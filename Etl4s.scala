/*
 * +==========================================================================+
 * |                                 etl4s                                    |
 * |                     Powerful, whiteboard-style ETL                       |
 * |                            Version 1.4.1                                 |
 * |                 Compatible with Scala 2.12, 2.13, and 3                  |
 * |                                                                          |
 * | Copyright 2025 Matthieu Court (matthieu.court@protonmail.com)            |
 * | Apache License 2.0                                                       |
 * +==========================================================================+
 */

/**
 * A lightweight, powerful library for writing dataflows
 * using the core [[Node]] and [[Reader]] abstractions.
 * 
 * It enables config-driven, whiteboard-style pipeline composition
 * with functional programming principles.
 *
 * @author Matthieu Court
 * @version 1.4.1
 */
package object etl4s {
  import scala.language.{higherKinds, implicitConversions}
  import scala.concurrent.{Future, ExecutionContext}
  import scala.concurrent.duration._
  import scala.concurrent.Await
  import scala.util.Try

  /**
   * The core abstraction of etl4s: a composable wrapper around a function `A => B`.
   *
   * Node represents a single step in an ETL pipeline and provides a rich set of
   * combinators for composition, error handling, and parallel execution.
   *
   * @tparam A the input type
   * @tparam B the output type
   */
  trait Node[A, B] {

    /**
     * The core function wrapped by this node.
     * This is the actual computation that transforms input `A` to output `B`.
     */
    val f: A => B

    /**
     * Optional metadata that can be attached to a Node at compile time.
     * Useful for debugging, documentation, or runtime introspection.
     *
     * @example
     * {{{
     * val node = Node[String, Int](_.length)
     *   .withMetadata("String length calculator")
     * }}}
     */
    val metadata: Any = None

    /**
     * Applies the node's function to the input.
     *
     * @param a the input value
     * @return the transformed output
     */
    def apply(a: A): B = f(a)

    /**
     * Runs the node without any error handling.
     *
     * @param a the input value
     * @return the transformed output
     * @throws any exception thrown by the underlying function
     */
    def unsafeRun(a: A): B = f(a)

    /**
     * Runs the node with error handling, wrapping the result in a Try.
     *
     * @param a the input value
     * @return Success(result) or Failure(exception)
     */
    def safeRun(a: A): Try[B] = Try(f(a))

    /**
     * Runs the node and collects insights about the execution.
     *
     * @param a the input value
     * @return ExecutionInsights containing result and collected information
     */
    def unsafeRunTraced(a: A): ExecutionInsights[B] = {
      val logCollector = new ThreadLocal[List[Any]] {
        override def initialValue(): List[Any] = List.empty
      }
      val validationCollector = new ThreadLocal[List[Any]] {
        override def initialValue(): List[Any] = List.empty
      }

      val startTime = System.currentTimeMillis()
      Runtime.setCollectors(logCollector, validationCollector, startTime)

      try {
        val result   = f(a)
        val endTime  = System.currentTimeMillis()
        val duration = endTime - startTime

        ExecutionInsights(
          result = result,
          logs = logCollector.get().reverse,
          timing = Some(duration),
          validationErrors = validationCollector.get().reverse
        )
      } finally {
        Runtime.clearCollectors()
        logCollector.remove()
        validationCollector.remove()
      }
    }

    /**
     * Makes this node depend on some configuration type `T`.
     *
     * This method transforms a regular Node into a Reader-wrapped Node,
     * enabling dependency injection patterns.
     *
     * @tparam T the configuration/context type
     * @param f a function that takes config and returns the node function
     * @return a Reader that produces a Node when given configuration
     *
     * @example
     * {{{
     * case class Config(multiplier: Int)
     * 
     * val configNode = someNode.requires[Config] { config => input =>
     *   input * config.multiplier
     * }
     * 
     * val result = configNode.provide(Config(5)).unsafeRun(10) // 50
     * }}}
     */
    def requires[T](f: T => A => B): Reader[T, Node[A, B]] = {
      Reader { config =>
        Node { a =>
          f(config)(a)
        }
      }
    }

    /**
     * Attaches custom metadata to this node.
     *
     * @param meta the metadata to attach (can be any type)
     * @return a new Node with the attached metadata
     *
     * @example
     * {{{
     * val documented = node.withMetadata("Processes user data")
     * val versioned = node.withMetadata(("v1.2", "Critical path"))
     * }}}
     */
    def withMetadata(meta: Any): Node[A, B] = {
      val currentF = this.f
      new Node[A, B] {
        val f: A => B              = currentF
        override val metadata: Any = meta
      }
    }

    /**
     * Functorial mapping: transforms the output of this node.
     *
     * @tparam C the new output type
     * @param g the transformation function
     * @return a new Node that applies g to the result of this node
     *
     * @example
     * {{{
     * val lengthNode = Node[String, Int](_.length)
     * val doubledNode = lengthNode.map(_ * 2)
     * doubledNode("hello") // returns 10
     * }}}
     */
    def map[C](g: B => C): Node[A, C] = Node(a => g(f(a)))

    /**
     * Monadic binding: allows dynamic node selection based on intermediate results.
     *
     * @tparam C the final output type
     * @param g a function that takes the result of this node and returns a new Node
     * @return a new Node that chains the computation
     *
     * @example
     * {{{
     * val get = Node[String, Int](_.toInt)
     * val process = get.flatMap(n => Node[String, String](_ => "~" * n))
     * process("5") // returns "~~~~~"
     * }}}
     */
    def flatMap[C](g: B => Node[A, C]): Node[A, C] = Node { a =>
      val b = f(a)
      g(b)(a)
    }

    /**
     * Sequential composition: chains two nodes together.
     * 
     * The output type of this node must match the input type of the next node.
     *
     * @tparam C the output type of the next node
     * @param next the node to execute after this one
     * @return a new Node representing the composed computation
     *
     * @example
     * {{{
     * val extract = Node[String, Int](_.length)
     * val transform = Node[Int, String](i => s"Length: $i")
     * val pipeline = extract ~> transform
     * pipeline("hello") // returns "Length: 5"
     * }}}
     */
    def ~>[C](next: Node[B, C]): Node[A, C] = Node(a => next(f(a)))

    /**
     * Alias for `~>` with more explicit naming.
     */
    def andThen[C](next: Node[B, C]): Node[A, C] = Node(a => next(f(a)))

    /**
     * Sequential composition with a Reader-wrapped node.
     *
     * @tparam T the configuration type required by the next node
     * @tparam C the output type of the next node
     * @param next a Reader-wrapped node
     * @return a Reader that produces the composed Node
     */
    def ~>[T, C](next: Reader[T, Node[B, C]]): Reader[T, Node[A, C]] = {
      next.map(nextNode => this ~> nextNode)
    }

    /**
     * Side effect composition: runs this node, ignores result, then runs next with Unit input.
     *
     * Useful for logging, cleanup, or other side effects that don't affect the main data flow.
     *
     * @tparam C the output type of the next node
     * @param next a node that takes Unit as input
     * @return a new Node that performs the side effect after the main computation
     *
     * @example
     * {{{
     * val processData = Node[String, Int](_.length)
     * val logSuccess = Node[Unit, Unit](_ => println("Processing complete"))
     * val withLogging = processData >> logSuccess
     * }}}
     */
    def >>[C](next: Node[Unit, C]): Node[A, C] = Node { a =>
      f(a)
      next(()) /* Execute the next node with unit input and return its result */
    }

    /**
     * Side effect composition with a Reader-wrapped node.
     */
    def >>[T, C](next: Reader[T, Node[Unit, C]]): Reader[T, Node[A, C]] = {
      next.map(nextNode => this >> nextNode)
    }

    /**
     * Parallel composition: runs both nodes with the same input, combines results into a tuple.
     *
     * Both nodes execute sequentially but with the same input value.
     *
     * @tparam C the output type of the other node
     * @param that the other node to run in parallel
     * @return a new Node that returns a tuple of both results
     *
     * @example
     * {{{
     * val getName = Node[Person, String](_.name)
     * val getAge = Node[Person, Int](_.age)
     * val getBoth = getName & getAge  // returns (String, Int)
     * }}}
     */
    def &[C](that: Node[A, C]): Node[A, (B, C)] = Node { a =>
      (f(a), that(a))
    }

    /**
     * Parallel composition with a Reader-wrapped node.
     */
    def &[T, C](that: Reader[T, Node[A, C]]): Reader[T, Node[A, (B, C)]] = {
      that.map(thatNode => this & thatNode)
    }

    /**
     * Concurrent parallel composition: runs both nodes concurrently with the same input.
     *
     * Unlike `&`, this version uses Future to execute both nodes simultaneously,
     * potentially improving performance for I/O bound operations.
     *
     * @tparam C the output type of the other node
     * @param that the other node to run concurrently
     * @param ec implicit ExecutionContext for Future execution
     * @return a new Node that returns a tuple of both results
     *
     * @example
     * {{{
     * implicit val ec = ExecutionContext.global
     * val fetchUser = Node[UserId, User](id => fetchFromDB(id))
     * val fetchPrefs = Node[UserId, Preferences](id => fetchPrefsFromCache(id))
     * val fetchBoth = fetchUser &> fetchPrefs  // concurrent execution
     * }}}
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

    /**
     * Concurrent parallel composition with a Reader-wrapped node.
     */
    def &>[T, C](that: Reader[T, Node[A, C]])(implicit
      ec: ExecutionContext
    ): Reader[T, Node[A, (B, C)]] = {
      that.map(thatNode => this &> thatNode)
    }

    /**
     * Tap operation: peek at the result without changing it.
     *
     * Useful for debugging, logging, or side effects that don't modify the data flow.
     *
     * @param g a function to execute with the result (return value is ignored)
     * @return a new Node that passes through the original result unchanged
     *
     * @example
     * {{{
     * val pipeline = extractData
     *   .tap(data => logger.info(s"Extracted ${data.size} records"))
     *   .map(transform)
     *   .tap(result => println(s"Transformation complete: $result"))
     * }}}
     */
    def tap(g: B => Any): Node[A, B] = Node { a =>
      val result = f(a)
      g(result)
      result
    }

    /**
     * Attaches a log message to this node.
     *
     * @param message static log message (any type - will be converted to string)
     * @return a new Node that logs the message during execution
     */
    def withLog[T](message: T): Node[A, B] = Node { a =>
      Runtime.log(message)
      f(a)
    }

    /**
     * Attaches a dynamic log message based on input and output.
     *
     * @param messageFunc function that generates log message from input and output
     * @return a new Node that logs the generated message during execution
     *
     * @example
     * {{{
     * val transform = Transform[String, Int](_.length)
     *   .withLog((input, output) => s"Transformed '$input' -> length $output")
     *   .withLog((input, output) => output)  // Log the actual output value
     * 
     * // If you only need input or output, just ignore the other parameter:
     * .withLog((input, _) => s"Processing: $input")
     * .withLog((_, output) => s"Result: $output")
     * 
     * // Static messages (any type):
     * .withLog("Starting transform")
     * .withLog(42)
     * .withLog(User("John", 30))
     * }}}
     */
    def withLog[T](messageFunc: (A, B) => T): Node[A, B] = Node { a =>
      val result = f(a)
      Runtime.log(messageFunc(a, result))
      result
    }

    /**
     * Attaches a validation that checks input and output.
     *
     * @param validationFunc function that takes (input, output) and returns (condition, message)
     * @return a new Node that validates during execution
     *
     * @example
     * {{{
     * val transform = Transform[String, Int](_.length)
     *   .validate((input, output) => (
     *     output == input.length, 
     *     s"Length mismatch: expected ${input.length}, got $output"
     *   ))
     *   .validate((_, output) => (output > 0, "Length must be positive"))
     * }}}
     */
    def validate[E](validationFunc: (A, B) => (Boolean, E)): Node[A, B] = Node { a =>
      val result             = f(a)
      val (condition, error) = validationFunc(a, result)
      if (!condition) {
        Runtime.logValidation(error)
      }
      result
    }

    /**
     * Error handling: provides a fallback value when this node fails.
     *
     * @tparam BB a supertype of B (to allow for fallback values of compatible types)
     * @param handler function that converts exceptions to fallback values
     * @return a new Node that never throws exceptions
     *
     * @example
     * {{{
     * val parseNumber = Node[String, Int](_.toInt)
     *   .onFailure(_ => 0)  // return 0 for invalid strings
     * 
     * parseNumber("123")  // returns 123
     * parseNumber("abc")  // returns 0
     * }}}
     */
    def onFailure[BB >: B](handler: Throwable => BB): Node[A, BB] =
      Node { a =>
        try {
          f(a)
        } catch {
          case t: Throwable => handler(t)
        }
      }

    /**
     * Adds retry capability to any node.
     *
     * Automatically retries failed operations with exponential backoff.
     *
     * @param maxAttempts maximum number of attempts (default: 3)
     * @param initialDelayMs initial delay between retries in milliseconds (default: 100)
     * @param backoffFactor multiplier for delay between attempts (default: 2.0)
     * @return a new Node with retry behavior
     *
     * @example
     * {{{
     * val unreliableService = Node[Request, Response](callExternalAPI)
     *   .withRetry(maxAttempts = 5, initialDelayMs = 200, backoffFactor = 1.5)
     * }}}
     */
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

    /**
     * Creates an asynchronous version of this node.
     *
     * @param ec implicit ExecutionContext for Future execution
     * @return a function that returns Future[B] instead of B
     *
     * @example
     * {{{
     * implicit val ec = ExecutionContext.global
     * val asyncProcessor = heavyComputation.runAsync
     * val futureResult: Future[Result] = asyncProcessor(input)
     * }}}
     */
    def runAsync(implicit ec: ExecutionContext): A => Future[B] = a => Future(f(a))

    /**
     * Flattens nested tuple results.
     *
     * When combining multiple nodes with `&`, you can end up with nested tuples
     * like `((A, B), C)`. This method flattens them to `(A, B, C)`.
     *
     * @tparam BB a supertype of B
     * @tparam Out the flattened output type
     * @param flattener implicit evidence for how to flatten the type
     * @return a new Node with flattened output
     *
     * @example
     * {{{
     * val node1 = Node[String, Int](_.length)
     * val node2 = Node[String, String](_.toUpperCase)
     * val node3 = Node[String, Boolean](_.nonEmpty)
     * 
     * val combined = (node1 & node2) & node3  // Node[String, ((Int, String), Boolean)]
     * val flattened = combined.zip  // Node[String, (Int, String, Boolean)]
     * }}}
     */
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
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = {
      Reader { config =>
        Node { a =>
          f(config)(a)
        }
      }
    }
  }

  /** Semantic type aliases for ETL operations */
  type Extract[A, B]   = Node[A, B]
  type Transform[A, B] = Node[A, B]
  type Load[A, B]      = Node[A, B]
  type Pipeline[A, B]  = Node[A, B]

  /** Factory objects for semantic clarity */
  object Pipeline {
    def apply[A, B](func: A => B): Pipeline[A, B]                = Node(func)
    def apply[B](value: B): Pipeline[Unit, B]                    = Node(_ => value)
    def pure[A]: Pipeline[A, A]                                  = Node.identity[A]
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Extract {
    def apply[A, B](func: A => B): Extract[A, B]                 = Node(func)
    def apply[B](value: B): Extract[Unit, B]                     = Node(_ => value)
    def pure[A]: Extract[A, A]                                   = Node.identity[A]
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Transform {
    def apply[A, B](func: A => B): Transform[A, B]               = Node(func)
    def apply[B](value: B): Transform[Unit, B]                   = Node(_ => value)
    def pure[A]: Transform[A, A]                                 = Node.identity[A]
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Load {
    def apply[A, B](func: A => B): Load[A, B]                    = Node(func)
    def apply[B](value: B): Load[Unit, B]                        = Node(_ => value)
    def pure[A]: Load[A, A]                                      = Node.identity[A]
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  /**
   * Extension methods for Node factory methods.
   * 
   * This allows the pattern: `Transform[Int, Int].requires[Config] { ... }`
   */
  implicit class NodeFactoryRequiresOps[A, B](val factory: (A => B) => Node[A, B]) {
    def requires[T](f: T => A => B): Reader[T, Node[A, B]] = {
      Reader { config =>
        factory { a =>
          f(config)(a)
        }
      }
    }
  }

  /**
   * Type class for environment compatibility between different component requirements.
   *
   * This enables composition of Reader-wrapped nodes that require different but compatible
   * configuration types. The type class provides evidence of how to extract the required
   * configuration from a common environment type.
   *
   * @tparam T1 the first configuration type
   * @tparam T2 the second configuration type  
   * @tparam R the common environment type that can provide both T1 and T2
   */
  trait ReaderCompat[T1, T2, R] {
    def toT1(r: R): T1
    def toT2(r: R): T2
  }

  /**
   * Companion object providing implicit instances for ReaderCompat.
   * 
   * The priority hierarchy ensures the most specific instances are selected first.
   */
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

  /**
   * The Reader monad for dependency injection and context management.
   *
   * Reader represents a computation that depends on some shared environment or configuration.
   * It's essentially a wrapper around a function `R => A` where `R` is the environment type
   * and `A` is the result type.
   *
   * @example
   * {{{
   * case class DatabaseConfig(url: String, timeout: Int)
   * 
   * val dbNode = Reader { config: DatabaseConfig =>
   *   Node { data => saveToDatabase(config.url, data) }
   * }
   * 
   * // Later, provide the configuration:
   * val result = dbNode.provide(DatabaseConfig("localhost", 5000))
   *                   .unsafeRun(myData)
   * }}}
   *
   * @tparam R the environment/configuration type
   * @tparam A the result type
   * @param run the function that computes A given environment R
   */
  case class Reader[R, A](run: R => A) {
    def map[B](f: A => B): Reader[R, B] = Reader(r => f(run(r)))
    def flatMap[B](f: A => Reader[R, B]): Reader[R, B] =
      Reader(r => f(run(r)).run(r))
    def provideContext(ctx: R): A = run(ctx)
    def provide(ctx: R): A        = run(ctx)
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
   * Extension methods for composing Reader-wrapped Nodes.
   *
   * These methods enable natural composition of context-dependent operations
   * while handling environment compatibility automatically.
   */
  implicit class ReaderOps[T1, A, B](val fa: Reader[T1, Node[A, B]]) {

    /**
      * ~>: Reader(Node) ~> {Reader(Node) | Reader(Node) compat | Node}
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

    /**
     * Tap operation for Reader-wrapped nodes with access to context.
     *
     * Allows peeking at both the context and result of a context-dependent node.
     *
     * @param g a curried function that receives context then result for side effects
     * @return a Reader that produces a Node with context-aware tap behavior
     *
     * @example
     * {{{
     * val contextExtract = Etl4sContext.Extract[Config, String, Int] { config => input =>
     *   process(input, config)
     * }
     * 
     * val withTap = contextExtract.tap(config => result => 
     *   println(s"[${config.serviceName}] Extracted: $result")
     * )
     * }}}
     */
    def tap(g: T1 => B => Any): Reader[T1, Node[A, B]] = {
      Reader { ctx =>
        fa.run(ctx).tap(result => g(ctx)(result))
      }
    }
  }

  /**
   * Comprehensive insights collected during pipeline execution.
   *
   * @tparam A the result type
   * @param result the actual computation result
   * @param logs collected log values from withLog calls (any type)
   * @param timing execution duration in milliseconds (if collected)
   * @param validationErrors validation errors encountered (any type)
   */
  case class ExecutionInsights[A](
    result: A,
    logs: List[Any] = List.empty,
    timing: Option[Long] = None,
    validationErrors: List[Any] = List.empty
  ) {

    /** Convenience method to check if any validation errors occurred */
    def hasValidationErrors: Boolean = validationErrors.nonEmpty

    /** Convenience method to check if any issues (validation errors) occurred */
    def hasIssues: Boolean = hasValidationErrors

    /** Get timing in a more readable format */
    def timingMillis: Option[Long]    = timing
    def timingSeconds: Option[Double] = timing.map(_ / 1000.0)

    /** Get logs as strings (for display/compatibility) */
    def logsAsStrings: List[String] = logs.map(_.toString)

    /** Get validation errors as strings (for display/compatibility) */
    def validationErrorsAsStrings: List[String] = validationErrors.map(_.toString)

    /** Get all messages (logs + validation errors) as strings */
    def allMessagesAsStrings: List[String] =
      logsAsStrings ++ validationErrorsAsStrings.map(e => s"[VALIDATION] $e")
  }

  /** Utility functions */
  def tap[A](f: A => Any): Node[A, A] = Node[A, A](a => { f(a); a })

  /**
   * Access to current pipeline execution state.
   * 
   * Provides unified access to the currently executing pipeline's runtime state,
   * including logs, validation errors, and execution timing.
   */
  object Runtime {
    private val logCollector: ThreadLocal[Option[ThreadLocal[List[Any]]]] =
      new ThreadLocal[Option[ThreadLocal[List[Any]]]] {
        override def initialValue(): Option[ThreadLocal[List[Any]]] = None
      }

    private val validationCollector: ThreadLocal[Option[ThreadLocal[List[Any]]]] =
      new ThreadLocal[Option[ThreadLocal[List[Any]]]] {
        override def initialValue(): Option[ThreadLocal[List[Any]]] = None
      }

    private val startTimeCollector: ThreadLocal[Option[Long]] =
      new ThreadLocal[Option[Long]] {
        override def initialValue(): Option[Long] = None
      }

    def setCollectors(
      logs: ThreadLocal[List[Any]],
      validations: ThreadLocal[List[Any]],
      startTime: Long
    ): Unit = {
      logCollector.set(Some(logs))
      validationCollector.set(Some(validations))
      startTimeCollector.set(Some(startTime))
    }

    def clearCollectors(): Unit = {
      logCollector.set(None)
      validationCollector.set(None)
      startTimeCollector.set(None)
    }

    /**
     * Get the current execution insights as they're being built.
     * 
     * Returns a live view of the execution state including logs, validation errors,
     * and current execution time.
     */
    def current: ExecutionInsights[Any] = {
      val logs = logCollector.get() match {
        case Some(collector) => collector.get().reverse
        case None            => List.empty
      }

      val validationErrors = validationCollector.get() match {
        case Some(collector) => collector.get().reverse
        case None            => List.empty
      }

      val timing = startTimeCollector.get() match {
        case Some(startTime) => Some(System.currentTimeMillis() - startTime)
        case None            => None
      }

      ExecutionInsights(
        result = (), // Result not available during execution
        logs = logs,
        timing = timing,
        validationErrors = validationErrors
      )
    }

    /** Add a log value to the current execution (any type) */
    def log[T](message: T): Unit = {
      logCollector.get() match {
        case Some(collector) =>
          val currentLogs = collector.get()
          collector.set(message :: currentLogs)
        case None =>
        // No collector set, log is ignored
      }
    }

    /** Add a validation error to the current execution (any type) */
    def logValidation[T](error: T): Unit = {
      validationCollector.get() match {
        case Some(collector) =>
          val currentValidations = collector.get()
          collector.set(error :: currentValidations)
        case None =>
        // No collector set, validation error is ignored
      }
    }

    /** Check if there are any validation errors so far */
    def hasValidationErrors: Boolean = current.hasValidationErrors

    /** Get current validation errors (any type) */
    def validationErrors: List[Any] = current.validationErrors

    /** Get current logs (any type) */
    def logs: List[Any] = current.logs

    /** Get current execution time in milliseconds */
    def executionTimeMillis: Option[Long] = current.timing

    /** Get current execution time in seconds */
    def executionTimeSeconds: Option[Double] = current.timingSeconds
  }

  /**
   * Type class for flattening nested tuple structures.
   *
   * This helps transform nested tuples like `((a,b),c)` into flat tuples like `(a,b,c)`.
   * Makes pipelines that combine multiple steps more ergonomic.
   *
   * Note: Implementation is limited to avoid shapeless dependency and maintain
   * cross-compilation with Scala 2.12. Nesting is supported up to about 7-8 levels.
   *
   * @tparam A the input type to flatten
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

  /**
   * Base trait for creating context-aware ETL operations.
   *
   * When you need configuration or context for your ETL operations, extend this trait
   * with your config type. It provides convenient methods to build context-aware
   * operations using the Reader monad.
   *
   * @example
   * {{{
   * case class MyConfig(dbUrl: String, timeout: Int)
   * 
   * object MyETL extends Etl4sContext[MyConfig] {
   *   val saveUser = Etl4sContext.Load[User, Unit] { config => user =>
   *     // use config.dbUrl, config.timeout
   *     saveToDatabase(config, user)
   *   }
   * 
   *   val pipeline = extractUsers ~> transformUsers ~> saveUser
   * 
   *   // Later, provide config and run:
   *   pipeline.provide(MyConfig("localhost", 5000)).unsafeRun(inputData)
   * }
   * }}}
   *
   * @tparam T the configuration/context type
   */
  trait Etl4sContext[T] {
    type ExtractWithContext[A, B]   = Context[T, Extract[A, B]]
    type TransformWithContext[A, B] = Context[T, Transform[A, B]]
    type LoadWithContext[A, B]      = Context[T, Load[A, B]]
    type NodeWithContext[A, B]      = Context[T, Node[A, B]]
    type PipelineWithContext[A, B]  = Context[T, Pipeline[A, B]]

    def extractWithContext[A, B](f: T => A => B): ExtractWithContext[A, B] =
      Extract.requires[T, A, B](f)
    def transformWithContext[A, B](f: T => A => B): TransformWithContext[A, B] =
      Transform.requires[T, A, B](f)
    def loadWithContext[A, B](f: T => A => B): LoadWithContext[A, B] = Load.requires[T, A, B](f)
    def nodeWithContext[A, B](f: T => A => B): NodeWithContext[A, B] = Node.requires[T, A, B](f)
    def pipelineWithContext[A, B](f: T => A => B): PipelineWithContext[A, B] =
      Pipeline.requires[T, A, B](f)

    /**
     * Object providing more natural access to context-wrapped operations.
     *
     * Instead of calling `extractWithContext`, you can use the more natural:
     * `Etl4sContext.Extract[A, B] { ctx => in => out }`
     */
    object Etl4sContext {
      def Extract[A, B](f: T => A => B): Context[T, Extract[A, B]] =
        etl4s.Extract.requires[T, A, B](f)

      def Transform[A, B](f: T => A => B): Context[T, Transform[A, B]] =
        etl4s.Transform.requires[T, A, B](f)

      def Load[A, B](f: T => A => B): Context[T, Load[A, B]] =
        etl4s.Load.requires[T, A, B](f)

      def Pipeline[A, B](f: T => A => B): Context[T, Pipeline[A, B]] =
        etl4s.Pipeline.requires[T, A, B](f)

      def Tap[A](f: T => A => Any): Context[T, Node[A, A]] =
        Context { ctx =>
          Node { a =>
            f(ctx)(a)
            a
          }
        }
    }
  }

}
