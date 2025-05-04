/*
 * +==========================================================================+
 * |                                 etl4s                                    |
 * |              Powerful, whiteboard-style pipelines for Scala              |
 * |                            Version 1.4.0                                 |
 * |                 Compatible with Scala 2.12, 2.13, and 3                  |
 * |                                                                          |
 * | Copyright 2025 Matthieu Court (matthieu.court@protonmail.com)            |
 * | Apache License 2.0                                                       |
 * |                                                                          |
 * | Drop into your project like a header file. Everything is defined in this |
 * | package object: just import etl4s._ to use                               |
 * +==========================================================================+
 */

package object etl4s {
  import scala.language.{higherKinds, implicitConversions}
  import scala.concurrent.{Future, ExecutionContext}
  import scala.concurrent.duration._
  import scala.concurrent.Await
  import scala.util.Try

  /** The core abstraction of etl4s: a composable wrapper around a function:
    * f: A => B.
    *
    * (Extract, Transform, Load, Pipeline) are just type aliases of Node
    */
  trait Node[A, B] {

    /** Core function wrapped by the node */
    val f: A => B

    def apply(a: A): B = f(a)

    /** Run the node with timing and error handling */
    def unsafeRun(a: A): B    = f(a)
    def safeRun(a: A): Try[B] = Try(f(a))
    def unsafeRunTimedMillis(a: A): (B, Long) = {
      val startTime = System.currentTimeMillis()
      val result    = f(a)
      val endTime   = System.currentTimeMillis()
      (result, endTime - startTime)
    }

    /** Functorial mapping (map):
      *
      * val lengthNode = Node[String, Int](_.length) val doubledNode =
      * lengthNode.map(_ * 2) // Creates Node[String, Int] doubledNode("hello")
      */

    def map[C](g: B => C): Node[A, C] = Node(a => g(f(a)))

    /** Monadic binding (flatMap):
      *
      * val get = Node[String, Int](_.toInt) val process = get.flatMap(n =>
      * Node[String, String](_ => "~" * n)) process("5") // Returns "~~~~~"
      */
    def flatMap[C](g: B => Node[A, C]): Node[A, C] = Node { a =>
      val b = f(a)
      g(b)(a)
    }

    /** 
      * Sequential composition (~>): Chain nodes together where the output of one becomes the input to the next.
      * Supports both regular Node and Reader-wrapped Node as the next step.
      */
    def ~>[C](next: Node[B, C]): Node[A, C]      = Node(a => next(f(a)))
    def andThen[C](next: Node[B, C]): Node[A, C] = Node(a => next(f(a)))
    def ~>[T, C](next: Reader[T, Node[B, C]]): Reader[T, Node[A, C]] = {
      next.map(nextNode => this ~> nextNode)
    }

    /**
      * Sequential operation (>>): Execute this node then execute the next with unit input.
      * The result of the first node is ignored and the result of the second node is returned.
      * Supports both regular Node and Reader-wrapped Node as the next step.
      */
    def >>[C](next: Node[Unit, C]): Node[A, C] = Node { a =>
      f(a)
      next(()) /* Execute the next node with unit input and return its result */
    }

    def >>[T, C](next: Reader[T, Node[Unit, C]]): Reader[T, Node[A, C]] = {
      next.map(nextNode => this >> nextNode)
    }

    /**
      * Parallel composition (&): Execute two nodes with the same input and combine their results into a tuple.
      * Supports both regular Node and Reader-wrapped Node as the second operand.
      */
    def &[C](that: Node[A, C]): Node[A, (B, C)] = Node { a =>
      (f(a), that(a))
    }

    def &[T, C](that: Reader[T, Node[A, C]]): Reader[T, Node[A, (B, C)]] = {
      that.map(thatNode => this & thatNode)
    }

    /**
      * Parallel composition with concurrency (&>): Execute two nodes concurrently using Futures.
      * Results are combined into a tuple once both operations complete.
      * Supports both regular Node and Reader-wrapped Node as the second operand.
      */
    def &>[C](that: Node[A, C])(implicit
      ec: ExecutionContext
    ): Node[A, (B, C)] = Node { a =>
      val f1 = Future(f(a))
      val f2 = Future(that(a))
      val combined = for {
        r1 <- f1
        r2 <- f2
      } yield (r1, r2)
      Await.result(combined, Duration.Inf)
    }

    def &>[T, C](that: Reader[T, Node[A, C]])(implicit
      ec: ExecutionContext
    ): Reader[T, Node[A, (B, C)]] = {
      that.map(thatNode => this &> thatNode)
    }

    /** Observe and tap into node execution flow */
    def tap(g: B => Any): Node[A, B] = Node { a =>
      val result = f(a)
      g(result)
      result
    }

    /** Error handling */
    def onFailure[BB >: B](handler: Throwable => BB): Node[A, BB] =
      Node { a =>
        try {
          f(a)
        } catch {
          case t: Throwable => handler(t)
        }
      }

    /** Add retry capability to any node */
    def withRetry(
      maxAttempts: Int = 3,
      initialDelayMs: Long = 100,
      backoffFactor: Double = 2.0
    ): Node[A, B] = Node { a =>
      def attempt(remaining: Int, delay: Long): B = {
        try {
          f(a)
        } catch {
          case e: Throwable if remaining > 1 =>
            Thread.sleep(delay)
            attempt(remaining - 1, (delay * backoffFactor).toLong)
          case e: Throwable => throw e
        }
      }
      attempt(maxAttempts, initialDelayMs)
    }

    /** Run asynchronously */
    def runAsync(implicit ec: ExecutionContext): A => Future[B] = a => Future(f(a))

    /** Support for flattening nested tuples */
    def zip[BB >: B, Out](implicit
      flattener: Flatten.Aux[BB, Out]
    ): Node[A, Out] =
      Node { a => flattener(f(a)) }
  }

  /** Node companion object with factory methods */
  object Node {
    def apply[A, B](func: A => B): Node[A, B] = new Node[A, B] {
      val f: A => B = func
    }

    def identity[A]: Node[A, A]                   = Node(a => a)
    def unit[B](value: => B): Node[Unit, B]       = Node(_ => value)
    def effect(action: => Unit): Node[Unit, Unit] = Node(_ => action)
    def pure[A, B](b: B): Node[A, B]              = Node(_ => b)
  }

  /** Semantic type aliases for ETL operations */
  type Extract[A, B]   = Node[A, B]
  type Transform[A, B] = Node[A, B]
  type Load[A, B]      = Node[A, B]
  type Pipeline[A, B]  = Node[A, B]

  /** Factory objects for semantic clarity */
  object Pipeline {
    def apply[A, B](func: A => B): Pipeline[A, B] = Node(func)
    def apply[B](value: B): Pipeline[Unit, B]     = Node(_ => value)
    def pure[A]: Pipeline[A, A]                   = Node.identity[A]
  }

  object Extract {
    def apply[A, B](func: A => B): Extract[A, B] = Node(func)
    def apply[B](value: B): Extract[Unit, B]     = Node(_ => value)
    def pure[A]: Extract[A, A]                   = Node.identity[A]
  }

  object Transform {
    def apply[A, B](func: A => B): Transform[A, B] = Node(func)
    def apply[B](value: B): Transform[Unit, B]     = Node(_ => value)
    def pure[A]: Transform[A, A]                   = Node.identity[A]
  }

  object Load {
    def apply[A, B](func: A => B): Load[A, B] = Node(func)
    def apply[B](value: B): Load[Unit, B]     = Node(_ => value)
    def pure[A]: Load[A, A]                   = Node.identity[A]
  }

  /** Type class for environment compatibility between different component
    * requirements
    */
  trait ReaderCompat[T1, T2, R] {
    def toT1(r: R): T1
    def toT2(r: R): T2
  }

  object ReaderCompat extends ReaderCompat2 {

    /** Highest priority: Case 1 - same types */
    implicit def identityCompat[T]: ReaderCompat[T, T, T] =
      new ReaderCompat[T, T, T] {
        def toT1(r: T): T = r
        def toT2(r: T): T = r
      }
  }

  trait ReaderCompat2 extends ReaderCompat1 {

    /** Case 2: T1 is a subtype of T2 */
    implicit def t1SubT2[T1 <: T2, T2]: ReaderCompat[T1, T2, T1] =
      new ReaderCompat[T1, T2, T1] {
        def toT1(r: T1): T1 = r
        def toT2(r: T1): T2 = r /* Since T1 <: T2 */
      }
  }

  trait ReaderCompat1 {

    /** Case 3: T2 is a subtype of T1 */
    implicit def t2SubT1[T1, T2 <: T1]: ReaderCompat[T1, T2, T2] =
      new ReaderCompat[T1, T2, T2] {
        def toT1(r: T2): T1 = r /* Since T2 <: T1 */
        def toT2(r: T2): T2 = r
      }
  }

  /** Reader monad for context handling */
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

  type Context[T, A] = Reader[T, A]

  object Context {
    def apply[T, A](f: T => A): Reader[T, A] = Reader(f)
    def pure[T, A](a: A): Reader[T, A]       = Reader.pure(a)
    def ask[T]: Reader[T, T]                 = Reader.ask
  }

  /**
    * Extension methods to connect Reader wrapped nodes to other Reader nodes
    * with all operators
    */
  implicit class ReaderOps[T1, A, B](val fa: Reader[T1, Node[A, B]]) {

    /**
      *  ~>: Reader(Node) ~> {Reader(Node) | Reader(Node) compat | Node}
      */
    def ~>[C](fb: Reader[T1, Node[B, C]]): Reader[T1, Node[A, C]] = {
      for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA ~> nodeB
    }

    def ~>[T2, C, R](fb: Reader[T2, Node[B, C]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[A, C]] = {
      Reader { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA ~> nodeB
      }
    }

    def ~>[C](node: Node[B, C]): Reader[T1, Node[A, C]] = {
      fa.map(contextNode => contextNode ~> node)
    }

    /**
      *  &: Reader(Node) & {Reader(Node) | Reader(Node) compat | Node}
      */
    def &[C](fb: Reader[T1, Node[A, C]]): Reader[T1, Node[A, (B, C)]] = {
      for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA & nodeB
    }

    def &[T2, C, R](fb: Reader[T2, Node[A, C]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[A, (B, C)]] = {
      Reader { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA & nodeB
      }
    }

    def &[C](node: Node[A, C]): Reader[T1, Node[A, (B, C)]] = {
      fa.map(readerNode => readerNode & node)
    }

    /**
      *  &>: Reader(Node) &> {Reader(Node) | Reader(Node) compat | Node}
      */
    def &>[C](fb: Reader[T1, Node[A, C]])(implicit
      ec: ExecutionContext
    ): Reader[T1, Node[A, (B, C)]] = {
      for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA &> nodeB
    }

    def &>[T2, C, R](fb: Reader[T2, Node[A, C]])(implicit
      compat: ReaderCompat[T1, T2, R],
      ec: ExecutionContext
    ): Reader[R, Node[A, (B, C)]] = {
      Reader { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA &> nodeB
      }
    }

    def &>[C](node: Node[A, C])(implicit
      ec: ExecutionContext
    ): Reader[T1, Node[A, (B, C)]] = {
      fa.map(readerNode => readerNode &> node)
    }

    /**
      *  >>: Reader(Node) >> {Reader(Node) | Reader(Node) compat | Node}
      */
    def >>[C](fb: Reader[T1, Node[Unit, C]]): Reader[T1, Node[A, C]] = {
      for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA >> nodeB
    }

    def >>[T2, C, R](fb: Reader[T2, Node[Unit, C]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[A, C]] = {
      Reader { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA >> nodeB
      }
    }

    def >>[C](node: Node[Unit, C]): Reader[T1, Node[A, C]] = {
      fa.map(readerNode => readerNode >> node)
    }
  }

  /** Utility functions */
  def tap[A](f: A => Any): Node[A, A]           = Node[A, A](a => { f(a); a })
  def effect(action: => Unit): Node[Unit, Unit] = Node(_ => action)

  /** Monoid typeclass for combining values */
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

  /** Writer monad for accumulating logs */
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

  /** Validation result type */
  sealed trait ValidationResult[+A] {
    def isValid: Boolean
    def errors: List[String]
    def get: A

    def &&[B >: A](other: ValidationResult[B]): ValidationResult[B] =
      (this, other) match {
        case (Valid(a), Valid(_))         => Valid(a)
        case (Valid(_), invalid: Invalid) => invalid
        case (invalid: Invalid, Valid(_)) => invalid
        case (Invalid(e1), Invalid(e2))   => Invalid(e1 ++ e2)
      }

    def ||[B >: A](other: ValidationResult[B]): ValidationResult[B] =
      (this, other) match {
        case (Valid(a), _)              => Valid(a)
        case (_, Valid(b))              => Valid(b)
        case (Invalid(e1), Invalid(e2)) => Invalid(e1 ++ e2)
      }

    def map[B](f: A => B): ValidationResult[B] = this match {
      case Valid(a)   => Valid(f(a))
      case i: Invalid => i.asInstanceOf[ValidationResult[B]]
    }

    def flatMap[B](f: A => ValidationResult[B]): ValidationResult[B] =
      this match {
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

  /** Validated typeclass */
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

  /** Validation utility functions */
  def require[T](
    value: T,
    condition: => Boolean,
    message: => String
  ): ValidationResult[T] =
    if (condition) Valid(value) else Invalid(List(message))

  def require(
    condition: => Boolean,
    message: => String
  ): ValidationResult[Unit] =
    if (condition) Valid(()) else Invalid(List(message))

  def success[A](value: A): ValidationResult[A]        = Valid(value)
  def failure[A](message: String): ValidationResult[A] = Invalid(List(message))

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

  /** Contextual types for ETL operations */
  trait Etl4sContext[T] {
    type ExtractWithContext[A, B]   = Context[T, Extract[A, B]]
    type TransformWithContext[A, B] = Context[T, Transform[A, B]]
    type LoadWithContext[A, B]      = Context[T, Load[A, B]]
    type NodeWithContext[A, B]      = Context[T, Node[A, B]]
    type PipelineWithContext[A, B]  = Context[T, Pipeline[A, B]]
  }
}
