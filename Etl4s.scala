import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Try, Success, Failure}

package object etl4s {
  implicit class NodeOps[A, B](node: Node[A, B]) {

    /** Base etl4s connection operator (for nodes and pipelines)
      */
    def ~>[C](next: Node[B, C]): Pipeline[A, C] =
      Pipeline(new Node[A, C] {
        def runSync: A => C = node.runSync andThen next.runSync
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          node.runAsync(ec)(a).flatMap(next.runAsync(ec))
        }
      })
  }

  implicit class PipelineSequence[A, B](val pipeline: Pipeline[A, B]) {

    /** Sequences two pipelines, running the second after the first completes.
      * The second pipeline must take Unit as input and will be executed
      * regardless of the output from the first pipeline.
      *
      * It makes intuitive sense that subsequent pipelines must take Unit but
      * TODO think how to communicate this to user.
      */
    def >>[C](next: Pipeline[Unit, C]): Pipeline[A, C] =
      Pipeline(new Node[A, C] {
        def runSync: A => C = a => {
          pipeline.unsafeRun(a)
          next.unsafeRun(())
        }

        def runAsync(implicit ec: ExecutionContext): A => Future[C] =
          a => pipeline.runAsync(ec)(a).flatMap(_ => next.runAsync(ec)(()))(ec)
      })
  }

  /* Implicit conversions for Scala 2.x compat */
  implicit def pipelineToNode[A, B](p: Pipeline[A, B]): Node[A, B] = p.node

  implicit def extractToPipeline[A, B](e: Extract[A, B]): Pipeline[A, B] =
    Pipeline(e)
  implicit def transformToPipeline[A, B](t: Transform[A, B]): Pipeline[A, B] =
    Pipeline(t)
  implicit def loadToPipeline[A, B](l: Load[A, B]): Pipeline[A, B] = Pipeline(l)
}

package etl4s {

  /** Base etl4s dependency injection Reader monad I encourage you to use the
    * better built homologues from Cats or elsewhere
    */
  case class Reader[R, A](run: R => A) {
    def map[B](f: A => B): Reader[R, B] = Reader(r => f(run(r)))
    def flatMap[B](f: A => Reader[R, B]): Reader[R, B] =
      Reader(r => f(run(r)).run(r))
  }

  object Reader {
    def pure[R, A](a: A): Reader[R, A] = Reader(_ => a)
    def ask[R]: Reader[R, R] = Reader(identity)
  }

  /** Validated monad for error accumulating pipelines... Same recommendation as
    * above
    */
  case class Validated[+E, +A](value: Either[List[E], A]) {
    def map[B](f: A => B): Validated[E, B] =
      Validated(value.map(f))

    def flatMap[EE >: E, B](f: A => Validated[EE, B]): Validated[EE, B] =
      Validated(value.flatMap(a => f(a).value))

    def zip[EE >: E, B](that: Validated[EE, B]): Validated[EE, (A, B)] =
      (this.value, that.value) match {
        case (Right(a), Right(b)) => Validated.valid((a, b))
        case (Left(e1), Left(e2)) => Validated(Left(e1 ++ e2))
        case (Left(e), _)         => Validated(Left(e))
        case (_, Left(e))         => Validated(Left(e))
      }
  }

  object Validated {
    def valid[E, A](a: A): Validated[E, A] = Validated(Right(a))
    def invalid[E, A](e: E): Validated[E, A] = Validated(Left(List(e)))
  }

  /** Basic Monoid to help encode Writer
    */
  trait Monoid[A] {
    def empty: A
    def combine(x: A, y: A): A
  }

  object Monoid {
    def apply[A](implicit M: Monoid[A]): Monoid[A] = M

    implicit val listMonoid: Monoid[List[String]] = new Monoid[List[String]] {
      def empty = List.empty[String]
      def combine(x: List[String], y: List[String]) = x ++ y
    }

    implicit val vectorMonoid: Monoid[Vector[String]] =
      new Monoid[Vector[String]] {
        def empty = Vector.empty[String]
        def combine(x: Vector[String], y: Vector[String]) = x ++ y
      }
  }

  /** Writer to encode log accumulating pipelines Same recommendation for using
    * better production grade implementations
    */
  case class Writer[L, A](run: () => (L, A))(implicit L: Monoid[L]) {
    def map[B](f: A => B): Writer[L, B] = Writer(() => {
      val (l, a) = run()
      (l, f(a))
    })

    def flatMap[B](f: A => Writer[L, B]): Writer[L, B] = Writer(() => {
      val (l1, a) = run()
      val (l2, b) = f(a).run()
      (L.combine(l1, l2), b)
    })

    def value: A = run()._2
  }

  object Writer {
    def apply[L: Monoid, A](l: L, a: A): Writer[L, A] =
      Writer(() => (l, a))

    def tell[L: Monoid](l: L): Writer[L, Unit] =
      Writer(() => (l, ()))

    def pure[L: Monoid, A](a: A): Writer[L, A] =
      Writer(() => (Monoid[L].empty, a))
  }

  case class RetryConfig(
      maxAttempts: Int = 3,
      initialDelay: Duration = 100.millis,
      backoffFactor: Double = 2.0
  )

  sealed trait Node[-A, +B] { self =>

    def &[AA <: A, C](that: Node[AA, C]): Node[AA, (B, C)] =
      new Node[AA, (B, C)] {
        def runSync: AA => (B, C) = { a =>
          (self.runSync(a), that.runSync(a))
        }
        def runAsync(implicit ec: ExecutionContext): AA => Future[(B, C)] = {
          a =>
            for {
              r1 <- self.runAsync(ec)(a)
              r2 <- that.runAsync(ec)(a)
            } yield (r1, r2)
        }
      }

    def &>[AA <: A, C](
        that: Node[AA, C]
    )(implicit ec: ExecutionContext): Node[AA, (B, C)] = {
      new Node[AA, (B, C)] {
        def runSync: AA => (B, C) = { a =>
          val f1 = Future(self.runSync(a))
          val f2 = Future(that.runSync(a))
          val combined = for {
            r1 <- f1
            r2 <- f2
          } yield (r1, r2)
          Await.result(combined, Duration.Inf): (B, C)
        }

        def runAsync(implicit ec: ExecutionContext): AA => Future[(B, C)] = {
          a =>
            val f1 = self.runAsync(ec)(a)
            val f2 = that.runAsync(ec)(a)
            for {
              r1 <- f1
              r2 <- f2
            } yield (r1, r2)
        }
      }
    }

    def zip[BB >: B, Out](implicit
        flattener: Flatten.Aux[BB, Out]
    ): Node[A, Out] = new Node[A, Out] {
      def runSync: A => Out = { a =>
        flattener(self.runSync(a))
      }
      def runAsync(implicit ec: ExecutionContext): A => Future[Out] = { a =>
        self.runAsync(ec)(a).map(b => flattener(b))(ec)
      }
    }

    def runSync: A => B
    def runAsync(implicit ec: ExecutionContext): A => Future[B]
    def run[C]: C => A => B = _ => runSync

    def map[C](g: B => C): Node[A, C] = new Node[A, C] {
      def runSync: A => C = self.runSync andThen g
      def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
        self.runAsync(ec)(a).map(g)(ec)
      }
    }

    def flatMap[AA <: A, C](g: B => Node[AA, C]): Node[AA, C] =
      new Node[AA, C] {
        def runSync: AA => C = { a =>
          val b = self.runSync(a)
          g(b).runSync(a)
        }
        def runAsync(implicit ec: ExecutionContext): AA => Future[C] = { a =>
          self.runAsync(ec)(a).flatMap(b => g(b).runAsync(ec)(a))(ec)
        }
      }

    def withRetry(config: RetryConfig): Node[A, B] = new Node[A, B] {
      def runSync: A => B = { input =>
        def attempt(remaining: Int, delay: Duration): Try[B] = {
          Try(Node.this.runSync(input)) match {
            case Success(result) => Success(result)
            case Failure(e) if remaining > 1 =>
              Thread.sleep(delay.toMillis)
              attempt(
                remaining - 1,
                Duration.fromNanos(
                  (delay.toNanos * config.backoffFactor).toLong
                )
              )
            case Failure(e) => Failure(e)
          }
        }
        attempt(
          config.maxAttempts,
          config.initialDelay
        ).get
      }

      def runAsync(implicit ec: ExecutionContext): A => Future[B] = { input =>
        def attempt(remaining: Int, delay: Duration): Future[B] = {
          Future(Try(Node.this.runSync(input))).flatMap {
            case Success(result) => Future.successful(result)
            case Failure(e) if remaining > 1 =>
              Future {
                Thread.sleep(delay.toMillis)
              }.flatMap { _ =>
                attempt(
                  remaining - 1,
                  Duration.fromNanos(
                    (delay.toNanos * config.backoffFactor).toLong
                  )
                )
              }
            case Failure(e) => Future.failed(e)
          }
        }
        attempt(config.maxAttempts, config.initialDelay)
      }
    }

    def onFailure[BB >: B](handler: Throwable => BB): Node[A, BB] =
      new Node[A, BB] {
        def runSync: A => BB = { input =>
          Try(self.runSync(input)) match {
            case Success(result) => result
            case Failure(e)      => handler(e)
          }
        }

        def runAsync(implicit ec: ExecutionContext): A => Future[BB] = {
          input =>
            self.runAsync(ec)(input).recover { case e => handler(e) }
        }
      }
  }

  object Node {
    def pure[A]: Node[A, A] = new Node[A, A] {
      def runSync: A => A = a => a
      def runAsync(implicit ec: ExecutionContext): A => Future[A] =
        a => Future.successful(a)
    }
  }

  case class Extract[A, B](f: A => B) extends Node[A, B] {
    def runSync: A => B = f
    def runAsync(implicit ec: ExecutionContext): A => Future[B] =
      a => Future(f(a))

    override def map[C](g: B => C): Extract[A, C] =
      new Extract[A, C](f andThen g)

    def flatMap[C](g: B => Extract[A, C]): Extract[A, C] =
      new Extract[A, C](a => {
        val b = f(a)
        g(b).f(a)
      })
    def andThen[C](that: Extract[B, C]): Extract[A, C] = Extract(
      f andThen that.f
    )
  }

  object Extract {
    def apply[A](value: A): Extract[Unit, A] = Extract(_ => value)
    def pure[A]: Extract[A, A] = Extract(a => a)
    def async[A, B](f: A => Future[B])(implicit
        ec: ExecutionContext
    ): Extract[A, B] =
      Extract(a => Await.result(f(a), Duration.Inf))
  }

  case class Transform[A, B](f: A => B) extends Node[A, B] {
    def runSync: A => B = f
    def runAsync(implicit ec: ExecutionContext): A => Future[B] =
      a => Future(f(a))

    def andThen[C](that: Transform[B, C]): Transform[A, C] =
      Transform(f andThen that.f)

    def flatMap[C](g: B => Transform[A, C]): Transform[A, C] =
      Transform { a =>
        val b = f(a)
        g(b).f(a)
      }

    override def map[C](g: B => C): Transform[A, C] = Transform(f andThen g)
  }

  object Transform {
    def pure[A]: Transform[A, A] = Transform(a => a)
  }

  case class Load[A, B](f: A => B) extends Node[A, B] {
    def runSync: A => B = f
    def runAsync(implicit ec: ExecutionContext): A => Future[B] =
      a => Future(f(a))

    override def map[C](g: B => C): Load[A, C] = new Load[A, C](f andThen g)

    def flatMap[C](g: B => Load[A, C]): Load[A, C] =
      new Load[A, C](a => {
        val b = f(a)
        g(b).f(a)
      })
    def andThen[C](that: Load[B, C]): Load[A, C] = Load(f andThen that.f)
  }

  object Load {
    def pure[A]: Load[A, A] = Load(a => a)
    def async[A, B](f: A => Future[B])(implicit
        ec: ExecutionContext
    ): Load[A, B] =
      Load(a => Await.result(f(a), Duration.Inf))
  }

  case class Pipeline[A, B](node: Node[A, B]) {
    def ~>[C](next: Node[B, C]): Pipeline[A, C] =
      Pipeline(new Node[A, C] {
        def runSync: A => C = node.runSync andThen next.runSync
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          node.runAsync.apply(a).flatMap(next.runAsync.apply)
        }
      })

    def &[C](that: Pipeline[A, C]): Pipeline[A, (B, C)] =
      Pipeline(new Node[A, (B, C)] {
        def runSync: A => (B, C) = { a =>
          (node.runSync(a), that.node.runSync(a))
        }
        def runAsync(implicit ec: ExecutionContext): A => Future[(B, C)] = {
          a =>
            for {
              r1 <- node.runAsync(ec)(a)
              r2 <- that.node.runAsync(ec)(a)
            } yield (r1, r2)
        }
      })

    def &>[C](
        that: Pipeline[A, C]
    )(implicit ec: ExecutionContext): Pipeline[A, (B, C)] =
      Pipeline(new Node[A, (B, C)] {
        def runSync: A => (B, C) = { a =>
          val f1 = Future(node.runSync(a))
          val f2 = Future(that.node.runSync(a))
          val combined = for {
            r1 <- f1
            r2 <- f2
          } yield (r1, r2)
          Await.result(combined, Duration.Inf)
        }
        def runAsync(implicit ec: ExecutionContext): A => Future[(B, C)] = {
          a =>
            val f1 = node.runAsync(ec)(a)
            val f2 = that.node.runAsync(ec)(a)
            for {
              r1 <- f1
              r2 <- f2
            } yield (r1, r2)
        }
      })

    def flatMap[C](f: B => Pipeline[A, C]): Pipeline[A, C] = {
      Pipeline(new Node[A, C] {
        def runSync: A => C = { a =>
          val b = node.runSync(a)
          f(b).runSync(a)
        }
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          node.runAsync.apply(a).flatMap(b => f(b).runAsync(a))
        }
      })
    }

    def flatten[C](implicit ev: B <:< Pipeline[A, C]): Pipeline[A, C] = {
      Pipeline(new Node[A, C] {
        def runSync: A => C = { a =>
          val innerPipeline = ev(node.runSync(a))
          innerPipeline.runSync(a)
        }
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          node.runAsync.apply(a).flatMap(b => ev(b).runAsync(a))
        }
      })
    }

    def map[C](f: B => C): Pipeline[A, C] = this ~> Transform(f)

    private def runSync(input: A): B = node.runSync(input)

    private def runAsync(input: A)(implicit ec: ExecutionContext): Future[B] =
      node.runAsync.apply(input)

    def unsafeRun(input: A): B = runSync(input)

    def safeRun(input: A): Try[B] = Try(runSync(input))

    def withRetry(config: RetryConfig = RetryConfig()): Pipeline[A, B] =
      Pipeline(node.withRetry(config))

    def onFailure[BB >: B](handler: Throwable => BB): Pipeline[A, BB] =
      Pipeline(node.onFailure(handler))
  }

  object Pipeline {
    def pure[A]: Pipeline[A, A] = Pipeline(Node.pure[A])

    /* To conjur up pipelines directly */
    def apply[A, B](f: A => B): Pipeline[A, B] = Pipeline(Transform(f))
  }

  /** Flatten - utility typeclasses for tuple flattening Yuck - but don't want
    * to use shapeless Also can't nest past 7-8ish (not sure) to cross build w/
    * 2.12
    */
  trait Flatten[A] {
    type Out
    def apply(a: A): Out
  }

  trait P0 {
    implicit def base[A]: Flatten.Aux[A, A] = new Flatten[A] {
      type Out = A
      def apply(a: A): A = a
    }
  }

  trait P1 extends P0 {
    implicit def tuple3[A, B, C]: Flatten.Aux[((A, B), C), (A, B, C)] =
      new Flatten[((A, B), C)] {
        type Out = (A, B, C)
        def apply(t: ((A, B), C)): (A, B, C) = {
          val ((a, b), c) = t
          (a, b, c)
        }
      }
  }

  trait P2 extends P1 {
    implicit def tuple4[A, B, C, D]
        : Flatten.Aux[(((A, B), C), D), (A, B, C, D)] =
      new Flatten[(((A, B), C), D)] {
        type Out = (A, B, C, D)
        def apply(t: (((A, B), C), D)): (A, B, C, D) = {
          val (((a, b), c), d) = t
          (a, b, c, d)
        }
      }
  }

  trait P3 extends P2 {
    implicit def tuple5[A, B, C, D, E]
        : Flatten.Aux[((((A, B), C), D), E), (A, B, C, D, E)] =
      new Flatten[((((A, B), C), D), E)] {
        type Out = (A, B, C, D, E)
        def apply(t: ((((A, B), C), D), E)): (A, B, C, D, E) = {
          val ((((a, b), c), d), e) = t
          (a, b, c, d, e)
        }
      }
  }

  trait P4 extends P3 {
    implicit def tuple6[A, B, C, D, E, F]
        : Flatten.Aux[(((((A, B), C), D), E), F), (A, B, C, D, E, F)] =
      new Flatten[(((((A, B), C), D), E), F)] {
        type Out = (A, B, C, D, E, F)
        def apply(t: (((((A, B), C), D), E), F)): (A, B, C, D, E, F) = {
          val (((((a, b), c), d), e), f) = t
          (a, b, c, d, e, f)
        }
      }
  }

  trait P5 extends P4 {
    implicit def tuple7[A, B, C, D, E, F, G]
        : Flatten.Aux[((((((A, B), C), D), E), F), G), (A, B, C, D, E, F, G)] =
      new Flatten[((((((A, B), C), D), E), F), G)] {
        type Out = (A, B, C, D, E, F, G)
        def apply(t: ((((((A, B), C), D), E), F), G)): (A, B, C, D, E, F, G) = {
          val ((((((a, b), c), d), e), f), g) = t
          (a, b, c, d, e, f, g)
        }
      }
  }

  trait P6 extends P5 {
    implicit def tuple8[A, B, C, D, E, F, G, H]: Flatten.Aux[
      (((((((A, B), C), D), E), F), G), H),
      (A, B, C, D, E, F, G, H)
    ] =
      new Flatten[(((((((A, B), C), D), E), F), G), H)] {
        type Out = (A, B, C, D, E, F, G, H)
        def apply(
            t: (((((((A, B), C), D), E), F), G), H)
        ): (A, B, C, D, E, F, G, H) = {
          val (((((((a, b), c), d), e), f), g), h) = t
          (a, b, c, d, e, f, g, h)
        }
      }
  }

  trait P7 extends P6 {
    implicit def tuple9[A, B, C, D, E, F, G, H, I]: Flatten.Aux[
      ((((((((A, B), C), D), E), F), G), H), I),
      (A, B, C, D, E, F, G, H, I)
    ] =
      new Flatten[((((((((A, B), C), D), E), F), G), H), I)] {
        type Out = (A, B, C, D, E, F, G, H, I)
        def apply(
            t: ((((((((A, B), C), D), E), F), G), H), I)
        ): (A, B, C, D, E, F, G, H, I) = {
          val ((((((((a, b), c), d), e), f), g), h), i) = t
          (a, b, c, d, e, f, g, h, i)
        }
      }
  }

  trait P8 extends P7 {
    implicit def tuple10[A, B, C, D, E, F, G, H, I, J]: Flatten.Aux[
      (((((((((A, B), C), D), E), F), G), H), I), J),
      (A, B, C, D, E, F, G, H, I, J)
    ] =
      new Flatten[(((((((((A, B), C), D), E), F), G), H), I), J)] {
        type Out = (A, B, C, D, E, F, G, H, I, J)
        def apply(
            t: (((((((((A, B), C), D), E), F), G), H), I), J)
        ): (A, B, C, D, E, F, G, H, I, J) = {
          val (((((((((a, b), c), d), e), f), g), h), i), j) = t
          (a, b, c, d, e, f, g, h, i, j)
        }
      }
  }

  trait P9 extends P8 {
    implicit def tuple11[A, B, C, D, E, F, G, H, I, J, K]: Flatten.Aux[
      ((((((((((A, B), C), D), E), F), G), H), I), J), K),
      (A, B, C, D, E, F, G, H, I, J, K)
    ] =
      new Flatten[((((((((((A, B), C), D), E), F), G), H), I), J), K)] {
        type Out = (A, B, C, D, E, F, G, H, I, J, K)
        def apply(
            t: ((((((((((A, B), C), D), E), F), G), H), I), J), K)
        ): (A, B, C, D, E, F, G, H, I, J, K) = {
          val ((((((((((a, b), c), d), e), f), g), h), i), j), k) = t
          (a, b, c, d, e, f, g, h, i, j, k)
        }
      }
  }

  trait P10 extends P9 {
    implicit def tuple12[A, B, C, D, E, F, G, H, I, J, K, L]: Flatten.Aux[
      (((((((((((A, B), C), D), E), F), G), H), I), J), K), L),
      (A, B, C, D, E, F, G, H, I, J, K, L)
    ] =
      new Flatten[(((((((((((A, B), C), D), E), F), G), H), I), J), K), L)] {
        type Out = (A, B, C, D, E, F, G, H, I, J, K, L)
        def apply(
            t: (((((((((((A, B), C), D), E), F), G), H), I), J), K), L)
        ): (A, B, C, D, E, F, G, H, I, J, K, L) = {
          val (((((((((((a, b), c), d), e), f), g), h), i), j), k), l) = t
          (a, b, c, d, e, f, g, h, i, j, k, l)
        }
      }
  }

  object Flatten extends P10 {
    type Aux[A, B] = Flatten[A] { type Out = B }
  }
}

/* Rough EBNF
(* Core Types *)
Pipeline    ::= Node {~> Node} [.withRetry(RetryConfig)] [.onFailure(Handler)]
Node        ::= BasicNode | ComposedNode [.withRetry(RetryConfig)] [.onFailure(Handler)]

(* Basic Nodes *)
BasicNode   ::= Extract | Transform | Load
Extract     ::= Extract [Type, Type] (Function)
Transform   ::= Transform [Type, Type] (Function)
Load        ::= Load [Type, Type] (Function)

(* Node Composition *)
ComposedNode::= Node & Node              (* Sequential composition *)
             | Node &> Node              (* Parallel composition *)
             | Node andThen Node         (* Same-type chaining *)

(* Type System *)
Type        ::= Identifier | GenericType | EffectType
GenericType ::= Identifier [Type {, Type}]
EffectType  ::= Reader [Type, Type]
             | Writer [Type, Type]
             | Validated [Type, Type]

(* Operations *)
Zip         ::= Node.zip                 (* Flattens any nested tuple *)
             | Validated.zip(Validated)  (* Combines validations *)

(* Base Elements *)
Function    ::= Identifier | (Params => Expr)
Identifier  ::= Letter {Letter | Digit}
 */
