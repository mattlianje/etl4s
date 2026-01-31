/*
 * +==========================================================================+
 * |                                 etl4s                                    |
 * |                     Powerful, whiteboard-style ETL                       |
 * |                            Version 1.8.0                                 |
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
 */
package object etl4s {
  import scala.language.{higherKinds, implicitConversions}
  import scala.concurrent.{Future, ExecutionContext}
  import scala.concurrent.duration._
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

    /** Sets up trace collectors, executes block, cleans up. Supports nesting. */
    private def withTraceSetup[T](
      block: Long => T
    ): T = {
      val parentState = Trace.saveCollector()
      val startTime   = System.currentTimeMillis()
      Trace.setCollector(startTime)

      try {
        block(startTime)
      } finally {
        Trace.restoreCollector(parentState)
      }
    }

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
     * Optional lineage information for data pipeline visualization.
     * Documents inputs, outputs, scheduling, and organization.
     */
    def getLineage: Option[Lineage] = None

    /**
     * Applies the node's function to the input.
     *
     * @param a the input value
     * @return the transformed output
     */
    def apply(a: A): B = f(a)

    /**
     * Runs the node without any input (for Node[Any, B]).
     * Only available when the node accepts Any as input.
     */
    def unsafeRun()(implicit ev: A =:= Any): B =
      unsafeRun(null.asInstanceOf[A])(Etl4sNoOpTelemetry)

    /**
     * Runs the node without any error handling.
     * Trace information is collected internally but only accessible via unsafeRunTraced.
     *
     * @param a the input value
     * @param otelProvider optional OTel provider for observability (defaults to Etl4sNoOpTelemetry)
     * @return the transformed output
     * @throws any exception thrown by the underlying function
     */
    def unsafeRun(a: A)(implicit otelProvider: Etl4sTelemetry = Etl4sNoOpTelemetry): B =
      withOtelSetup(otelProvider) {
        withTraceSetup { _ =>
          f(a)
        }
      }

    /**
     * Runs the node with error handling, wrapping the result in a Try.
     * Trace information is collected internally but only accessible via safeRunTraced.
     *
     * @param a the input value
     * @param otelProvider optional OTel provider for observability (defaults to Etl4sNoOpTelemetry)
     * @return Success(result) or Failure(exception)
     */
    def safeRun(a: A)(implicit otelProvider: Etl4sTelemetry = Etl4sNoOpTelemetry): Try[B] =
      withOtelSetup(otelProvider) {
        withTraceSetup { _ =>
          Try(f(a))
        }
      }

    /**
     * Runs the node with error handling without any input (for Node[Any, B]).
     */
    def safeRun()(implicit ev: A =:= Any): Try[B] =
      safeRun(null.asInstanceOf[A])(Etl4sNoOpTelemetry)

    /**
     * Runs the node and collects insights about the execution.
     *
     * @param a the input value
     * @param otelProvider optional OTel provider for observability (defaults to Etl4sNoOpTelemetry)
     * @return Trace containing result and collected information
     */
    def unsafeRunTrace(a: A)(implicit otelProvider: Etl4sTelemetry = Etl4sNoOpTelemetry): Trace[B] =
      withOtelSetup(otelProvider) {
        withTraceSetup { startTime =>
          val result       = f(a)
          val endTime      = System.currentTimeMillis()
          val duration     = endTime - startTime
          val currentTrace = Trace.current

          Trace(
            result = result,
            logs = currentTrace.logs,
            timeElapsedMillis = duration,
            errors = currentTrace.errors
          )
        }
      }

    /**
     * Runs the node safely and collects insights about the execution.
     *
     * @param a the input value
     * @param otelProvider optional OTel provider for observability (defaults to Etl4sNoOpTelemetry)
     * @return Trace with Try[B] as result
     */
    def safeRunTrace(
      a: A
    )(implicit otelProvider: Etl4sTelemetry = Etl4sNoOpTelemetry): Trace[Try[B]] =
      withOtelSetup(otelProvider) {
        withTraceSetup { startTime =>
          val result       = Try(f(a))
          val endTime      = System.currentTimeMillis()
          val duration     = endTime - startTime
          val currentTrace = Trace.current

          Trace(
            result = result,
            logs = currentTrace.logs,
            timeElapsedMillis = duration,
            errors = currentTrace.errors
          )
        }
      }

    /** Sets up OTel provider, executes block, cleans up. */
    private def withOtelSetup[T](otelProvider: Etl4sTelemetry)(block: => T): T = {
      if (otelProvider != Etl4sNoOpTelemetry) {
        Tel.setProvider(otelProvider)
      }
      try {
        block
      } finally {
        if (otelProvider != Etl4sNoOpTelemetry) {
          Tel.clearProvider()
        }
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
      val currentF       = this.f
      val currentLineage = this.getLineage
      new Node[A, B] {
        val f: A => B                            = currentF
        override val metadata: Any               = meta
        override def getLineage: Option[Lineage] = currentLineage
      }
    }

    /**
     * Attaches lineage information to this node.
     *
     * @param lin the lineage to attach
     * @return a new Node with the attached lineage
     */
    def withLineage(lin: Lineage): Node[A, B] = {
      val currentF        = this.f
      val currentMetadata = this.metadata
      new Node[A, B] {
        val f: A => B                            = currentF
        override val metadata: Any               = currentMetadata
        override def getLineage: Option[Lineage] = Some(lin)
      }
    }

    /**
     * Sets or updates the lineage name.
     */
    def lineageName(name: String): Node[A, B] = {
      val updated = this.getLineage match {
        case Some(lin) => lin.copy(name = name)
        case None      => Lineage(name = name)
      }
      this.withLineage(updated)
    }

    /**
     * Sets or updates the lineage inputs.
     */
    def lineageInputs(inputs: String*): Node[A, B] = {
      val updated = this.getLineage match {
        case Some(lin) => lin.copy(inputs = inputs.toList)
        case None      => Lineage(name = "", inputs = inputs.toList)
      }
      this.withLineage(updated)
    }

    /**
     * Sets or updates the lineage outputs.
     */
    def lineageOutputs(outputs: String*): Node[A, B] = {
      val updated = this.getLineage match {
        case Some(lin) => lin.copy(outputs = outputs.toList)
        case None      => Lineage(name = "", outputs = outputs.toList)
      }
      this.withLineage(updated)
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
    def ~>[C](next: Node[B, C]): Node[A, C] = {
      val combined = (this.getLineage, next.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.chain(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Node[A, C](a => next(f(a)))
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Alias for `~>` with more explicit naming.
     */
    def andThen[C](next: Node[B, C]): Node[A, C] = this ~> next

    /**
     * Sequential composition with a Reader-wrapped node.
     *
     * @tparam T the configuration type required by the next node
     * @tparam C the output type of the next node
     * @param next a Reader-wrapped node
     * @return a Reader that produces the composed Node
     */
    def ~>[T, C](next: Reader[T, Node[B, C]]): Reader[T, Node[A, C]] = {
      val combined = (this.getLineage, next.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.chain(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = next.map(nextNode => this ~> nextNode)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Sequential side-effect composition: runs this node, then runs next with the same input.
     *
     * Executes this node for its side effects, then passes the original input to the next node.
     * This is useful for chaining side effects that all need access to the same input value.
     *
     * @tparam C the output type of the next node
     * @param next a node that takes the same input type as this node
     * @return a new Node that executes both nodes with the same input and returns the second result
     *
     * @example
     * {{{
     * val storeToS3 = Node[Int, Unit](n => println(s"Stored $n to S3"))
     * val storeToDb = Node[Int, Unit](n => println(s"Stored $n to DB"))
     * val storeBoth = storeToS3 >> storeToDb  // Both receive the same Int
     * }}}
     */
    def >>[C](next: Node[A, C]): Node[A, C] = {
      val combined = (this.getLineage, next.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Node[A, C] { a =>
        f(a)
        next(a)
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Side effect composition with a Reader-wrapped node.
     */
    def >>[T, C](next: Reader[T, Node[A, C]]): Reader[T, Node[A, C]] = {
      val combined = (this.getLineage, next.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = next.map(nextNode => this >> nextNode)
      combined.fold(result)(lin => result.withLineage(lin))
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
     * val getAll = getName & getAge & getEmail  // returns (String, Int, String) - auto-flattened!
     * }}}
     */
    def &[C](that: Node[A, C])(implicit ta: TupleAppend[B, C]): Node[A, ta.Out] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Node[A, ta.Out] { a =>
        ta.append(f(a), that(a))
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Parallel composition with a Reader-wrapped node.
     */
    def &[T, C](
      that: Reader[T, Node[A, C]]
    )(implicit ta: TupleAppend[B, C]): Reader[T, Node[A, ta.Out]] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = that.map(thatNode => this & thatNode)
      combined.fold(result)(lin => result.withLineage(lin))
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
     * val fetchAll = fetchUser &> fetchPrefs &> fetchSettings  // auto-flattened!
     * }}}
     */
    def &>[C](that: Node[A, C])(implicit
      ec: ExecutionContext,
      ta: TupleAppend[B, C]
    ): Node[A, ta.Out] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Node[A, ta.Out] { a =>
        val (r1, r2) = Platform.runParallel(f(a), that(a))
        ta.append(r1, r2)
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Concurrent parallel composition with a Reader-wrapped node.
     */
    def &>[T, C](that: Reader[T, Node[A, C]])(implicit
      ec: ExecutionContext,
      ta: TupleAppend[B, C]
    ): Reader[T, Node[A, ta.Out]] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = that.map(thatNode => this &> thatNode)
      combined.fold(result)(lin => result.withLineage(lin))
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
            Platform.sleep(delay)
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

    /**
     * Creates a node from a function A => B.
     */
    def apply[A, B](func: A => B): Node[A, B] = new Node[A, B] {
      val f: A => B = func
    }

    /**
     * Creates a lazy node that evaluates the value when run (not at construction time).
     * Accepts any input type for maximum composability.
     *
     * This is the most flexible constructor - use it for side effects, I/O, or any
     * computation that should be deferred until execution.
     *
     * @param value the by-name parameter to evaluate when the node runs
     * @return a Node[Any, B] that evaluates value on each run
     *
     * @example
     * {{{
     * val getUserInput = Node {
     *   println("Enter your name:")
     *   scala.io.StdIn.readLine()
     * }
     * // Nothing prints until you call getUserInput.unsafeRun(...)
     * }}}
     */
    def apply[B](value: => B): Node[Any, B] = Node((_: Any) => value)

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
    def apply[B](value: => B): Pipeline[Any, B]                  = Node(value)
    def pure[A]: Pipeline[A, A]                                  = Node.identity[A]
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Extract {
    def apply[A, B](func: A => B): Extract[A, B]                 = Node(func)
    def apply[B](value: => B): Extract[Any, B]                   = Node(value)
    def pure[A]: Extract[A, A]                                   = Node.identity[A]
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Transform {
    def apply[A, B](func: A => B): Transform[A, B]               = Node(func)
    def apply[B](value: => B): Transform[Any, B]                 = Node(value)
    def pure[A]: Transform[A, A]                                 = Node.identity[A]
    def requires[T, A, B](f: T => A => B): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Load {
    def apply[A, B](func: A => B): Load[A, B]                    = Node(func)
    def apply[B](value: => B): Load[Any, B]                      = Node(value)
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

  // Lineage case class is defined in shared src/Lineage.scala

  /**
   * Type class for types that can carry metadata.
   */
  trait HasMetadata[F[_]] {
    def metadata[A](fa: F[A]): Any
    def withMetadata[A](fa: F[A], meta: Any): F[A]
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
   * @param metadata optional metadata that can be attached at compile time
   */
  case class Reader[R, A](run: R => A, metadata: Any = None, getLineage: Option[Lineage] = None) {
    def map[B](f: A => B): Reader[R, B] = Reader(r => f(run(r)), metadata, getLineage)
    def flatMap[B](f: A => Reader[R, B]): Reader[R, B] =
      Reader(r => f(run(r)).run(r), metadata, getLineage)
    def provideContext(ctx: R): A = run(ctx)
    def provide(ctx: R): A        = run(ctx)

    /**
     * Attaches custom metadata to this Reader.
     *
     * @param meta the metadata to attach (can be any type)
     * @return a new Reader with the attached metadata
     */
    def withMetadata(meta: Any): Reader[R, A] = copy(metadata = meta)

    /**
     * Attaches lineage information to this Reader.
     *
     * @param lin the lineage to attach
     * @return a new Reader with the attached lineage
     */
    def withLineage(lin: Lineage): Reader[R, A] = copy(getLineage = Some(lin))

    /**
     * Sets or updates the lineage name.
     */
    def lineageName(name: String): Reader[R, A] = {
      val updated = this.getLineage match {
        case Some(lin) => lin.copy(name = name)
        case None      => Lineage(name = name)
      }
      this.withLineage(updated)
    }

    /**
     * Sets or updates the lineage inputs.
     */
    def lineageInputs(inputs: String*): Reader[R, A] = {
      val updated = this.getLineage match {
        case Some(lin) => lin.copy(inputs = inputs.toList)
        case None      => Lineage(name = "", inputs = inputs.toList)
      }
      this.withLineage(updated)
    }

    /**
     * Sets or updates the lineage outputs.
     */
    def lineageOutputs(outputs: String*): Reader[R, A] = {
      val updated = this.getLineage match {
        case Some(lin) => lin.copy(outputs = outputs.toList)
        case None      => Lineage(name = "", outputs = outputs.toList)
      }
      this.withLineage(updated)
    }
  }

  object Reader {
    def pure[R, A](a: A): Reader[R, A] = Reader(_ => a)
    def ask[R]: Reader[R, R]           = Reader(identity)
  }

  /**
   * HasMetadata instances for Node and Reader.
   */
  object HasMetadata {
    // Basically just type lambda syntax: ({type L[X] = SomeType})#L creates a type constructor
    // from multi-parameter types to work with single-parameter typeclasses like HasMetadata[F[_]]
    // ... to keep the cross builds between 2.12, 2.13 and 3.x simple for now
    implicit def nodeHasMetadata[A, B]: HasMetadata[({ type L[X] = Node[A, B] })#L] =
      new HasMetadata[({ type L[X] = Node[A, B] })#L] {
        def metadata[X](fa: Node[A, B]): Any                       = fa.metadata
        def withMetadata[X](fa: Node[A, B], meta: Any): Node[A, B] = fa.withMetadata(meta)
      }

    implicit def readerHasMetadata[R]: HasMetadata[({ type L[A] = Reader[R, A] })#L] =
      new HasMetadata[({ type L[A] = Reader[R, A] })#L] {
        def metadata[A](fa: Reader[R, A]): Any                         = fa.metadata
        def withMetadata[A](fa: Reader[R, A], meta: Any): Reader[R, A] = fa.withMetadata(meta)
      }
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
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.chain(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA ~> nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def ~>[T2, C, R](fb: Reader[T2, Node[B, C]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[A, C]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.chain(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, C]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA ~> nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def ~>[C](node: Node[B, C]): Reader[T1, Node[A, C]] = {
      val combined = (fa.getLineage, node.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.chain(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = fa.map(contextNode => contextNode ~> node)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
      *  &: Reader(Node) & {Reader(Node) | Reader(Node) compat | Node}
      */
    def &[C](
      fb: Reader[T1, Node[A, C]]
    )(implicit ta: TupleAppend[B, C]): Reader[T1, Node[A, ta.Out]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA & nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def &[T2, C, R](fb: Reader[T2, Node[A, C]])(implicit
      compat: ReaderCompat[T1, T2, R],
      ta: TupleAppend[B, C]
    ): Reader[R, Node[A, ta.Out]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, ta.Out]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA & nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def &[C](node: Node[A, C])(implicit ta: TupleAppend[B, C]): Reader[T1, Node[A, ta.Out]] = {
      val combined = (fa.getLineage, node.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = fa.map(readerNode => readerNode & node)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
      *  &>: Reader(Node) &> {Reader(Node) | Reader(Node) compat | Node}
      */
    def &>[C](fb: Reader[T1, Node[A, C]])(implicit
      ec: ExecutionContext,
      ta: TupleAppend[B, C]
    ): Reader[T1, Node[A, ta.Out]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA &> nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def &>[T2, C, R](fb: Reader[T2, Node[A, C]])(implicit
      compat: ReaderCompat[T1, T2, R],
      ec: ExecutionContext,
      ta: TupleAppend[B, C]
    ): Reader[R, Node[A, ta.Out]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, ta.Out]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA &> nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def &>[C](node: Node[A, C])(implicit
      ec: ExecutionContext,
      ta: TupleAppend[B, C]
    ): Reader[T1, Node[A, ta.Out]] = {
      val combined = (fa.getLineage, node.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = fa.map(readerNode => readerNode &> node)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
      *  >>: Reader(Node) >> {Reader(Node) | Reader(Node) compat | Node}
      */
    def >>[C](fb: Reader[T1, Node[A, C]]): Reader[T1, Node[A, C]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA >> nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def >>[T2, C, R](fb: Reader[T2, Node[A, C]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[A, C]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, C]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA >> nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def >>[C](node: Node[A, C]): Reader[T1, Node[A, C]] = {
      val combined = (fa.getLineage, node.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = fa.map(readerNode => readerNode >> node)
      combined.fold(result)(lin => result.withLineage(lin))
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
     * val contextExtract = Context.Extract[Config, String, Int] { config => input =>
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
   * Execution trace with result, logs, timing, and errors.
   *
   * @tparam A the result type
   * @param result the computation result
   * @param logs collected log values (any type)
   * @param timeElapsedMillis execution duration in milliseconds
   * @param errors errors encountered (any type)
   */
  /**
   * Result container for traced pipeline execution.
   *
   * @tparam A the result type
   * @param result the final result value
   * @param logs collected log values (any type)
   * @param timeElapsedMillis execution duration in milliseconds
   * @param errors errors encountered (any type)
   */
  case class Trace[A](
    result: A,
    logs: List[Any] = List.empty,
    timeElapsedMillis: Long = 0L,
    errors: List[Any] = List.empty
  ) {

    /** Check if any errors occurred */
    def hasErrors: Boolean = errors.nonEmpty

    /** Get timing in seconds */
    def seconds: Double = timeElapsedMillis / 1000.0

    /** Get logs as strings */
    def logsAsStrings: List[String] = logs.map(_.toString)

    /** Get errors as strings */
    def errorsAsStrings: List[String] = errors.map(_.toString)
  }

  /** Utility functions */
  def tap[A](f: A => Any): Node[A, A] = Node[A, A](a => { f(a); a })

  /**
   * Implicit conversion from Function1 to Node.
   * 
   * This allows you to use plain functions directly as Nodes without wrapping.
   * 
   * @example
   * {{{
   * val length: String => Int = _.length
   * val upper: String => String = _.toUpperCase
   * 
   * // Can use directly without Node(...)
   * val pipeline = length ~> upper
   * }}}
   */
  implicit def function1ToNode[A, B](f: A => B): Node[A, B] = Node(f)

  // ValidationCheck, CurriedCheck, PlainCheck defined in shared src/Core.scala

  /**
   * Implicit conversions for validation checks
   */
  implicit def curriedToCheck[T, A](f: T => A => Option[String]): ValidationCheck[T, A] =
    CurriedCheck(f)

  implicit def plainToCheck[T, A](f: A => Option[String]): ValidationCheck[T, A] =
    PlainCheck(f)

  /**
   * Access to current pipeline execution state.
   * 
   * Provides unified access to the currently executing pipeline's runtime state,
   * including logs, validation errors, and execution timing.
   */
  object Trace {
    // Platform-specific local var that holds all trace state: (logs, errors, startTime)
    private val traceCollector: LocalVar[Option[(List[Any], List[Any], Long)]] =
      Platform.newLocalVar(None)

    def setCollector(startTime: Long): Unit = {
      traceCollector.set(Some((List.empty, List.empty, startTime)))
    }

    def clearCollector(): Unit = {
      traceCollector.set(None)
    }

    /** Save current collector state for later restoration (supports nesting) */
    def saveCollector(): Option[(List[Any], List[Any], Long)] = {
      traceCollector.get()
    }

    /** Restore a previously saved collector state */
    def restoreCollector(state: Option[(List[Any], List[Any], Long)]): Unit = {
      traceCollector.set(state)
    }

    /**
     * Get the current execution trace as it's being built.
     * 
     * Returns a live view of the execution state including logs, errors,
     * and current execution time.
     */
    def current: Trace[Any] = {
      traceCollector.get() match {
        case Some((logs, errors, startTime)) =>
          val timeElapsedMillis = System.currentTimeMillis() - startTime
          Trace(
            result = (), // Result not available during execution
            logs = logs.reverse,
            timeElapsedMillis = timeElapsedMillis,
            errors = errors.reverse
          )
        case None =>
          Trace(
            result = (),
            logs = List.empty,
            timeElapsedMillis = 0L,
            errors = List.empty
          )
      }
    }

    /** Add a log value to the current execution (any type) */
    def log[T](message: T): Unit = {
      traceCollector.get() match {
        case Some((currentLogs, currentErrors, startTime)) =>
          traceCollector.set(Some((message :: currentLogs, currentErrors, startTime)))
        case None =>
        // No collector set, log is ignored
      }
    }

    /** Add an error to the current execution (any type) */
    def error[T](err: T): Unit = {
      traceCollector.get() match {
        case Some((currentLogs, currentErrors, startTime)) =>
          traceCollector.set(Some((currentLogs, err :: currentErrors, startTime)))
        case None =>
        // No collector set, error is ignored
      }
    }

    /** Get the current trace state */
    def getCurrent: Trace[Any] = current

    /** Get current logs */
    def getLogs: List[Any] = current.logs

    /** Get current errors */
    def getErrors: List[Any] = current.errors

    /** Get elapsed time in milliseconds */
    def getElapsedTimeMillis: Long = current.timeElapsedMillis

    /** Get elapsed time in seconds */
    def getElapsedTimeSeconds: Double = current.seconds

    /** Get logs as strings */
    def getLogsAsStrings: List[String] = current.logsAsStrings

    /** Get errors as strings */
    def getErrorsAsStrings: List[String] = current.errorsAsStrings

    /** Check if there are any errors */
    def hasErrors: Boolean = current.hasErrors

    /** Check if there are any logs */
    def hasLogs: Boolean = current.logs.nonEmpty

    /** Get the number of logs */
    def getLogCount: Int = current.logs.size

    /** Get the number of errors */
    def getErrorCount: Int = current.errors.size

    /** Get the last log (most recent) */
    def getLastLog: Option[Any] = current.logs.headOption

    /** Get the last error (most recent) */
    def getLastError: Option[Any] = current.errors.headOption
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
   * Type class for appending an element to a tuple, building flat tuples.
   * Used by the & operator to auto-flatten parallel compositions.
   *
   * For non-tuple A: A & B => (A, B)
   * For tuple A: (A1, A2) & B => (A1, A2, B)
   *
   * This enables: node1 & node2 & node3 to produce Node[In, (Out1, Out2, Out3)]
   * instead of Node[In, ((Out1, Out2), Out3)]
   */
  trait TupleAppend[A, B] {
    type Out
    def append(a: A, b: B): Out
  }

  trait TupleAppendLowestPriority {
    // Fallback: when A is not a tuple, create a pair
    implicit def pairAppend[A, B]: TupleAppend.Aux[A, B, (A, B)] =
      new TupleAppend[A, B] {
        type Out = (A, B)
        def append(a: A, b: B): (A, B) = (a, b)
      }
  }

  trait TupleAppendLowPriority extends TupleAppendLowestPriority {
    implicit def append2[A, B, C]: TupleAppend.Aux[(A, B), C, (A, B, C)] =
      new TupleAppend[(A, B), C] {
        type Out = (A, B, C)
        def append(t: (A, B), c: C): (A, B, C) = (t._1, t._2, c)
      }
  }

  trait TupleAppend3 extends TupleAppendLowPriority {
    implicit def append3[A, B, C, D]: TupleAppend.Aux[(A, B, C), D, (A, B, C, D)] =
      new TupleAppend[(A, B, C), D] {
        type Out = (A, B, C, D)
        def append(t: (A, B, C), d: D): (A, B, C, D) = (t._1, t._2, t._3, d)
      }
  }

  trait TupleAppend4 extends TupleAppend3 {
    implicit def append4[A, B, C, D, E]: TupleAppend.Aux[(A, B, C, D), E, (A, B, C, D, E)] =
      new TupleAppend[(A, B, C, D), E] {
        type Out = (A, B, C, D, E)
        def append(t: (A, B, C, D), e: E): (A, B, C, D, E) = (t._1, t._2, t._3, t._4, e)
      }
  }

  trait TupleAppend5 extends TupleAppend4 {
    implicit def append5[A, B, C, D, E, F]
      : TupleAppend.Aux[(A, B, C, D, E), F, (A, B, C, D, E, F)] =
      new TupleAppend[(A, B, C, D, E), F] {
        type Out = (A, B, C, D, E, F)
        def append(t: (A, B, C, D, E), f: F): (A, B, C, D, E, F) = (t._1, t._2, t._3, t._4, t._5, f)
      }
  }

  trait TupleAppend6 extends TupleAppend5 {
    implicit def append6[A, B, C, D, E, F, G]
      : TupleAppend.Aux[(A, B, C, D, E, F), G, (A, B, C, D, E, F, G)] =
      new TupleAppend[(A, B, C, D, E, F), G] {
        type Out = (A, B, C, D, E, F, G)
        def append(t: (A, B, C, D, E, F), g: G): (A, B, C, D, E, F, G) =
          (t._1, t._2, t._3, t._4, t._5, t._6, g)
      }
  }

  trait TupleAppend7 extends TupleAppend6 {
    implicit def append7[A, B, C, D, E, F, G, H]
      : TupleAppend.Aux[(A, B, C, D, E, F, G), H, (A, B, C, D, E, F, G, H)] =
      new TupleAppend[(A, B, C, D, E, F, G), H] {
        type Out = (A, B, C, D, E, F, G, H)
        def append(t: (A, B, C, D, E, F, G), h: H): (A, B, C, D, E, F, G, H) =
          (t._1, t._2, t._3, t._4, t._5, t._6, t._7, h)
      }
  }

  trait TupleAppend8 extends TupleAppend7 {
    implicit def append8[A, B, C, D, E, F, G, H, I]
      : TupleAppend.Aux[(A, B, C, D, E, F, G, H), I, (A, B, C, D, E, F, G, H, I)] =
      new TupleAppend[(A, B, C, D, E, F, G, H), I] {
        type Out = (A, B, C, D, E, F, G, H, I)
        def append(t: (A, B, C, D, E, F, G, H), i: I): (A, B, C, D, E, F, G, H, I) =
          (t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, i)
      }
  }

  trait TupleAppend9 extends TupleAppend8 {
    implicit def append9[A, B, C, D, E, F, G, H, I, J]
      : TupleAppend.Aux[(A, B, C, D, E, F, G, H, I), J, (A, B, C, D, E, F, G, H, I, J)] =
      new TupleAppend[(A, B, C, D, E, F, G, H, I), J] {
        type Out = (A, B, C, D, E, F, G, H, I, J)
        def append(t: (A, B, C, D, E, F, G, H, I), j: J): (A, B, C, D, E, F, G, H, I, J) =
          (t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, j)
      }
  }

  trait TupleAppend10 extends TupleAppend9 {
    implicit def append10[A, B, C, D, E, F, G, H, I, J, K]
      : TupleAppend.Aux[(A, B, C, D, E, F, G, H, I, J), K, (A, B, C, D, E, F, G, H, I, J, K)] =
      new TupleAppend[(A, B, C, D, E, F, G, H, I, J), K] {
        type Out = (A, B, C, D, E, F, G, H, I, J, K)
        def append(t: (A, B, C, D, E, F, G, H, I, J), k: K): (A, B, C, D, E, F, G, H, I, J, K) =
          (t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, k)
      }
  }

  object TupleAppend extends TupleAppend10 {
    type Aux[A, B, O] = TupleAppend[A, B] { type Out = O }
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
   * object MyETL extends Context[MyConfig] {
   *   val saveUser = Context.Load[User, Unit] { config => user =>
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
  trait Context[T] {

    /**
     * Provides natural access to context-wrapped operations.
     * Use as: `Context.Extract[A, B] { ctx => in => out }`
     */
    object Context {
      def Extract[A, B](f: T => A => B): Reader[T, Extract[A, B]] =
        etl4s.Extract.requires[T, A, B](f)

      def Transform[A, B](f: T => A => B): Reader[T, Transform[A, B]] =
        etl4s.Transform.requires[T, A, B](f)

      def Load[A, B](f: T => A => B): Reader[T, Load[A, B]] =
        etl4s.Load.requires[T, A, B](f)

      def Pipeline[A, B](f: T => A => B): Reader[T, Pipeline[A, B]] =
        etl4s.Pipeline.requires[T, A, B](f)

      def tap[A](f: T => A => Any): Reader[T, Node[A, A]] =
        Reader { ctx =>
          Node { a =>
            f(ctx)(a)
            a
          }
        }
    }
  }

  /**
   * OpenTelemetry integration for etl4s pipelines.
   * 
   * Provides span, counter, gauge, and histogram recording within pipeline execution.
   * Uses ThreadLocal to automatically work when Etl4sTelemetry is set via implicit parameter
   * to run methods.
   * 
   * @example
   * {{{
   * val extract = Extract[String, List[User]] { input =>
   *   Tel.withSpan("user-parsing") {
   *     Trace.log("Starting extraction")
   *     val users = parseUsers(input)
   *     Tel.addCounter("users.extracted", users.size.toLong)
   *     Tel.recordHistogram("batch.size", users.size.toDouble)
   *     users
   *   }
   * }
   * 
   * // With OTel provider
   * implicit val otel: Etl4sTelemetry = ConsoleEtl4sTelemetry()
   * pipeline.unsafeRun(data)
   * 
   * // Without OTel provider - all calls are no-ops
   * pipeline.unsafeRun(data)
   * }}}
   */
  object Tel {
    private val observabilityProvider: LocalVar[Option[Etl4sTelemetry]] =
      Platform.newLocalVar(None)

    private[etl4s] def setProvider(provider: Etl4sTelemetry): Unit = {
      observabilityProvider.set(Some(provider))
    }

    private[etl4s] def clearProvider(): Unit = {
      observabilityProvider.set(None)
    }

    /**
     * Execute block within a named span with optional attributes.
     * No-op if no Etl4sTelemetry is set.
     */
    def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
      val provider = observabilityProvider.get()
      provider match {
        case Some(p) => p.withSpan(name, attributes: _*)(block)
        case None    => block
      }
    }

    /**
     * Add an event to the current span.
     */
    def addEvent(name: String, attributes: (String, Any)*): Unit = {
      // No-op by default - only works when real telemetry provider is active
      // Implementation provided by actual telemetry integration
    }

    /**
     * Record a counter metric.
     * No-op if no Etl4sTelemetry is set.
     */
    def addCounter(name: String, value: Long): Unit = {
      val provider = observabilityProvider.get()
      provider.foreach(_.addCounter(name, value))
    }

    /**
     * Record a gauge metric.
     * No-op if no Etl4sTelemetry is set.
     */
    def setGauge(name: String, value: Double): Unit = {
      val provider = observabilityProvider.get()
      provider.foreach(_.setGauge(name, value))
    }

    /**
     * Record a histogram metric.
     * No-op if no Etl4sTelemetry is set.
     */
    def recordHistogram(name: String, value: Double): Unit = {
      val provider = observabilityProvider.get()
      provider.foreach(_.recordHistogram(name, value))
    }
  }

  // Etl4sTelemetry, Etl4sNoOpTelemetry, Etl4sConsoleTelemetry defined in shared src/Telemetry.scala

  /**
   * Typeclass for rendering lineage information to various formats.
   */
  trait LineageRenderer[T] {
    def toJson(t: T): String
    def toDot(t: T): String
    def toMermaid(t: T): String
  }

  object LineageRenderer {
    private def singleItemRenderer[T]: LineageRenderer[T] = new LineageRenderer[T] {
      def toJson(t: T): String    = new LineageCollectionOps(Seq(t)).toJson
      def toDot(t: T): String     = new LineageCollectionOps(Seq(t)).toDot
      def toMermaid(t: T): String = new LineageCollectionOps(Seq(t)).toMermaid
    }

    implicit def nodeRenderer[A, B]: LineageRenderer[Node[A, B]] = singleItemRenderer[Node[A, B]]
    implicit def readerRenderer[R, A]: LineageRenderer[Reader[R, A]] =
      singleItemRenderer[Reader[R, A]]

    implicit def seqRenderer[T]: LineageRenderer[Seq[T]] = new LineageRenderer[Seq[T]] {
      def toJson(items: Seq[T]): String    = new LineageCollectionOps(items).toJson
      def toDot(items: Seq[T]): String     = new LineageCollectionOps(items).toDot
      def toMermaid(items: Seq[T]): String = new LineageCollectionOps(items).toMermaid
    }
  }

  /**
   * Extension methods for lineage rendering using typeclass.
   */
  implicit class LineageOps[T](val t: T)(implicit renderer: LineageRenderer[T]) {
    def toJson: String    = renderer.toJson(t)
    def toDot: String     = renderer.toDot(t)
    def toMermaid: String = renderer.toMermaid(t)
  }

  /**
   * Extension methods for adding lineage to Nodes.
   */
  implicit class NodeLineageOps[A, B](val node: Node[A, B]) {

    /**
     * Attaches lineage information to this node.
     *
     * @param name the unique name/identifier for this pipeline component
     * @param inputs list of input data source names
     * @param outputs list of output data source names
     * @param schedule optional schedule information
     * @param cluster optional cluster/group name
     * @param upstreams list of upstream Node/Reader objects or String names this depends on
     * @return a new Node with the attached lineage
     *
     * @example
     * {{{
     * val enrichment = Node[User, EnrichedUser](enrich)
     *   .lineage(
     *     name = "user-enrichment",
     *     inputs = List("raw_users", "user_events"),
     *     outputs = List("enriched_users"),
     *     schedule = "Every 2 hours",
     *     cluster = "user-processing",
     *     upstreams = List(userExtract, eventExtract)
     *   )
     * }}}
     */
    def lineage(
      name: String,
      inputs: List[String] = List.empty,
      outputs: List[String] = List.empty,
      upstreams: List[Any] = List.empty,
      schedule: String = "",
      cluster: String = "",
      description: String = "",
      group: String = "",
      tags: List[String] = List.empty,
      links: Map[String, String] = Map.empty
    ): Node[A, B] = {
      node.withLineage(
        Lineage(
          name,
          inputs,
          outputs,
          upstreams,
          schedule,
          cluster,
          description,
          group,
          tags,
          links
        )
      )
    }
  }

  /**
   * Extension methods for adding lineage to Readers.
   */
  implicit class ReaderLineageOps[R, A](val reader: Reader[R, A]) {

    /**
     * Attaches lineage information to this reader.
     *
     * @param name the unique name/identifier for this pipeline component
     * @param inputs list of input data source names
     * @param outputs list of output data source names
     * @param schedule optional schedule information
     * @param cluster optional cluster/group name
     * @param upstreams list of upstream Node/Reader objects or String names this depends on
     * @param description optional description of the pipeline
     * @param group optional group name for collapsing nodes
     * @param tags optional list of tags
     * @param links optional map of link names to URLs
     * @return a new Reader with the attached lineage
     */
    def lineage(
      name: String,
      inputs: List[String] = List.empty,
      outputs: List[String] = List.empty,
      upstreams: List[Any] = List.empty,
      schedule: String = "",
      cluster: String = "",
      description: String = "",
      group: String = "",
      tags: List[String] = List.empty,
      links: Map[String, String] = Map.empty
    ): Reader[R, A] = {
      reader.withLineage(
        Lineage(
          name,
          inputs,
          outputs,
          upstreams,
          schedule,
          cluster,
          description,
          group,
          tags,
          links
        )
      )
    }
  }

  // LineageNode, LineageEdge, LineageCluster, LineageGraph defined in shared src/Lineage.scala
  // ValidationException defined in shared src/Telemetry.scala

  /**
   * Extension methods for adding validation to Nodes.
   * 
   * Validation functions return None if valid, Some(errorMessage) if invalid.
   * All validation errors are collected and logged to Trace before throwing.
   */
  implicit class NodeValidationOps[A, B](val node: Node[A, B]) {

    private def validateInput(a: A, checks: Seq[A => Option[String]], parallel: Boolean)(implicit
      ec: ExecutionContext
    ): Unit = {
      val errors = if (parallel) {
        Platform.runAll(checks.map(check => () => check(a))).flatten
      } else {
        checks.flatMap(_(a))
      }
      if (errors.nonEmpty) {
        val errorMsg = s"Input validation failed:\n${errors.map(e => s"  - $e").mkString("\n")}"
        Trace.error(errorMsg)
        throw new ValidationException(errorMsg)
      }
    }

    private def validateOutput(b: B, checks: Seq[B => Option[String]], parallel: Boolean)(implicit
      ec: ExecutionContext
    ): Unit = {
      val errors = if (parallel) {
        Platform.runAll(checks.map(check => () => check(b))).flatten
      } else {
        checks.flatMap(_(b))
      }
      if (errors.nonEmpty) {
        val errorMsg = s"Output validation failed:\n${errors.map(e => s"  - $e").mkString("\n")}"
        Trace.error(errorMsg)
        throw new ValidationException(errorMsg)
      }
    }

    private def validateChange(
      pair: (A, B),
      checks: Seq[((A, B)) => Option[String]],
      parallel: Boolean
    )(implicit
      ec: ExecutionContext
    ): Unit = {
      val errors = if (parallel) {
        Platform.runAll(checks.map(check => () => check(pair))).flatten
      } else {
        checks.flatMap(_(pair))
      }
      if (errors.nonEmpty) {
        val errorMsg = s"Change validation failed:\n${errors.map(e => s"  - $e").mkString("\n")}"
        Trace.error(errorMsg)
        throw new ValidationException(errorMsg)
      }
    }

    private def warnInput(a: A, checks: Seq[A => Option[String]], parallel: Boolean)(implicit
      ec: ExecutionContext
    ): Unit = logWarnings("Input", checks, a, parallel)

    private def warnOutput(b: B, checks: Seq[B => Option[String]], parallel: Boolean)(implicit
      ec: ExecutionContext
    ): Unit = logWarnings("Output", checks, b, parallel)

    private def warnChange(
      pair: (A, B),
      checks: Seq[((A, B)) => Option[String]],
      parallel: Boolean
    )(implicit
      ec: ExecutionContext
    ): Unit = logWarnings("Change", checks, pair, parallel)

    private def logWarnings[V](
      stage: String,
      checks: Seq[V => Option[String]],
      value: V,
      parallel: Boolean
    )(implicit
      ec: ExecutionContext
    ): Unit = {
      val errors = if (parallel) {
        Platform.runAll(checks.map(check => () => check(value))).flatten
      } else {
        checks.flatMap(_(value))
      }
      if (errors.nonEmpty) {
        Trace.log(s"$stage validation warning:\n${errors.map(e => s"  - $e").mkString("\n")}")
      }
    }

    /**
     * Adds multiple validation checks in one call.
     * 
     * @param input validation functions for input
     * @param output validation functions for output
     * @param change validation functions for the transformation
     * @return a new Node with all validations applied
     * 
     * @example
     * {{{
     * val process = Node[Int, String](n => s"Value: $n")
     *   .ensure(
     *     input = Seq(x => if (x > 0) None else Some("Must be positive")),
     *     output = Seq(s => if (s.nonEmpty) None else Some("Must not be empty"))
     *   )
     * }}}
     */
    def ensure(
      input: Seq[A => Option[String]] = Nil,
      output: Seq[B => Option[String]] = Nil,
      change: Seq[((A, B)) => Option[String]] = Nil
    ): Node[A, B] =
      if (input.isEmpty && output.isEmpty && change.isEmpty) node
      else
        Node { a =>
          if (input.nonEmpty) validateInput(a, input, parallel = false)(ExecutionContext.global)
          val result = node.f(a)
          if (output.nonEmpty)
            validateOutput(result, output, parallel = false)(ExecutionContext.global)
          if (change.nonEmpty)
            validateChange((a, result), change, parallel = false)(ExecutionContext.global)
          result
        }

    /**
     * Adds multiple validation checks in one call with parallel execution.
     * 
     * @param input validation functions for input
     * @param output validation functions for output
     * @param change validation functions for the transformation
     * @param ec ExecutionContext for parallel execution
     * @return a new Node with all validations applied
     */
    def ensurePar(
      input: Seq[A => Option[String]] = Nil,
      output: Seq[B => Option[String]] = Nil,
      change: Seq[((A, B)) => Option[String]] = Nil
    )(implicit ec: ExecutionContext = ExecutionContext.global): Node[A, B] =
      if (input.isEmpty && output.isEmpty && change.isEmpty) node
      else
        Node { a =>
          if (input.nonEmpty) validateInput(a, input, parallel = true)
          val result = node.f(a)
          if (output.nonEmpty) validateOutput(result, output, parallel = true)
          if (change.nonEmpty) validateChange((a, result), change, parallel = true)
          result
        }

    /**
     * Adds validation checks that log warnings instead of throwing exceptions.
     * 
     * @param input validation functions for input
     * @param output validation functions for output
     * @param change validation functions for the transformation
     * @return a new Node with all validations applied
     */
    def ensureWarn(
      input: Seq[A => Option[String]] = Nil,
      output: Seq[B => Option[String]] = Nil,
      change: Seq[((A, B)) => Option[String]] = Nil
    ): Node[A, B] =
      if (input.isEmpty && output.isEmpty && change.isEmpty) node
      else
        Node { a =>
          if (input.nonEmpty) warnInput(a, input, parallel = false)(ExecutionContext.global)
          val result = node.f(a)
          if (output.nonEmpty) warnOutput(result, output, parallel = false)(ExecutionContext.global)
          if (change.nonEmpty)
            warnChange((a, result), change, parallel = false)(ExecutionContext.global)
          result
        }

    /**
     * Adds validation checks with parallel execution that log warnings instead of throwing exceptions.
     * 
     * @param input validation functions for input
     * @param output validation functions for output
     * @param change validation functions for the transformation
     * @param ec ExecutionContext for parallel execution
     * @return a new Node with all validations applied
     */
    def ensureParWarn(
      input: Seq[A => Option[String]] = Nil,
      output: Seq[B => Option[String]] = Nil,
      change: Seq[((A, B)) => Option[String]] = Nil
    )(implicit ec: ExecutionContext = ExecutionContext.global): Node[A, B] =
      if (input.isEmpty && output.isEmpty && change.isEmpty) node
      else
        Node { a =>
          if (input.nonEmpty) warnInput(a, input, parallel = true)
          val result = node.f(a)
          if (output.nonEmpty) warnOutput(result, output, parallel = true)
          if (change.nonEmpty) warnChange((a, result), change, parallel = true)
          result
        }
  }

  /**
   * Non-exhaustive conditional builder for Nodes.
   */
  case class PartialConditionalBuilder[A, B, C](
    sourceNode: Node[A, B],
    branches: List[(B => Boolean, Node[B, C])]
  ) {
    def ElseIf(condition: B => Boolean)(branch: Node[B, C]): PartialConditionalBuilder[A, B, C] =
      PartialConditionalBuilder(sourceNode, branches :+ (condition, branch))

    def Else(branch: Node[B, C]): Node[A, C] = Node { a =>
      val b = sourceNode.f(a)
      branches.find(_._1(b)).map(_._2.f(b)).getOrElse(branch.f(b))
    }
  }

  /**
   * Exhaustive conditional builder for Nodes.
   */
  case class CompleteConditionalBuilder[A, B, C](
    sourceNode: Node[A, B],
    branches: List[(B => Boolean, Node[B, C])],
    defaultBranch: Node[B, C]
  ) {
    def ElseIf(condition: B => Boolean)(branch: Node[B, C]): CompleteConditionalBuilder[A, B, C] =
      CompleteConditionalBuilder(sourceNode, branches :+ (condition, branch), defaultBranch)

    def build: Node[A, C] = Node { a =>
      val b = sourceNode.f(a)
      branches.find(_._1(b)).map(_._2.f(b)).getOrElse(defaultBranch.f(b))
    }
  }

  /**
   * Conditional branching for Nodes.
   */
  implicit class NodeConditionalOps[A, B](val node: Node[A, B]) {
    def If[C](condition: B => Boolean)(branch: Node[B, C]): PartialConditionalBuilder[A, B, C] =
      PartialConditionalBuilder(node, List((condition, branch)))
  }

  implicit def conditionalBuilderToNode[A, B, C](
    builder: CompleteConditionalBuilder[A, B, C]
  ): Node[A, C] =
    builder.build

  /**
   * Type class for lifting branches (Node or Reader) to Reader.
   */
  trait BranchLift[T, B, C, Branch] {
    def lift(branch: Branch): Reader[T, Node[B, C]]
  }

  object BranchLift extends BranchLiftLowPriority {
    implicit def nodeToReader[T, B, C]: BranchLift[T, B, C, Node[B, C]] =
      new BranchLift[T, B, C, Node[B, C]] {
        def lift(branch: Node[B, C]): Reader[T, Node[B, C]] = Reader.pure(branch)
      }

    implicit def readerIdentity[T, B, C]: BranchLift[T, B, C, Reader[T, Node[B, C]]] =
      new BranchLift[T, B, C, Reader[T, Node[B, C]]] {
        def lift(branch: Reader[T, Node[B, C]]): Reader[T, Node[B, C]] = branch
      }
  }

  trait BranchLiftLowPriority {
    implicit def builderToReader[T, B, X, C]
      : BranchLift[T, B, C, ReaderCompleteConditionalBuilder[T, B, X, C]] =
      new BranchLift[T, B, C, ReaderCompleteConditionalBuilder[T, B, X, C]] {
        def lift(builder: ReaderCompleteConditionalBuilder[T, B, X, C]): Reader[T, Node[B, C]] =
          builder.build
      }
  }

  /**
   * Non-exhaustive conditional builder for Reader-wrapped nodes.
   */
  case class ReaderPartialConditionalBuilder[T, A, B, C](
    sourceReader: Reader[T, Node[A, B]],
    branches: List[(T => B => Boolean, Reader[T, Node[B, C]])]
  ) {
    def ElseIf[Branch](condition: T => B => Boolean)(branch: Branch)(implicit
      lift: BranchLift[T, B, C, Branch]
    ): ReaderPartialConditionalBuilder[T, A, B, C] =
      ReaderPartialConditionalBuilder(sourceReader, branches :+ (condition, lift.lift(branch)))

    def Else[Branch](branch: Branch)(implicit
      lift: BranchLift[T, B, C, Branch]
    ): Reader[T, Node[A, C]] = Reader { ctx =>
      val sourceNode = sourceReader.run(ctx)
      val evaluatedBranches = branches.map { case (check, readerBranch) =>
        (check(ctx), readerBranch.run(ctx))
      }
      val evaluatedDefault = lift.lift(branch).run(ctx)
      Node { a =>
        val b = sourceNode.f(a)
        evaluatedBranches.find(_._1(b)).map(_._2.f(b)).getOrElse(evaluatedDefault.f(b))
      }
    }
  }

  /**
   * Exhaustive conditional builder for Reader-wrapped nodes.
   */
  case class ReaderCompleteConditionalBuilder[T, A, B, C](
    sourceReader: Reader[T, Node[A, B]],
    branches: List[(T => B => Boolean, Reader[T, Node[B, C]])],
    defaultBranch: Reader[T, Node[B, C]]
  ) {
    def ElseIf[Branch](condition: T => B => Boolean)(branch: Branch)(implicit
      lift: BranchLift[T, B, C, Branch]
    ): ReaderCompleteConditionalBuilder[T, A, B, C] =
      ReaderCompleteConditionalBuilder(
        sourceReader,
        branches :+ (condition, lift.lift(branch)),
        defaultBranch
      )

    def build: Reader[T, Node[A, C]] = Reader { ctx =>
      val sourceNode = sourceReader.run(ctx)
      val evaluatedBranches = branches.map { case (check, readerBranch) =>
        (check(ctx), readerBranch.run(ctx))
      }
      val evaluatedDefault = defaultBranch.run(ctx)

      Node { a =>
        val b = sourceNode.f(a)
        evaluatedBranches.find(_._1(b)).map(_._2.f(b)).getOrElse(evaluatedDefault.f(b))
      }
    }
  }

  implicit def readerConditionalBuilderToReader[T, A, B, C](
    builder: ReaderCompleteConditionalBuilder[T, A, B, C]
  ): Reader[T, Node[A, C]] = builder.build

  /**
   * Conditional branching for Reader-wrapped Nodes.
   */
  implicit class ReaderConditionalOps[T, A, B](val reader: Reader[T, Node[A, B]]) {
    def If[C, Branch](condition: T => B => Boolean)(branch: Branch)(implicit
      lift: BranchLift[T, B, C, Branch]
    ): ReaderPartialConditionalBuilder[T, A, B, C] =
      ReaderPartialConditionalBuilder(reader, List((condition, lift.lift(branch))))
  }

  /**
   * Extension methods for adding validation to Reader-wrapped Nodes.
   * 
   * Validation functions use curried form (T => A => Option[String]) to match
   * the Reader pattern. This allows validations to be context-aware and composable.
   * 
   * For context-independent checks, use `(_: T) => ...` to ignore the context.
   */
  implicit class ReaderValidationOps[T, A, B](val fa: Reader[T, Node[A, B]]) {

    /**
     * Adds multiple validation checks in one call.
     * 
     * Accepts both curried (T => A => Option[String]) and plain (A => Option[String]) checks.
     * Plain checks are automatically lifted to ignore the context.
     * 
     * @param input validation functions for input
     * @param output validation functions for output
     * @param change validation functions for the transformation
     * @return a new Reader[T, Node[A, B]] with all validations applied
     * 
     * @example
     * {{{
     * val checkPositive = (x: Int) => if (x > 0) None else Some("must be positive")
     * 
     * val node = Reader[Config, Node[Int, String]] { _ => Node(_.toString) }
     *   .ensure(
     *     input = Seq(
     *       (cfg: Config) => (x: Int) => if (x >= cfg.min) None else Some("too small"),
     *       checkPositive  // plain function - automatically lifted!
     *     ),
     *     output = Seq(
     *       (s: String) => if (s.nonEmpty) None else Some("empty")  // also works!
     *     )
     *   )
     * }}}
     */
    def ensure(
      input: Seq[ValidationCheck[T, A]] = Nil,
      output: Seq[ValidationCheck[T, B]] = Nil,
      change: Seq[ValidationCheck[T, (A, B)]] = Nil
    ): Reader[T, Node[A, B]] =
      if (input.isEmpty && output.isEmpty && change.isEmpty) fa
      else
        Reader { ctx =>
          val node = fa.run(ctx)
          Node { a =>
            def validate[V](checks: Seq[ValidationCheck[T, V]], value: V, stage: String): Unit = {
              val errors = checks.flatMap(_.toCurried(ctx)(value))
              if (errors.nonEmpty) {
                val errorMsg =
                  s"$stage validation failed:\n${errors.map(e => s"  - $e").mkString("\n")}"
                Trace.error(errorMsg)
                throw new ValidationException(errorMsg)
              }
            }

            if (input.nonEmpty) validate(input, a, "Input")
            val result = node.f(a)
            if (output.nonEmpty) validate(output, result, "Output")
            if (change.nonEmpty) validate(change, (a, result), "Change")
            result
          }
        }

    /**
     * Adds validation checks that log warnings instead of throwing exceptions.
     * 
     * @param input validation functions for input
     * @param output validation functions for output
     * @param change validation functions for the transformation
     * @return a new Reader[T, Node[A, B]] with all validations applied
     */
    def ensureWarn(
      input: Seq[ValidationCheck[T, A]] = Nil,
      output: Seq[ValidationCheck[T, B]] = Nil,
      change: Seq[ValidationCheck[T, (A, B)]] = Nil
    ): Reader[T, Node[A, B]] =
      if (input.isEmpty && output.isEmpty && change.isEmpty) fa
      else
        Reader { ctx =>
          val node = fa.run(ctx)
          Node { a =>
            def warn[V](checks: Seq[ValidationCheck[T, V]], value: V, stage: String): Unit = {
              val errors = checks.flatMap(_.toCurried(ctx)(value))
              if (errors.nonEmpty) {
                val errorMsg =
                  s"$stage validation warning:\n${errors.map(e => s"  - $e").mkString("\n")}"
                Trace.log(errorMsg)
              }
            }

            if (input.nonEmpty) warn(input, a, "Input")
            val result = node.f(a)
            if (output.nonEmpty) warn(output, result, "Output")
            if (change.nonEmpty) warn(change, (a, result), "Change")
            result
          }
        }
  }

  /**
   * Extension methods for collections of pipeline components with lineage.
   */
  implicit class LineageCollectionOps[T](val items: Seq[T]) {

    /**
     * Converts a collection of Nodes or Readers with lineage information to JSON format.
     *
     * @return JSON string representation of the lineage graph
     *
     * @example
     * {{{
     * val p1 = Node[String, User](parse)
     *   .lineage("user-enrichment", 
     *     inputs = List("raw_users"), 
     *     outputs = List("enriched_users"))
     * 
     * val json = Seq(p1).toJson
     * }}}
     */
    def toJson: String = {
      val lineages = items.flatMap(extractLineage)
      if (lineages.isEmpty) return """{"pipelines":[]}"""

      buildLineageGraph(lineages).toJson
    }

    /**
     * Converts a collection of Nodes or Readers with lineage information to DOT graph format.
     *
     * The resulting DOT format can be visualized using Graphviz or similar tools.
     * Pipelines are shown as boxes, data sources as ellipses, organized by cluster.
     *
     * @return DOT graph representation as a String
     *
     * @example
     * {{{
     * val p1 = Node[String, User](parse)
     *   .lineage("user-enrichment", 
     *     inputs = List("raw_users"), 
     *     outputs = List("enriched_users"))
     * 
     * val dotGraph = Seq(p1, p2).toDot
     * }}}
     */
    def toDot: String = {
      val lineages = items.flatMap(extractLineage)
      if (lineages.isEmpty)
        return "digraph EmptyGraph {\n  label=\"No lineage information found\";\n}"

      generateDotGraph(buildLineageGraph(lineages))
    }

    /**
     * Converts a collection of Nodes or Readers with lineage information to Mermaid graph format.
     *
     * The resulting Mermaid format can be visualized in GitHub, web browsers, or Mermaid-compatible tools.
     *
     * @return Mermaid graph string representation of the lineage
     *
     * @example
     * {{{
     * val p1 = Node[String, User](parse)
     *   .lineage("user-enrichment", 
     *     inputs = List("raw_users"), 
     *     outputs = List("enriched_users"))
     * 
     * val mermaidGraph = Seq(p1).toMermaid
     * }}}
     */
    def toMermaid: String = {
      val lineages = items.flatMap(extractLineage)
      if (lineages.isEmpty) return "graph LR\n    EmptyGraph[\"No lineage information found\"]"

      generateMermaidGraph(buildLineageGraph(lineages))
    }

    private def buildLineageGraph(lineages: Seq[Lineage]): LineageGraph = {
      /* Fail in duplicate names... TODO review this */
      val duplicates = lineages.groupBy(_.name).filter(_._2.size > 1)
      if (duplicates.nonEmpty) {
        throw new IllegalArgumentException(
          s"Duplicate pipeline names: ${duplicates.keys.mkString(", ")}"
        )
      }

      val allItemsWithLineage = items.flatMap(item => extractLineage(item).map(_ => item))

      /* Auto-infers upstreams by matching output -> input */
      val enrichedLineages = lineages.map { lineage =>
        val inferredUpstreams = allItemsWithLineage.filter { item =>
          extractLineage(item).exists { upstream =>
            upstream.name != lineage.name &&
            upstream.outputs.exists(lineage.inputs.contains)
          }
        }
        lineage.copy(upstreams = (lineage.upstreams ++ inferredUpstreams).distinct)
      }

      val allClusters = enrichedLineages.map(_.cluster).filter(_.nonEmpty).distinct.toList
      LineageGraph(
        pipelines = enrichedLineages.map(lineageToNode).toList,
        datasources = (enrichedLineages.flatMap(_.inputs) ++ enrichedLineages.flatMap(
          _.outputs
        )).distinct.toList,
        clusters = allClusters.map(name => LineageCluster(name)),
        edges = collectEdges(enrichedLineages)
      )
    }

    private def lineageToNode(l: Lineage): LineageNode = {
      val pipelineNames = l.upstreams.flatMap(extractPipelineName)
      val groupNames    = l.upstreams.flatMap(extractPipelineGroup)
      LineageNode(
        name = l.name,
        input_sources = l.inputs,
        output_sources = l.outputs,
        upstream_pipelines = (pipelineNames ++ groupNames).distinct,
        schedule = l.schedule,
        cluster = l.cluster,
        description = l.description,
        group = l.group,
        tags = l.tags,
        links = l.links
      )
    }

    private def extractLineage(item: Any): Option[Lineage] = item match {
      case n: Node[_, _]   => n.getLineage
      case r: Reader[_, _] => r.getLineage
      case _               => None
    }

    private def extractPipelineName(obj: Any): Option[String] = obj match {
      case n: Node[_, _]   => n.getLineage.map(_.name)
      case r: Reader[_, _] => r.getLineage.map(_.name)
      case s: String       => Some(s)
      case _               => None
    }

    private def extractPipelineGroup(obj: Any): Option[String] = obj match {
      case n: Node[_, _] => n.getLineage.flatMap(l => if (l.group.nonEmpty) Some(l.group) else None)
      case r: Reader[_, _] =>
        r.getLineage.flatMap(l => if (l.group.nonEmpty) Some(l.group) else None)
      case _ => None
    }

    private def generateDotGraph(graph: LineageGraph): String = {
      val builder = new StringBuilder
      builder.append("digraph G {\n")
      builder.append("    rankdir=LR; bgcolor=\"transparent\";\n")
      builder.append("    node [fontsize=12, fontname=\"Arial\"];\n")
      builder.append("    edge [fontsize=10, arrowsize=0.8];\n\n")

      renderDotContent(builder, graph)

      builder.append("\n    overlap=false; splines=true;\n}\n").toString
    }

    private def renderDotContent(builder: StringBuilder, graph: LineageGraph): Unit = {
      val pipelinesByCluster =
        graph.pipelines.groupBy(p => if (p.cluster.nonEmpty) Some(p.cluster) else None)

      pipelinesByCluster.foreach {
        case (Some(clusterName), pipelines) => renderCluster(builder, clusterName, pipelines, 1)
        case (None, pipelines)              => pipelines.foreach(renderPipelineNode(builder, _, 1))
      }

      val clusteredDataSources = graph.pipelines
        .filter(_.cluster.nonEmpty)
        .flatMap(p => p.input_sources ++ p.output_sources)
        .toSet
      graph.datasources
        .filterNot(clusteredDataSources.contains)
        .foreach(renderDataSource(builder, _, 1))

      builder.append("\n")

      graph.edges.foreach { e =>
        val style =
          if (e.isDependency) """[color="#ff6b35", style="solid"]""" else """[color="#666"]"""
        builder.append(s"""    "${e.from}" -> "${e.to}" $style;\n""")
      }
    }

    private def generateMermaidGraph(graph: LineageGraph): String = {
      val builder = new StringBuilder
      builder.append("graph LR\n")
      renderMermaidStyles(builder)
      renderMermaidContent(builder, graph)
      renderMermaidClasses(builder, graph)
      builder.toString
    }

    private def renderMermaidStyles(builder: StringBuilder): Unit = {
      builder.append(
        "    classDef pipeline fill:#e1f5fe,stroke:#01579b,stroke-width:2px,color:#000\n"
      )
      builder.append(
        "    classDef dataSource fill:#f3e5f5,stroke:#4a148c,stroke-width:2px,color:#000\n"
      )
      builder.append(
        "    classDef cluster fill:#e8f5e8,stroke:#2e7d32,stroke-width:2px,color:#000\n\n"
      )
    }

    private def renderMermaidContent(builder: StringBuilder, graph: LineageGraph): Unit = {
      val pipelinesByCluster =
        graph.pipelines.groupBy(p => if (p.cluster.nonEmpty) Some(p.cluster) else None)

      pipelinesByCluster.foreach {
        case (Some(clusterName), pipelines) => renderMermaidCluster(builder, clusterName, pipelines)
        case (None, pipelines)              => pipelines.foreach(renderMermaidPipeline(builder, _))
      }

      graph.datasources.foreach(renderMermaidDataSource(builder, _))
      builder.append("\n")

      var linkIndex = 0
      graph.edges.foreach { edge =>
        renderMermaidEdge(builder, edge, linkIndex)
        linkIndex += 1
      }
    }

    private def renderMermaidCluster(
      builder: StringBuilder,
      clusterName: String,
      pipelines: Seq[LineageNode]
    ): Unit = {
      val clusterId = sanitizeId(clusterName)
      builder.append(s"""    subgraph $clusterId ["$clusterName"]\n""")
      pipelines.foreach(p => renderMermaidPipeline(builder, p, "        "))
      builder.append("    end\n\n")
    }

    private def renderMermaidPipeline(
      builder: StringBuilder,
      p: LineageNode,
      indent: String = "    "
    ): Unit = {
      val nodeId = sanitizeId(p.name)
      val label  = if (p.schedule.nonEmpty) s"${p.name}<br/>(${p.schedule})" else p.name
      builder.append(s"""${indent}$nodeId["$label"]\n""")
    }

    private def renderMermaidDataSource(builder: StringBuilder, ds: String): Unit = {
      val nodeId = sanitizeId(ds)
      builder.append(s"""    $nodeId(["$ds"])\n""")
    }

    private def renderMermaidEdge(builder: StringBuilder, e: LineageEdge, linkIndex: Int): Unit = {
      val fromId = sanitizeId(e.from)
      val toId   = sanitizeId(e.to)
      if (e.isDependency) {
        builder.append(s"    $fromId -.-> $toId\n")
        builder.append(s"    linkStyle $linkIndex stroke:#ff6b35,stroke-width:2px\n")
      } else {
        builder.append(s"    $fromId --> $toId\n")
      }
    }

    private def renderMermaidClasses(builder: StringBuilder, graph: LineageGraph): Unit = {
      builder.append("\n")
      graph.pipelines.foreach(p => builder.append(s"    class ${sanitizeId(p.name)} pipeline\n"))
      graph.datasources.foreach(ds => builder.append(s"    class ${sanitizeId(ds)} dataSource\n"))
    }

    private def sanitizeId(name: String): String = name.replaceAll("[^a-zA-Z0-9]", "_")

    private def collectEdges(lineages: Seq[Lineage]): List[LineageEdge] = {
      val dataEdges = lineages.flatMap { lineage =>
        val inputEdges  = lineage.inputs.map(input => LineageEdge(input, lineage.name))
        val outputEdges = lineage.outputs.map(output => LineageEdge(lineage.name, output))
        inputEdges ++ outputEdges
      }

      val pipelineOutputs = lineages.map(l => (l.name, l.outputs)).toMap
      val implicitDependencyEdges = lineages.flatMap { lineage =>
        lineage.inputs.flatMap { input =>
          pipelineOutputs.collectFirst {
            case (pipelineName, outputs) if outputs.contains(input) =>
              LineageEdge(pipelineName, lineage.name, isDependency = true)
          }
        }
      }

      val explicitDependencyEdges = lineages.flatMap { lineage =>
        lineage.upstreams.flatMap { upstreamObj =>
          extractPipelineName(upstreamObj).map { upstreamName =>
            LineageEdge(upstreamName, lineage.name, isDependency = true)
          }
        }
      }

      (dataEdges ++ implicitDependencyEdges ++ explicitDependencyEdges).toList.distinct
    }

    private def renderCluster(
      builder: StringBuilder,
      clusterName: String,
      pipelines: Seq[LineageNode],
      indent: Int
    ): Unit = {
      val ind           = "    " * indent
      val sanitizedName = clusterName.replaceAll("[^a-zA-Z0-9_]", "_")

      builder.append(s"${ind}subgraph cluster_$sanitizedName {\n")
      builder.append(s"""${ind}    label="$clusterName";\n""")
      builder.append(s"""${ind}    style="dotted";\n""")
      builder.append(s"""${ind}    color="#666666";\n""")
      builder.append(s"${ind}    fontsize=11;\n\n")

      pipelines.foreach { pipeline =>
        renderPipelineNode(builder, pipeline, indent + 1)

        val clusterDataSources = (pipeline.input_sources ++ pipeline.output_sources).distinct
        clusterDataSources.foreach { ds =>
          renderDataSource(builder, ds, indent + 1)
        }
      }

      builder.append(s"${ind}}\n\n")
    }

    private def renderPipelineNode(
      builder: StringBuilder,
      pipeline: LineageNode,
      indent: Int
    ): Unit = {
      val ind = "    " * indent
      val scheduleLabel = if (pipeline.schedule.nonEmpty) {
        "<BR/><FONT POINT-SIZE=\"9\" COLOR=\"#d63384\"><I>" + pipeline.schedule + "</I></FONT>"
      } else {
        ""
      }

      builder.append(s"""${ind}"${pipeline.name}" [shape=box, style="filled,rounded",""" + "\n")
      builder.append(
        s"""${ind}    fillcolor="#e3f2fd", color="#1976d2", fontname="Arial Bold",""" + "\n"
      )
      builder.append(ind + "    label=<" + pipeline.name + scheduleLabel + ">];\n")
    }

    private def renderDataSource(
      builder: StringBuilder,
      name: String,
      indent: Int
    ): Unit = {
      val ind = "    " * indent
      builder.append(s"""${ind}"$name" [shape=ellipse, style=filled,""" + "\n")
      builder.append(s"""${ind}    fillcolor="#f3e5f5", color="#7b1fa2", fontsize=10];""" + "\n")
    }
  }

}
