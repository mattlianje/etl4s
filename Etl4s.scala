import scala.language.{higherKinds, implicitConversions}
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Try, Success, Failure}

package object etl4s {

  /** 
    * Type class representing the Arrow abstraction for data processing components.
    *
    * Defines the capability for a binary type constructor F[_,_] to be treated as an 
    * Arrow in the categorical sense, enabling composition through the ~> operator.
    * 
    * TL;DR This enables composition between different types of processing components
    * (functions, ETL components, pipelines) using the same ~> operator.
    */
  trait AsArrow[F[_, _]] {
    def toNode[A, B](f: F[A, B]): Node[A, B]
  }

  object AsArrow {

    /** 
      * Summoner method for obtaining type class arrow evidence.
      */
    def apply[F[_, _]](implicit ev: AsArrow[F]): AsArrow[F] = ev

    /** 
      * Enables standard Scala functions to be treated as arrows.
      * Maps them directly to Transform nodes as they share the same semantics.
      */
    implicit val function1AsArrow: AsArrow[Function1] = new AsArrow[Function1] {
      def toNode[A, B](f: A => B): Node[A, B] = Transform(f)
    }

    implicit val nodeAsArrow: AsArrow[Node] = new AsArrow[Node] {
      def toNode[A, B](n: Node[A, B]): Node[A, B] = n
    }

    /**
      * ETL components can be modeled as arrows - provides typeclasses for them
      */
    implicit val extractAsArrow: AsArrow[Extract] = new AsArrow[Extract] {
      def toNode[A, B](e: Extract[A, B]): Node[A, B] = e
    }

    implicit val transformAsArrow: AsArrow[Transform] = new AsArrow[Transform] {
      def toNode[A, B](t: Transform[A, B]): Node[A, B] = t
    }

    implicit val loadAsArrow: AsArrow[Load] = new AsArrow[Load] {
      def toNode[A, B](l: Load[A, B]): Node[A, B] = l
    }

    /**
      * Provides type class evidence for pipeline
      */
    implicit val pipelineAsArrow: AsArrow[Pipeline] = new AsArrow[Pipeline] {
      def toNode[A, B](p: Pipeline[A, B]): Node[A, B] = p.node
    }
  }

  /**
    * Extension methods for any type constructor F[_, _] that has an AsArrow instance.
    * 
    * If you are new here, this is higher-kinded type "voodoo" that lets us define operations on ANY binary 
    * type constructor (F[_, _]), not just concrete types. This is what enables the 
    * ~> abstraction to work across different types.
    */
  implicit class ArrowOps[F[_, _], A, B](val fa: F[A, B]) {
    def ~>[G[_, _], C](gb: G[B, C])(implicit F: AsArrow[F], G: AsArrow[G]): Pipeline[A, C] = {
      val nodeA = F.toNode(fa)
      val nodeB = G.toNode(gb)

      Pipeline(new Node[A, C] {
        def runSync: A => C = nodeA.runSync andThen nodeB.runSync
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          nodeA.runAsync(ec)(a).flatMap(nodeB.runAsync(ec))
        }
      })
    }

    /* Convert arrow to pipeline */
    def toPipeline(implicit F: AsArrow[F]): Pipeline[A, B] = Pipeline(F.toNode(fa))
  }

  /**
    * Type class for environment compatibility between different component requirements.
    * Determines the environment type needed when connecting components with different requirements.
    */
  trait ReaderCompat[T1, T2, R] {
    def toT1(r: R): T1
    def toT2(r: R): T2
  }

  object ReaderCompat extends LowPriorityReaderCompat2 {

    /* Highest priority: Case 1 - same types */
    implicit def identityCompat[T]: ReaderCompat[T, T, T] =
      new ReaderCompat[T, T, T] {
        def toT1(r: T): T = r
        def toT2(r: T): T = r
      }
  }

  trait LowPriorityReaderCompat2 extends LowPriorityReaderCompat1 {

    /**
      * Case 2: T1 is a subtype of T2 (e.g., HasFullConfig extends HasDateRange)
      * Result type is the more specific one (T1)
      */
    implicit def t1SubT2[T1 <: T2, T2]: ReaderCompat[T1, T2, T1] =
      new ReaderCompat[T1, T2, T1] {
        def toT1(r: T1): T1 = r
        def toT2(r: T1): T2 = r /* This works because T1 <: T2 */
      }
  }

  trait LowPriorityReaderCompat1 {

    /**
      * Case 3: T2 is a subtype of T1 (e.g., HasDateRange extends HasBaseConfig)
      * Result type is the more specific one (T2)
      */
    implicit def t2SubT1[T1, T2 <: T1]: ReaderCompat[T1, T2, T2] =
      new ReaderCompat[T1, T2, T2] {
        def toT1(r: T2): T1 = r /* This works because T2 <: T1 */
        def toT2(r: T2): T2 = r
      }
  }

  implicit class ReaderArrowOps[T1, F[_, _], A, B](val fa: Reader[T1, F[A, B]])(implicit
    F: AsArrow[F]
  ) {
    /* For context to context with same type */
    def ~>[G[_, _], C](
      gb: Reader[T1, G[B, C]]
    )(implicit G: AsArrow[G]): Reader[T1, Pipeline[A, C]] = {
      for {
        a <- fa
        b <- gb
        nodeA = F.toNode(a)
        nodeB = G.toNode(b)
      } yield Pipeline(new Node[A, C] {
        def runSync: A => C = nodeA.runSync andThen nodeB.runSync
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          nodeA.runAsync(ec)(a).flatMap(nodeB.runAsync(ec))
        }
      })
    }

    /* For context to context with different but compatible types */
    def ~>[T2, G[_, _], C, R](
      gb: Reader[T2, G[B, C]]
    )(implicit
      G: AsArrow[G],
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Pipeline[A, C]] = {
      Reader { (env: R) =>
        val fa1   = fa.run(compat.toT1(env))
        val gb1   = gb.run(compat.toT2(env))
        val nodeA = F.toNode(fa1)
        val nodeB = G.toNode(gb1)

        Pipeline(new Node[A, C] {
          def runSync: A => C = nodeA.runSync andThen nodeB.runSync
          def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
            nodeA.runAsync(ec)(a).flatMap(nodeB.runAsync(ec))
          }
        })
      }
    }

    /* For context to function */
    def ~>[C](gb: Function1[B, C]): Reader[T1, Pipeline[A, C]] = {
      fa.map(a => {
        val nodeA = F.toNode(a)
        val nodeB = Transform(gb)
        Pipeline(new Node[A, C] {
          def runSync: A => C = nodeA.runSync andThen nodeB.runSync
          def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
            nodeA.runAsync(ec)(a).flatMap(nodeB.runAsync(ec))
          }
        })
      })
    }

    /* For context to E, T, L or Pipeline */
    def ~>[C](gb: ETLComponent[B, C]): Reader[T1, Pipeline[A, C]] = {
      fa.map(a => {
        val nodeA = F.toNode(a)
        Pipeline(new Node[A, C] {
          def runSync: A => C = nodeA.runSync andThen gb.runSync
          def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
            nodeA.runAsync(ec)(a).flatMap(gb.runAsync(ec))
          }
        })
      })
    }

    def toPipeline: Reader[T1, Pipeline[A, B]] = {
      fa.map(f => Pipeline(F.toNode(f)))
    }
  }

  /**
    * Extension methods for regular ETL components to connect with context-aware components
    */
  implicit class RegularComponentWithReaderOps[F[_, _], A, B, T](val fa: F[A, B])(implicit
    F: AsArrow[F]
  ) {

    /**
      * Connect a regular (non-context) component with a context-aware component
      */
    def ~>[G[_, _], C](
      gb: Reader[T, G[B, C]]
    )(implicit G: AsArrow[G]): Reader[T, Pipeline[A, C]] = {
      gb.map(b => {
        val nodeA = F.toNode(fa)
        val nodeB = G.toNode(b)

        Pipeline(new Node[A, C] {
          def runSync: A => C = nodeA.runSync andThen nodeB.runSync
          def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
            nodeA.runAsync(ec)(a).flatMap(nodeB.runAsync(ec))
          }
        })
      })
    }
  }

  implicit class PipelineSequence[A, B](val pipeline: Pipeline[A, B]) {
    /* Sequences two pipelines */
    def >>[C](next: Pipeline[Unit, C]): Pipeline[A, C] =
      Pipeline(new Node[A, C] {
        def runSync: A => C = a => {
          pipeline(a)
          next(())
        }
        def runAsync(implicit ec: ExecutionContext): A => Future[C] =
          a => pipeline.node.runAsync(ec)(a).flatMap(_ => next.node.runAsync(ec)(()))
      })
  }

  implicit class NodeSequence[B](val node: Node[Unit, B]) {
    /* Sequences a node with another node that has Unit input */
    def >>[C](next: Node[Unit, C]): Node[Unit, C] = new Node[Unit, C] {
      def runSync: Unit => C = _ => {
        node(())
        next(())
      }

      def runAsync(implicit ec: ExecutionContext): Unit => Future[C] = _ => {
        node.runAsync(ec)(()).flatMap(_ => next.runAsync(ec)(()))
      }
    }
  }

  implicit def pipelineToNode[A, B](p: Pipeline[A, B]): Node[A, B]           = p.node
  implicit def extractToPipeline[A, B](e: Extract[A, B]): Pipeline[A, B]     = Pipeline(e)
  implicit def transformToPipeline[A, B](t: Transform[A, B]): Pipeline[A, B] = Pipeline(t)
  implicit def loadToPipeline[A, B](l: Load[A, B]): Pipeline[A, B]           = Pipeline(l)
  implicit def functionToNode[A, B](f: A => B): Node[A, B]                   = Transform(f)
  implicit def functionToPipeline[A, B](f: A => B): Pipeline[A, B] = Pipeline(Transform(f))

  /**
    * Represents the result of validation - either a validated value or a list of error messages.
    */
  sealed trait ValidationResult[+A] {
    def isValid: Boolean

    /**
      * Returns the list of error messages from the validation.
      * Empty list if validation is successful.
      */
    def errors: List[String]
    def get: A /* Returns validated value if successful */

    /**
      * Combines this validation result with another using logical AND semantics.
      * Both validations must pass for the combined result to be valid.
      * All errors from both validations are collected.
      */
    def &&[B >: A](other: ValidationResult[B]): ValidationResult[B] = (this, other) match {
      case (Valid(a), Valid(_))         => Valid(a)
      case (Valid(_), invalid: Invalid) => invalid
      case (invalid: Invalid, Valid(_)) => invalid
      case (Invalid(e1), Invalid(e2))   => Invalid(e1 ++ e2)
    }

    /**
      * Combines this validation result with another using logical OR semantics.
      * At least one validation must pass for the combined result to be valid.
      * If both fail, all errors are collected.
      */
    def ||[B >: A](other: ValidationResult[B]): ValidationResult[B] = (this, other) match {
      case (Valid(a), _)              => Valid(a)
      case (_, Valid(b))              => Valid(b)
      case (Invalid(e1), Invalid(e2)) => Invalid(e1 ++ e2)
    }

    def map[B](f: A => B): ValidationResult[B] = this match {
      case Valid(a)   => Valid(f(a))
      case i: Invalid => i.asInstanceOf[ValidationResult[B]]
    }

    def flatMap[B](f: A => ValidationResult[B]): ValidationResult[B] = this match {
      case Valid(a)   => f(a)
      case i: Invalid => i.asInstanceOf[ValidationResult[B]]
    }
  }

  case class Valid[+A](value: A) extends ValidationResult[A] {
    def isValid: Boolean     = true
    def errors: List[String] = Nil
    def get: A               = value
  }

  case class Invalid(errors: List[String]) extends ValidationResult[Nothing] {
    def isValid: Boolean = false
    def get: Nothing     = throw new NoSuchElementException("Invalid.get")
  }

  /**
    * A type class for things that can be validated.
    * 
    * @tparam T The type of object being validated
    */
  trait Validated[T] {
    def validate(value: T): ValidationResult[T]

    def &&(other: Validated[T]): Validated[T] = (value: T) => {
      val result1 = this.validate(value)
      val result2 = other.validate(value)

      (result1, result2) match {
        case (Valid(_), Valid(_))         => result2
        case (Valid(_), invalid: Invalid) => invalid
        case (invalid: Invalid, Valid(_)) => invalid
        case (Invalid(e1), Invalid(e2))   => Invalid(e1 ++ e2)
      }
    }

    def ||(other: Validated[T]): Validated[T] = (value: T) => {
      this.validate(value) match {
        case v @ Valid(_) => v
        case Invalid(errors1) =>
          other.validate(value) match {
            case v @ Valid(_)     => v
            case Invalid(errors2) => Invalid(errors1 ++ errors2)
          }
      }
    }

    def apply(value: T): ValidationResult[T] = validate(value)
  }

  object Validated {
    def apply[T](f: T => ValidationResult[T]): Validated[T] = new Validated[T] {
      def validate(value: T): ValidationResult[T] = f(value)
    }
  }

  /**
    * A simple requirement validation that fails with the given message if the condition is false.
    * 
    * @param value The object being validated
    * @param condition The condition to check
    * @param message The error message if the condition is false
    * @return Valid(value) if the condition is true, Invalid with the message otherwise
    */
  def require[T](value: T, condition: => Boolean, message: => String): ValidationResult[T] =
    if (condition) Valid(value) else Invalid(List(message))

  def require(condition: => Boolean, message: => String): ValidationResult[Unit] =
    if (condition) Valid(()) else Invalid(List(message))

  def success[A](value: A): ValidationResult[A]        = Valid(value)
  def failure[A](message: String): ValidationResult[A] = Invalid(List(message))

  /**
   * Creates a Transform that performs a side effect on the data without modifying it.
   * This allows for logging, debugging, or monitoring within a pipeline chain.
   *
   * Example usage:
   * {{{
   * val pipeline = extract ~> tap(x => println(s"Processed: $x")) ~> transform ~> load
   * }}}
   *
   * @param f The function to execute as a side effect
   * @return A Transform that performs the side effect and returns the original value
   */
  def tap[A](f: A => Any): Transform[A, A] = Transform[A, A] { a =>
    f(a)
    a
  }

  /**
    * Aliases and shorthands
    */
  type Context[T, A] = Reader[T, A]

  object Context {
    def apply[T, A](f: T => A): Reader[T, A] = Reader(f)
    def pure[T, A](a: A): Reader[T, A]       = Reader.pure(a)
    def ask[T]: Reader[T, T]                 = Reader.ask
  }

  trait Etl4sContext[T] {
    type ExtractWithContext[A, B]   = Context[T, Extract[A, B]]
    type TransformWithContext[A, B] = Context[T, Transform[A, B]]
    type LoadWithContext[A, B]      = Context[T, Load[A, B]]
    type PipelineWithContext[A, B]  = Context[T, Pipeline[A, B]]
  }
}

package etl4s {

  /** Reader Monad: Used for dependency injection. This helps pass configuration
    * throughout your pipeline without threading it through every function. Think
    * of it as a way to access configuration whenever needed
    */
  case class Reader[R, A](run: R => A) {
    def map[B](f: A => B): Reader[R, B] = Reader(r => f(run(r)))
    def flatMap[B](f: A => Reader[R, B]): Reader[R, B] =
      Reader(r => f(run(r)).run(r))

    def provideContext(ctx: R): A = run(ctx)
  }

  object Reader {
    def pure[R, A](a: A): Reader[R, A] = Reader(_ => a)
    def ask[R]: Reader[R, R]           = Reader(identity)
  }

  /** Monoid: Used to define how to combine values (like concatenating lists)
    * This is needed for the Writer monad to know how to combine logs
    */
  trait Monoid[A] {
    def empty: A
    def combine(x: A, y: A): A
  }

  object Monoid {
    def apply[A](implicit M: Monoid[A]): Monoid[A] = M

    implicit val listMonoid: Monoid[List[String]] = new Monoid[List[String]] {
      def empty                                     = List.empty[String]
      def combine(x: List[String], y: List[String]) = x ++ y
    }

    implicit val vectorMonoid: Monoid[Vector[String]] =
      new Monoid[Vector[String]] {
        def empty                                         = Vector.empty[String]
        def combine(x: Vector[String], y: Vector[String]) = x ++ y
      }
  }

  /** Writer: Used to accumulate logs or other information during execution. This
    * lets you track what happened during your pipeline execution without having
    * to pass around a log variable
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

  /** Node: The core building block of the ETL pipeline. A Node processes data of
    * type A and produces data of type B. This can be done either synchronously
    * (runSync) or asynchronously (runAsync)
    */
  sealed trait Node[-A, +B] extends (A => B) { self =>
    override def apply(a: A): B = runSync(a)
    def runSync: A => B
    def runAsync(implicit ec: ExecutionContext): A => Future[B]

    def &[AA <: A, C](that: Node[AA, C]): Node[AA, (B, C)] =
      new Node[AA, (B, C)] {
        def runSync: AA => (B, C) = { a =>
          (self.runSync(a), that.runSync(a))
        }
        def runAsync(implicit ec: ExecutionContext): AA => Future[(B, C)] = { a =>
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

        def runAsync(implicit ec: ExecutionContext): AA => Future[(B, C)] = { a =>
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

    /**
      * Creates a node that will retry the operation if it fails.
      * 
      * @param maxAttempts The maximum number of attempts (including the initial attempt)
      * @param initialDelayMs The initial delay in milliseconds before retrying
      * @param backoffFactor The multiplier applied to delay with each retry attempt
      * @return A new Node with retry capability
      */
    def withRetry(
      maxAttempts: Int = 3,
      initialDelayMs: Long = 100,
      backoffFactor: Double = 2.0
    ): Node[A, B] = new Node[A, B] {
      def runSync: A => B = { input =>
        def attempt(remaining: Int, delay: Long): Try[B] = {
          Try(self.runSync(input)) match {
            case Success(result) => Success(result)
            case Failure(e) if remaining > 1 =>
              Thread.sleep(delay)
              attempt(
                remaining - 1,
                (delay * backoffFactor).toLong
              )
            case Failure(e) => Failure(e)
          }
        }
        attempt(maxAttempts, initialDelayMs).get
      }

      def runAsync(implicit ec: ExecutionContext): A => Future[B] = { input =>
        def attempt(remaining: Int, delay: Long): Future[B] = {
          Future(Try(self.runSync(input))).flatMap {
            case Success(result) => Future.successful(result)
            case Failure(e) if remaining > 1 =>
              Future {
                Thread.sleep(delay)
              }.flatMap { _ =>
                attempt(
                  remaining - 1,
                  (delay * backoffFactor).toLong
                )
              }
            case Failure(e) => Future.failed(e)
          }
        }
        attempt(maxAttempts, initialDelayMs)
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

        def runAsync(implicit ec: ExecutionContext): A => Future[BB] = { input =>
          self.runAsync(ec)(input).recover { case e => handler(e) }
        }
      }

    /** 
     * Allows observation of the data flowing through the Node without modifying it.
     * Useful for logging, debugging, or other side effects.
     */
    def tap[BB >: B](f: BB => Any): Node[A, B] = new Node[A, B] {
      def runSync: A => B = { a =>
        val result = self.runSync(a)
        f(result)
        result
      }

      def runAsync(implicit ec: ExecutionContext): A => Future[B] = { a =>
        self
          .runAsync(ec)(a)
          .map { result =>
            f(result)
            result
          }(ec)
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

  /* ETL Component trait - now as a trait instead of abstract class */
  trait ETLComponent[A, B] extends Node[A, B] {
    val f: A => B
    def runSync: A => B                                         = f
    def runAsync(implicit ec: ExecutionContext): A => Future[B] = a => Future(f(a))
  }

  /** Extract: The "E" in ETL - Extracts data from a source. This is typically
    * the first stage in an ETL pipeline It gets data from somewhere (database,
    * file, API, etc.)
    */
  case class Extract[A, B](override val f: A => B) extends ETLComponent[A, B] {
    override def map[C](g: B => C): Extract[A, C] = Extract(f andThen g)

    def flatMap[C](g: B => Extract[A, C]): Extract[A, C] =
      Extract(a => {
        val b = f(a)
        g(b).f(a)
      })

    def andThen[C](that: Extract[B, C]): Extract[A, C] = Extract(f andThen that.f)
  }

  object Extract {
    def apply[A](value: A): Extract[Unit, A] = Extract(_ => value)
    def pure[A]: Extract[A, A]               = Extract[A, A](a => a)
  }

  /** Transform: The "T" in ETL - Transforms data from one form to another. This
    * is typically the middle stage in an ETL pipeline It processes and changes
    * the data in some way
    */
  case class Transform[A, B](override val f: A => B) extends ETLComponent[A, B] {
    override def map[C](g: B => C): Transform[A, C] = Transform(f andThen g)

    def flatMap[C](g: B => Transform[A, C]): Transform[A, C] =
      Transform(a => {
        val b = f(a)
        g(b).f(a)
      })

    def andThen[C](that: Transform[B, C]): Transform[A, C] = Transform(f andThen that.f)
  }

  object Transform {
    def apply[A](value: A): Transform[Unit, A] = Transform(_ => value)
    def pure[A]: Transform[A, A]               = Transform[A, A](a => a)
  }

  /** Load: The "L" in ETL - Loads data into a destination. This is typically the
    * final stage in an ETL pipeline It writes data somewhere (database, file,
    * API, etc.)
    */
  case class Load[A, B](override val f: A => B) extends ETLComponent[A, B] {
    override def map[C](g: B => C): Load[A, C] = Load(f andThen g)

    def flatMap[C](g: B => Load[A, C]): Load[A, C] =
      Load(a => {
        val b = f(a)
        g(b).f(a)
      })

    def andThen[C](that: Load[B, C]): Load[A, C] = Load(f andThen that.f)
  }

  object Load {
    def apply[A](value: A): Load[Unit, A] = Load(_ => value)
    def pure[A]: Load[A, A]               = Load[A, A](a => a)
  }

  /** Pipeline: A complete ETL flow from input to output. Pipelines combine
    * multiple operations into a single data processing flow
    *
    * IMPORTANT: You don't have to use Nodes, Extract, Transform, or Load
    * directly The simplest way to create a pipeline is with a regular function:
    *
    * Example:
    *  // Create a pipeline directly from a function
    *  val simplePipeline = Pipeline((s: String) => s.toUpperCase)
    *
    *  // Run the pipeline
    *  val result = simplePipeline.unsafeRun("hello") // Returns "HELLO"
    *
    *  // Connect pipelines with the ~> operator
    *  val combinedPipeline = Pipeline((s: String) => s.length) ~> Pipeline((n: Int) => n * 2)
    */
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
        def runAsync(implicit ec: ExecutionContext): A => Future[(B, C)] = { a =>
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
        def runAsync(implicit ec: ExecutionContext): A => Future[(B, C)] = { a =>
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

    /* Flatten a pipeline that returns another pipeline */
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

    /* Transform the output of this pipeline */
    def map[C](f: B => C): Pipeline[A, C] = this ~> Transform(f)

    private def runSync(input: A): B = node.runSync(input)
    private def runAsync(input: A)(implicit ec: ExecutionContext): Future[B] =
      node.runAsync.apply(input)

    def unsafeRun(input: A): B    = runSync(input)
    def safeRun(input: A): Try[B] = Try(runSync(input))

    /**
      * Runs the pipeline and returns both the result and the execution time in milliseconds.
      * This is useful for performance monitoring and benchmarking.
      *
      * @param input The input value to the pipeline
      * @return A tuple containing the result and the execution time in milliseconds
      */
    def unsafeRunTimed(input: A): (B, Long) = {
      val startTime = System.currentTimeMillis()
      val result    = node.runSync(input)
      val endTime   = System.currentTimeMillis()
      (result, endTime - startTime)
    }

    /**
      * Runs the pipeline asynchronously and returns a Future containing both 
      * the result and the execution time in milliseconds.
      *
      * @param input The input value to the pipeline
      * @return A Future containing a tuple of the result and execution time in milliseconds
      */
    def unsafeRunTimedAsync(input: A)(implicit ec: ExecutionContext): Future[(B, Long)] = {
      val startTime = System.currentTimeMillis()
      node
        .runAsync(ec)(input)
        .map { result =>
          val endTime = System.currentTimeMillis()
          (result, endTime - startTime)
        }(ec)
    }

    /**
      * Runs the pipeline safely (catching exceptions) and returns both the result as Try[B]
      * and the execution time in milliseconds.
      *
      * @param input The input value to the pipeline
      * @return A tuple containing the Try[Result] and the execution time in milliseconds
      */
    def safeRunTimed(input: A): (Try[B], Long) = {
      val startTime = System.currentTimeMillis()
      val result    = Try(node.runSync(input))
      val endTime   = System.currentTimeMillis()
      (result, endTime - startTime)
    }

    /**
      * Creates a pipeline that will retry the operation if it fails.
      * 
      * @param maxAttempts The maximum number of attempts (including the initial attempt)
      * @param initialDelayMs The initial delay in milliseconds before retrying
      * @param backoffFactor The multiplier applied to delay with each retry attempt
      * @return A new Pipeline with retry capability
      */
    def withRetry(
      maxAttempts: Int = 3,
      initialDelayMs: Long = 100,
      backoffFactor: Double = 2.0
    ): Pipeline[A, B] = Pipeline(node.withRetry(maxAttempts, initialDelayMs, backoffFactor))

    def onFailure[BB >: B](handler: Throwable => BB): Pipeline[A, BB] =
      Pipeline(node.onFailure(handler))

    /** 
      * Allows observation of the data flowing through the Pipeline without modifying it.
      * Useful for logging, debugging, or other side effects.
      */
    def tap(f: B => Any): Pipeline[A, B] = Pipeline(node.tap(f))
  }

  object Pipeline {
    /* Create an identity pipeline */
    def pure[A]: Pipeline[A, A] = Pipeline(Node.pure[A])

    /* To create a pipeline directly from a function */
    def apply[A, B](f: A => B): Pipeline[A, B] = Pipeline(Transform(f))
  }

  /** Flatten typeclasses for tuple flattening. This helps transform nested
    * tuples like ((a,b),c) into (a,b,c). Makes pipelines that combine multiple
    * steps more ergonomic. Yuck - but don't wan't to use shapeless. Also can't
    * nest past 7-8 ish to cross build with 2.12
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
    implicit def tuple4[A, B, C, D]: Flatten.Aux[(((A, B), C), D), (A, B, C, D)] =
      new Flatten[(((A, B), C), D)] {
        type Out = (A, B, C, D)
        def apply(t: (((A, B), C), D)): (A, B, C, D) = {
          val (((a, b), c), d) = t
          (a, b, c, d)
        }
      }
  }

  trait P3 extends P2 {
    implicit def tuple5[A, B, C, D, E]: Flatten.Aux[((((A, B), C), D), E), (A, B, C, D, E)] =
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
