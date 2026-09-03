/**
 * A lightweight, zero-dep library for writing whiteboard-style dataflows
 * using the core [[Node]] and [[Reader]] abstractions.
 *
 * Compose pipelines with the overloaded `~>` operator.
 */
package object etl4s {
  import scala.language.implicitConversions
  import scala.concurrent.{Future, ExecutionContext}
  import scala.concurrent.duration._
  import scala.concurrent.Await
  import scala.util.{Try, Success, Failure}
  import scala.util.control.NonFatal
  import scala.collection.mutable

  /**
   * The core abstraction of etl4s: a composable wrapper around a function `A => B`.
   *
   * Node represents a single step in an ETL pipeline and provides a rich set of
   * combinators for composition, error handling, and parallel execution.
   *
   * @tparam A the input type
   * @tparam B the output type
   */
  sealed trait Node[-A, +B] {

    /**
     * The compiled function this node folds down to.
     *
     * A Node is a reified data structure. Every structural combinator (`~>`, `&`, `&>`, `>>`, `map`, `flatMap`, ...)
     * builds a tree, so the whole graph stays introspectable via [[stages]] / [[mermaid]].
     * [[Node.interpret]] interprets that tree to a function
     */
    final lazy val f: A => B = Node.interpret(this)

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
    def metadata: Any = None

    /**
     * Optional lineage information for data pipeline visualization.
     * Documents inputs, outputs, scheduling, and organization.
     */
    def getLineage: Option[Lineage] = None

    /**
     * The short binding name of a leaf node (the enclosing `val`/`def`), if any.
     * Composite nodes (`~>`, `&`, ...) have no single name and return `None`.
     */
    def getName: Option[String] = this match {
      case Node.Step(n, _, _, _, _) => Some(n)
      case _                        => None
    }

    /**
     * The fully-qualified path of a leaf node's binding, including the enclosing
     * package/object/class chain (e.g. "myapp.jobs.UserPipeline.parse"), captured
     * at compile time. `None` for composites or when no path was captured.
     */
    def getFullName: Option[String] = this match {
      case Node.Step(_, _, _, _, fn) if fn.nonEmpty => Some(fn)
      case _                                        => None
    }

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
    def unsafeRun()(using ev: Any <:< A): B =
      unsafeRun(null.asInstanceOf[A])

    /**
     * Runs the node without any error handling.
     *
     * @param a the input value
     * @return the transformed output
     * @throws any exception thrown by the underlying function
     */
    def unsafeRun(a: A): B =
      f(a)

    /**
     * Runs the node and measures its execution time.
     *
     * @param a the input value
     * @return Trace containing the result and elapsed time
     */
    def unsafeRunTrace(a: A): Trace[B] = {
      val startTime = System.currentTimeMillis()
      val result    = f(a)
      Trace(result, System.currentTimeMillis() - startTime)
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
    def withMetadata(meta: Any): Node[A, B] = this match {
      case d: Node.Decorated[a, b] => d.copy(meta = meta)
      case other                   => Node.Decorated(other, meta, this.getLineage)
    }

    /**
     * Attaches lineage information to this node.
     *
     * @param lin the lineage to attach
     * @return a new Node with the attached lineage
     */
    def withLineage(lin: Lineage): Node[A, B] = this match {
      case d: Node.Decorated[a, b] => d.copy(lin = Some(lin))
      case other                   => Node.Decorated(other, this.metadata, Some(lin))
    }

    /**
     * Overrides the introspection name of a leaf node. Naming precedence for a
     * leaf is: the enclosing `val`/`def` name (auto-captured) -> `.withName`
     * -> "???". On composite nodes this is a no-op.
     */
    final def withName(name: String): Node[A, B] = this match {
      case s: Node.Step[a, b] => s.copy(name = name)
      case other              => other
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
     * doubledNode("hello")
     * }}}
     */
    def map[C](g: B => C): Node[A, C] = Node.Mapped(this, g)

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
     * process("5")
     * }}}
     */
    def flatMap[A1 <: A, C](g: B => Node[A1, C]): Node[A1, C] = Node.FlatMap(this, g)

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
      val result: Node[A, C] = Node.AndThen(this, next)
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
    def >>[A1 <: A, C](next: Node[A1, C]): Node[A1, C] = {
      val combined = (this.getLineage, next.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[A1, C] = Node.Then(this, next)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Side effect composition with a Reader-wrapped node.
     */
    def >>[T, A1 <: A, C](next: Reader[T, Node[A1, C]]): Reader[T, Node[A1, C]] = {
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
     * val getAll = getName & getAge & getEmail  // returns (String, Int, String)
     * }}}
     */
    def &[A1 <: A, C, O <: Tuple](that: Node[A1, C])(using
      ta: TupleAppend.Aux[B, C, O]
    ): Node[A1, O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[A1, O] = Node.Par(this, that, false, ta.append)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Parallel composition with a Reader-wrapped node.
     */
    def &[T, A1 <: A, C, O <: Tuple](
      that: Reader[T, Node[A1, C]]
    )(using ta: TupleAppend.Aux[B, C, O]): Reader[T, Node[A1, O]] = {
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
     * Concurrency is realized by the effect runner: under `as[Future]`/`as[IO]`
     * the two branches run via [[Effect]]`#both`. The plain synchronous
     * `unsafeRun` (the `Id` interpreter) has no threads, so it runs them
     * sequentially
     *
     * @tparam C the output type of the other node
     * @param that the other node to run concurrently
     * @return a new Node that returns a tuple of both results
     *
     * @example
     * {{{
     * val fetchUser = Node[UserId, User](id => fetchFromDB(id))
     * val fetchPrefs = Node[UserId, Preferences](id => fetchPrefsFromCache(id))
     * val fetchBoth = fetchUser &> fetchPrefs
     * val fetchAll = fetchUser &> fetchPrefs &> fetchSettings
     * }}}
     */
    def &>[A1 <: A, C, O <: Tuple](
      that: Node[A1, C]
    )(using ta: TupleAppend.Aux[B, C, O]): Node[A1, O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[A1, O] = Node.Par(this, that, true, ta.append)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Concurrent parallel composition with a Reader-wrapped node.
     */
    def &>[T, A1 <: A, C, O <: Tuple](
      that: Reader[T, Node[A1, C]]
    )(using ta: TupleAppend.Aux[B, C, O]): Reader[T, Node[A1, O]] = {
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
     * Product composition: pairs two pipelines that have different inputs into
     * one block whose requirement is a tuple of both inputs.
     *
     * Unlike `&`/`&>` (which broadcast a single shared input), `*` feeds `_._1` to the left and
     * `_._2` to the right: `Node[A, B] * Node[C, D] => Node[(A, C), (B, D)]`
     *
     * @example
     * {{{
     * val parseName = Node[String, Name](Name(_))
     * val parseAge  = Node[Int, Age](Age(_))
     * val both      = parseName * parseAge   // Node[(String, Int), (Name, Age)]
     * both.unsafeRun(("alice", 30))
     * }}}
     */
    def *[C, D, O <: Tuple](that: Node[C, D])(using
      ta: TupleAppend.Aux[B, D, O]
    ): Node[(A, C), O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[(A, C), O] = Node.Prod(this, that, false, ta.append)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Concurrent product composition: like `*`, but marks the two independent
     * branches to run concurrently under an effect runner (via [[Effect]]).
     * The plain synchronous `unsafeRun` runs them sequentially.
     */
    def *>[C, D, O <: Tuple](
      that: Node[C, D]
    )(using ta: TupleAppend.Aux[B, D, O]): Node[(A, C), O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[(A, C), O] = Node.Prod(this, that, true, ta.append)
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
    def tap(g: B => Any): Node[A, B] = Node.Tap(this, g)

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
     *   .onFailure(_ => 0) // return 0 for invalid strings
     * 
     * parseNumber("123")
     * parseNumber("abc")
     * }}}
     */
    def onFailure[BB >: B](handler: Throwable => BB): Node[A, BB] =
      Node.Recover(this, handler)

    /**
     * `l | r` : fan-in. Routes an `Either` input to the matching branch (`Left`
     * to this node, `Right` to `right`), merging both to a common output.
     *
     * @example
     * {{{
     * val fromInt = Node[Int, String](i => s"int:$i")
     * val fromStr = Node[String, String](s => s"str:$s")
     * val merged  = fromInt | fromStr // Node[Either[Int, String], String]
     * }}}
     */
    def |[A2, BB >: B](right: Node[A2, BB]): Node[Either[A, A2], BB] =
      Node.Fanin(this, right)

    /** Fan-in with a Reader-wrapped node. */
    def |[T, A2, BB >: B](right: Reader[T, Node[A2, BB]]): Reader[T, Node[Either[A, A2], BB]] = {
      val combined = (this.getLineage, right.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = right.map(this | _)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * `l + r` : choice. Routes an `Either` input through independent branches,
     * preserving the `Either` on the way out (`Either[A, C] => Either[B, D]`).
     *
     * @example
     * {{{
     * val dbl = Node[Int, Int](_ * 2)
     * val up  = Node[String, String](_.toUpperCase)
     * val ch  = dbl + up // Node[Either[Int, String], Either[Int, String]]
     * }}}
     */
    def +[C, D](right: Node[C, D]): Node[Either[A, C], Either[B, D]] =
      Node.Fanin(this.map((b: B) => Left[B, D](b)), right.map((d: D) => Right[B, D](d)))

    /** Choice with a Reader-wrapped node. */
    def +[T, C, D](right: Reader[T, Node[C, D]]): Reader[T, Node[Either[A, C], Either[B, D]]] = {
      val combined = (this.getLineage, right.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = right.map(this + _)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * `l <|> r` : fall back to `right` on the same input if this node raises.
     * Unlike [[onFailure]] (a `Throwable => B`), the alternative is a full node
     * that re-runs on the original input.
     *
     * @example
     * {{{
     * val primary  = Node[String, Int](_.toInt)
     * val fallback = Node[String, Int](_ => 0)
     * val safe     = primary <|> fallback
     * safe("7")    // 7
     * safe("oops") // 0
     * }}}
     */
    def <|>[A1 <: A, BB >: B](right: Node[A1, BB]): Node[A1, BB] =
      Node.OrElse(this, right)

    /** Error fallback with a Reader-wrapped node. */
    def <|>[T, A1 <: A, BB >: B](right: Reader[T, Node[A1, BB]]): Reader[T, Node[A1, BB]] = {
      val combined = (this.getLineage, right.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = right.map(this <|> _)
      combined.fold(result)(lin => result.withLineage(lin))
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
    ): Node[A, B] = Node.Retry(this, maxAttempts, initialDelayMs, backoffFactor)

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
    def runAsync(using ec: ExecutionContext): A => Future[B] = a => Future(f(a))

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
     * val combined = (node1 & node2) & node3 // Node[String, ((Int, String), Boolean)]
     * val flattened = combined.zip // Node[String, (Int, String, Boolean)]
     * }}}
     */
    def zip[BB >: B, Out](using flattener: Flatten.Aux[BB, Out]): Node[A, Out] =
      Node.Mapped(this, (b: BB) => flattener(b))

    /**
     * The pipeline as a flat list of leaf stages (name + in/out type names),
     * in execution order.
     *
     * For diagrams use `.toMermaid` / `.toDot`, which render this node's internal
     * stage structure. (On a `Seq` of nodes/readers those same names render the
     * declared data-lineage graph instead; see [[LineageCollectionOps]].)
     */
    def stages: List[Node.StageInfo] = Node.stages(this)
  }

  /**
   * Layout direction for the `toDot` / `toMermaid` structural diagrams.
   * Maps to Graphviz `rankdir` and Mermaid `flowchart` orientation.
   */
  sealed abstract class Direction(val code: String)
  object Direction {
    case object LR extends Direction("LR") // left  -> right
    case object RL extends Direction("RL") // right -> left
    case object TB extends Direction("TB") // top   -> bottom
    case object BT extends Direction("BT") // bottom -> top
  }

  /** Node companion object with factory methods */
  object Node {

    /**
     * Creates a node from a function A => B.
     */
    def apply[A, B](
      func: A => B
    )(using name: Name, inN: TypeName[A], outN: TypeName[B]): Node[A, B] =
      Step(name.value, func, inN, outN, name.fullName)

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
    def apply[B](value: => B)(using name: Name, outN: TypeName[B]): Node[Any, B] =
      Node((_: Any) => value)

    def identity[A](using name: Name, tn: TypeName[A]): Node[A, A] =
      Node(a => a)
    def unit[B](value: => B)(using name: Name, outN: TypeName[B]): Node[Unit, B] =
      Node(_ => value)
    def effect(action: => Unit)(using name: Name): Node[Unit, Unit] =
      Node(_ => action)
    def pure[A, B](b: B)(using name: Name, inN: TypeName[A], outN: TypeName[B]): Node[A, B] =
      Node(_ => b)

    def requires[T, A, B](
      f: T => A => B
    )(using name: Name, inN: TypeName[A], outN: TypeName[B]): Reader[T, Node[A, B]] = {
      Reader(
        config => Step(name.value, f(config), inN, outN, name.fullName),
        sourceName = Some(name)
      )
    }

    /** A leaf: an opaque `A => B` with its captured name and type names */
    final case class Step[A, B](
      name: String,
      run: A => B,
      inN: TypeName[A],
      outN: TypeName[B],
      fullName: String = ""
    ) extends Node[A, B]

    /** `a ~> b` : sequential composition */
    final case class AndThen[A, B, C](first: Node[A, B], second: Node[B, C]) extends Node[A, C]

    /**
     * `a & b` (sequential) / `a &> b` (concurrent) shared-input fan-out
     */
    final case class Par[A, B, C, O](
      left: Node[A, B],
      right: Node[A, C],
      concurrent: Boolean,
      append: (B, C) => O
    ) extends Node[A, O]

    /**
     * `a * b` (sequential) / `a *> b` (concurrent) product composition
     */
    final case class Prod[A, C, B, D, O](
      left: Node[A, B],
      right: Node[C, D],
      concurrent: Boolean,
      append: (B, D) => O
    ) extends Node[(A, C), O]

    /**
     * `each` / `eachPar` : apply `inner` to every element of a batch,
     * preserving the collection type
     */
    final case class Batch[CA, A, B, CB](
      inner: Node[A, B],
      parallelism: Int,
      toSeq: CA => Seq[A],
      fromSeq: Seq[B] => CB
    ) extends Node[CA, CB]

    /**
     * `a.ensure(...)` / `a.ensurePar(...)` reified input/output/change
     * validation around `inner`. Each check is `V => Option[String]` (None = ok)
     */
    final case class Validate[A, B](
      inner: Node[A, B],
      input: Seq[A => Option[String]],
      output: Seq[B => Option[String]],
      change: Seq[((A, B)) => Option[String]],
      concurrent: Boolean
    ) extends Node[A, B]

    /** `a >> b` : run `a` for effect, then `b` on the same input ... keep `b` */
    final case class Then[A, B, C](first: Node[A, B], second: Node[A, C]) extends Node[A, C]

    /** `a.map(g)` / `a.zip` : transform the output */
    final case class Mapped[A, B, C](inner: Node[A, B], g: B => C) extends Node[A, C]

    /** `a.tap(g)` : peek at the output, pass it through unchanged */
    final case class Tap[A, B](inner: Node[A, B], g: B => Any) extends Node[A, B]

    /** `a.onFailure(h)` : fall back to `h(t)` on any throwable */
    final case class Recover[A, B](inner: Node[A, B], handler: Throwable => B) extends Node[A, B]

    /** `a.withRetry(...)` : retry with exponential backoff */
    final case class Retry[A, B](
      inner: Node[A, B],
      maxAttempts: Int,
      initialDelayMs: Long,
      backoffFactor: Double
    ) extends Node[A, B]

    final case class FlatMap[A, B, C](src: Node[A, B], k: B => Node[A, C]) extends Node[A, C]

    /**
     * `If(...) / .ElseIf(...) / .Else(...)` reified conditional branching.
     * Each branch predicate is wrapped in a [[Predicate]] so its source text
     * (captured at compile time) can be used as a diagram label.
     */
    final case class Cond[A, B, C](
      source: Node[A, B],
      branches: List[(Predicate[B], Node[B, C])],
      default: Option[Node[B, C]]
    ) extends Node[A, C]

    /**
     * `l | r` : fan-in. Routes an `Either` input to the matching branch and
     * merges both to a common output `O` (`Either[LA, RA] => O`).
     */
    final case class Fanin[LA, RA, O](
      left: Node[LA, O],
      right: Node[RA, O]
    ) extends Node[Either[LA, RA], O]

    /** `l <|> r` : run `left`; if it raises, run `right` on the same input */
    final case class OrElse[A, B](left: Node[A, B], right: Node[A, B]) extends Node[A, B]

    /** Carries metadata / lineage without disturbing the structural fold */
    final case class Decorated[A, B](
      inner: Node[A, B],
      meta: Any,
      lin: Option[Lineage]
    ) extends Node[A, B] {
      override def metadata: Any               = meta
      override def getLineage: Option[Lineage] = lin
    }

    /** Raises a `ValidationException` collecting `errors` for a `Validate` stage */
    private[etl4s] def raiseIfInvalid(stage: String, errors: Seq[String]): Unit =
      if (errors.nonEmpty)
        throw new ValidationException(
          s"$stage validation failed:\n${errors.map(e => s"  - $e").mkString("\n")}"
        )

    private[etl4s] def interpret[A, B](node: Node[A, B]): A => B = node match {
      case Step(_, run, _, _, _) => run
      case AndThen(x, y)         => interpret(x).andThen(interpret(y))
      case Par(l, r, _, app)     =>
        val lf = interpret(l)
        val rf = interpret(r)
        (a: A) => app(lf(a), rf(a))
      case p: Prod[a, c, b, d, o] =>
        val lf                 = interpret(p.left)
        val rf                 = interpret(p.right)
        val app                = p.append
        val run: ((a, c)) => o = (in: (a, c)) => app(lf(in._1), rf(in._2))
        run.asInstanceOf[A => B]
      case b: Batch[ca, a, bb, cb] =>
        val innerF        = interpret(b.inner)
        val run: ca => cb = (in: ca) => b.fromSeq(b.toSeq(in).map(innerF))
        run.asInstanceOf[A => B]
      case v: Validate[a, b] =>
        val cf          = interpret(v.inner)
        val run: a => b = (in: a) => {
          raiseIfInvalid("Input", v.input.flatMap(c => c(in)))
          val out = cf(in)
          raiseIfInvalid("Output", v.output.flatMap(c => c(out)))
          raiseIfInvalid("Change", v.change.flatMap(c => c((in, out))))
          out
        }
        run.asInstanceOf[A => B]
      case Then(x, y) =>
        val xf = interpret(x)
        val yf = interpret(y)
        (a: A) => { xf(a); yf(a) }
      case Mapped(inner, g) => interpret(inner).andThen(g)
      case Tap(inner, g)    =>
        val cf = interpret(inner)
        (a: A) => { val b = cf(a); g(b); b }
      case Recover(inner, h) =>
        val cf = interpret(inner)
        (a: A) =>
          try cf(a)
          catch { case t: Throwable => h(t) }
      case Retry(inner, max, delay0, factor) =>
        val cf = interpret(inner)
        (a: A) => {
          def attempt(remaining: Int, delay: Long): B =
            try cf(a)
            catch {
              case _: Throwable if remaining > 1 =>
                Platform.sleep(delay)
                attempt(remaining - 1, (delay * factor).toLong)
            }
          attempt(max, delay0)
        }
      case FlatMap(s, k) =>
        val sf = interpret(s)
        (a: A) => interpret(k(sf(a)))(a)
      case f: Fanin[la, ra, o] =>
        val lf = interpret(f.left)
        val rf = interpret(f.right)
        (
          (e: Either[la, ra]) =>
            e match {
              case Left(x)  => lf(x)
              case Right(y) => rf(y)
            }
        ).asInstanceOf[A => B]
      case OrElse(l, r) =>
        val lf = interpret(l)
        val rf = interpret(r)
        (a: A) =>
          try lf(a)
          catch { case _: Throwable => rf(a) }
      case Cond(source, branches, default) =>
        val sf  = interpret(source)
        val bfs = branches.map { case (p, n) => (p.f, interpret(n)) }
        val dfO = default.map(interpret)
        (a: A) => {
          val b = sf(a)
          bfs.find(_._1(b)) match {
            case Some((_, nf)) => nf(b)
            case None          =>
              dfO match {
                case Some(df) => df(b)
                case None     => b.asInstanceOf[B] // pass-through (B <: output)
              }
          }
        }
      case Decorated(inner, _, _) => interpret(inner)
    }

    /* Run in an effect F
     * The same reified tree folded into `A => F[B]` for any `F` with a
     * [[Effect]] instance (`Id`/`Future`/`Try` shipped; anything else is one
     * user `given`)
     */
    private[etl4s] def interpretF[F[_], A, B](node: Node[A, B])(using E: Effect[F]): A => F[B] =
      node match {
        case Step(_, run, _, _, _) => (a: A) => E.delay(run(a))
        case AndThen(x, y)         =>
          val xf = interpretF[F, Any, Any](x.asInstanceOf[Node[Any, Any]])
          val yf = interpretF[F, Any, Any](y.asInstanceOf[Node[Any, Any]])
          ((a: A) => E.flatMap(xf(a))(yf)).asInstanceOf[A => F[B]]
        case p: Par[a, b, c, o] =>
          val lf  = interpretF[F, a, b](p.left)
          val rf  = interpretF[F, a, c](p.right)
          val app = p.append
          (
            (in: a) =>
              if (p.concurrent) E.map(E.both(lf(in), rf(in)))(bc => app(bc._1, bc._2))
              else E.flatMap(lf(in))(bv => E.map(rf(in))(cv => app(bv, cv)))
          ).asInstanceOf[A => F[B]]
        case p: Prod[a, c, b, d, o] =>
          val lf  = interpretF[F, a, b](p.left)
          val rf  = interpretF[F, c, d](p.right)
          val app = p.append
          (
            (in: (a, c)) =>
              if (p.concurrent) E.map(E.both(lf(in._1), rf(in._2)))(bd => app(bd._1, bd._2))
              else E.flatMap(lf(in._1))(bv => E.map(rf(in._2))(dv => app(bv, dv)))
          ).asInstanceOf[A => F[B]]
        case b: Batch[ca, a, bb, cb] =>
          val innerF = interpretF[F, a, bb](b.inner)
          val par    = math.max(1, b.parallelism)

          /** Elements within a group run concurrently via `both`; groups
            *  sequence via flatMap, which bounds parallelism to `par` for eager effects (e.g. Future)
            */
          def group(fbs: List[F[bb]]): F[List[bb]] = fbs match {
            case Nil       => E.pure(Nil)
            case x :: Nil  => E.map(x)(List(_))
            case x :: rest => E.map(E.both(x, group(rest)))(t => t._1 :: t._2)
          }
          def runGroups(gs: List[Seq[a]]): F[List[bb]] = gs match {
            case Nil       => E.pure(Nil)
            case g :: rest =>
              E.flatMap(group(g.toList.map(innerF)))(head =>
                E.map(runGroups(rest))(tail => head ++ tail)
              )
          }
          ((in: ca) => E.map(runGroups(b.toSeq(in).grouped(par).toList))(bs => b.fromSeq(bs)))
            .asInstanceOf[A => F[B]]
        case v: Validate[a, b] =>
          val cf   = interpretF[F, a, b](v.inner)
          val conc = v.concurrent

          /** Checks in a stage run concurrently via `both` when `conc`, else
            * sequentially; a stage fails via `delay(throw ...)` (an F-failure)
            */
          def collect(fos: List[F[Option[String]]]): F[List[Option[String]]] =
            fos match {
              case Nil       => E.pure(Nil)
              case x :: Nil  => E.map(x)(List(_))
              case x :: rest =>
                if (conc) E.map(E.both(x, collect(rest)))(t => t._1 :: t._2)
                else E.flatMap(x)(o => E.map(collect(rest))(o :: _))
            }
          def guard[T](stage: String, checks: Seq[T => Option[String]], t: T): F[Unit] =
            if (checks.isEmpty) E.pure(())
            else
              E.flatMap(collect(checks.toList.map(c => E.delay(c(t)))))(os =>
                E.delay(raiseIfInvalid(stage, os.flatten))
              )
          (
            (in: a) =>
              E.flatMap(guard("Input", v.input, in))(_ =>
                E.flatMap(cf(in))(out =>
                  E.flatMap(guard("Output", v.output, out))(_ =>
                    E.map(guard[(a, b)]("Change", v.change, (in, out)))(_ => out)
                  )
                )
              )
          ).asInstanceOf[A => F[B]]
        case Then(x, y) =>
          val xf = interpretF[F, Any, Any](x.asInstanceOf[Node[Any, Any]])
          val yf = interpretF[F, Any, Any](y.asInstanceOf[Node[Any, Any]])
          ((a: A) => E.flatMap(xf(a))(_ => yf(a))).asInstanceOf[A => F[B]]
        case Mapped(inner, g) =>
          val cf = interpretF[F, Any, Any](inner.asInstanceOf[Node[Any, Any]])
          (a: A) => E.map(cf(a))(g.asInstanceOf[Any => B])
        case t: Tap[a, b] =>
          val cf = interpretF[F, a, b](t.inner)
          val g  = t.g
          ((in: a) => E.flatMap(cf(in))(bv => E.map(E.delay(g(bv)))(_ => bv)))
            .asInstanceOf[A => F[B]]
        case Recover(inner, h) =>
          val cf = interpretF[F, A, B](inner.asInstanceOf[Node[A, B]])
          (a: A) => E.handleErrorWith(cf(a))(t => E.pure(h(t)))
        case Retry(inner, max, delay0, factor) =>
          val cf = interpretF[F, A, B](inner.asInstanceOf[Node[A, B]])
          (a: A) => {
            def attempt(remaining: Int, d: Long): F[B] =
              if (remaining <= 1) cf(a)
              else
                E.handleErrorWith(cf(a)) { _ =>
                  Platform.sleep(d); attempt(remaining - 1, (d * factor).toLong)
                }
            attempt(max, delay0)
          }
        case FlatMap(s, k) =>
          val sf = interpretF[F, A, Any](s.asInstanceOf[Node[A, Any]])
          (a: A) =>
            E.flatMap(sf(a))(b => interpretF[F, A, B](k.asInstanceOf[Any => Node[A, B]](b))(a))
        case f: Fanin[la, ra, o] =>
          val lf = interpretF[F, la, o](f.left)
          val rf = interpretF[F, ra, o](f.right)
          (
            (e: Either[la, ra]) =>
              e match {
                case Left(x)  => lf(x)
                case Right(y) => rf(y)
              }
          ).asInstanceOf[A => F[B]]
        case OrElse(l, r) =>
          val lf = interpretF[F, A, B](l)
          val rf = interpretF[F, A, B](r)
          (a: A) => E.handleErrorWith(lf(a))(_ => rf(a))
        case Cond(source, branches, default) =>
          val sf  = interpretF[F, A, Any](source.asInstanceOf[Node[A, Any]])
          val bfs = branches
            .asInstanceOf[List[(Predicate[Any], Node[Any, B])]]
            .map { case (p, n) => (p.f, interpretF[F, Any, B](n)) }
          val dfO = default.asInstanceOf[Option[Node[Any, B]]].map(interpretF[F, Any, B](_))
          (a: A) =>
            E.flatMap(sf(a)) { b =>
              bfs.find(_._1(b)) match {
                case Some((_, nf)) => nf(b)
                case None          =>
                  dfO match {
                    case Some(df) => df(b)
                    case None     => E.pure(b.asInstanceOf[B])
                  }
              }
            }
        case Decorated(inner, _, _) =>
          interpretF[F, A, B](inner.asInstanceOf[Node[A, B]])
      }

    /**
     * The effect a pipeline was asked to run in (via `node.compile[F]`). Its
     * `unsafeRun` folds the graph through [[interpretF]] into `F`
     */
    final class Runner[A, B, F[_]](node: Node[A, B])(using E: Effect[F]) {

      /** Runs the pipeline on `a`, producing an `F[B]` */
      def unsafeRun(a: A): F[B] = interpretF[F, A, B](node)(a)

      /** Runs an input-free pipeline (`Node[Any, B]`), producing an `F[B]` */
      def unsafeRun()(using ev: Any <:< A): F[B] =
        interpretF[F, A, B](node)(null.asInstanceOf[A])
    }

    /**
     * One leaf stage of a pipeline: its name, in/out type names, and the
     * fully-qualified path of its binding (empty when uncaptured/synthetic).
     */
    final case class StageInfo(name: String, in: String, out: String, fullName: String = "")

    /**
     * Flattens the tree to its leaf stages, in execution order.
     */
    def stages(node: Node[?, ?]): List[StageInfo] = node match {
      case Step(name, _, inN, outN, fn)    => List(StageInfo(name, inN.show, outN.show, fn))
      case AndThen(x, y)                   => stages(x) ++ stages(y)
      case Par(l, r, _, _)                 => stages(l) ++ stages(r)
      case Prod(l, r, _, _)                => stages(l) ++ stages(r)
      case Batch(inner, _, _, _)           => stages(inner)
      case Validate(inner, _, _, _, _)     => stages(inner)
      case Then(x, y)                      => stages(x) ++ stages(y)
      case Mapped(inner, _)                => stages(inner)
      case Tap(inner, _)                   => stages(inner)
      case Recover(inner, _)               => stages(inner)
      case Retry(inner, _, _, _)           => stages(inner)
      case Decorated(inner, _, _)          => stages(inner)
      case FlatMap(s, _)                   => stages(s) :+ StageInfo("<dynamic>", "?", "?")
      case f: Fanin[?, ?, ?]               => stages(f.left) ++ stages(f.right)
      case OrElse(l, r)                    => stages(l) ++ stages(r)
      case Cond(source, branches, default) =>
        stages(source) ++ branches.flatMap(bn => stages(bn._2)) ++ default.toList.flatMap(stages)
    }

    /**
     * Interpreter to render mermaid diagrams. By default, node boxes show only
     * the stage name and each edge is labeled with the wire type at that point.
     * Pass `typesOnNodes = true` to fold the types into the node label instead
     * (node-label style: `name<br/>In => Out`), or `showTypes = false` to drop them
     * entirely.
     */
    def mermaid(
      node: Node[?, ?],
      showTypes: Boolean = true,
      direction: Direction = Direction.LR,
      typesOnNodes: Boolean = false
    ): String = {
      val lines                   = mutable.ArrayBuffer.empty[String]
      var counter                 = 0
      def fresh(): Int            = { val c = counter; counter += 1; c }
      def box(label: String): Int = { val id = fresh(); lines += s"""  n$id["$label"]"""; id }
      /* marker nodes: split/join dot, the `?` diamond, and the boundary anchor */
      def junction(): Int = { val id = fresh(); lines += s"""  n$id(( )):::junction"""; id }
      def decision(): Int = { val id = fresh(); lines += s"""  n$id{"?"}:::decision"""; id }
      def boundary(): Int = { val id = fresh(); lines += s"""  n$id(( )):::anchor"""; id }
      /* `If` leaves an identity Step as its source — we route around it */
      def isSyntheticIdentity(f: Node[?, ?]): Boolean = f match {
        case Step(name, _, inN, outN, fullName) =>
          name == "input" && fullName.isEmpty && inN.show == outN.show
        case _ => false
      }
      val labelEdges = showTypes && !typesOnNodes
      def edge(a: Int, b: Int, label: String = "", dashed: Boolean = false): Unit = {
        val arrow     = if (dashed) "-.->" else "-->"
        val labelPart = if (label.isEmpty) "" else s"|\"$label\"|"
        lines += s"  n$a $arrow$labelPart n$b"
      }

      def go(f: Node[?, ?]): (List[(Int, String)], List[(Int, String)]) = f match {
        case Step(name, _, inN, outN, _) =>
          val label =
            if (showTypes && typesOnNodes) s"$name<br/>${inN.show} &rArr; ${outN.show}"
            else name
          val id = box(label)
          (List((id, inN.show)), List((id, outN.show)))
        case AndThen(x, y) =>
          val (xin, xout) = go(x)
          val (yin, yout) = go(y)
          for ((a, t) <- xout; (b, _) <- yin) edge(a, b, if (labelEdges) t else "")
          (xin, yout)
        case Par(l, r, _, _) =>
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          val marker      = junction()
          for ((b, t) <- lin) edge(marker, b, if (labelEdges) t else "")
          for ((b, t) <- rin) edge(marker, b, if (labelEdges) t else "")
          val inT = lin.headOption.map(_._2).getOrElse("?")
          (List((marker, inT)), lout ++ rout)
        case Prod(l, r, _, _) =>
          /* `*` splits into tuple slots — tag each edge `._1`/`._2` */
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          val marker      = junction()
          for ((b, t) <- lin) edge(marker, b, if (labelEdges) s"._1: $t" else "")
          for ((b, t) <- rin) edge(marker, b, if (labelEdges) s"._2: $t" else "")
          val lT = lin.headOption.map(_._2).getOrElse("?")
          val rT = rin.headOption.map(_._2).getOrElse("?")
          (List((marker, s"($lT, $rT)")), lout ++ rout)
        case Batch(inner, _, _, _)       => go(inner)
        case Validate(inner, _, _, _, _) => go(inner)
        case Then(x, y)                  =>
          /* x is a side-effect tap; y is the real continuation */
          val (xin, _)    = go(x)
          val (yin, yout) = go(y)
          (xin ++ yin, yout)
        case Mapped(inner, _)       => go(inner)
        case Tap(inner, _)          => go(inner)
        case Recover(inner, _)      => go(inner)
        case Retry(inner, _, _, _)  => go(inner)
        case Decorated(inner, _, _) => go(inner)
        case FlatMap(s, _)          =>
          val (sin, sout) = go(s)
          val dyn         = box("&lt;dynamic&gt;<br/>runtime-decided")
          for ((a, t) <- sout) edge(a, dyn, if (labelEdges) t else "", dashed = true)
          (sin, List((dyn, "?")))
        case f: Fanin[?, ?, ?] =>
          /* Either-merge and orElse: only one branch fires, so dash the join */
          val (lin, lout) = go(f.left)
          val (rin, rout) = go(f.right)
          val marker      = junction()
          for ((a, t) <- lout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          for ((a, t) <- rout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          val outT = lout.headOption.map(_._2).getOrElse("?")
          (lin ++ rin, List((marker, outT)))
        case OrElse(l, r) =>
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          val marker      = junction()
          for ((a, t) <- lout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          for ((a, t) <- rout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          val outT = lout.headOption.map(_._2).getOrElse("?")
          (lin ++ rin, List((marker, outT)))
        case Cond(source, branches, default) =>
          /* source routes through the `?` diamond to one branch; no default passes through */
          val branchIO          = (branches.map(_._2) ++ default.toList).map(go)
          val marker            = decision()
          val (sinExpose, srcT) =
            if (isSyntheticIdentity(source)) {
              val t = source match {
                case Step(_, _, inN, _, _) => inN.show
                case _                     => "?"
              }
              (List((marker, t)), t)
            } else {
              val (sin, sout) = go(source)
              for ((a, t) <- sout) edge(a, marker, if (labelEdges) t else "")
              (sin, sout.headOption.map(_._2).getOrElse("?"))
            }
          val branchLabels = branches.zipWithIndex.map { case ((p, _), i) =>
            if (p.source.nonEmpty) p.source else s"#${i + 1}"
          } ++ default.toList.map(_ => "else")
          for (((bin, _), label) <- branchIO.zip(branchLabels); (b, _) <- bin)
            edge(marker, b, if (labelEdges) label else "", dashed = true)
          val branchOuts  = branchIO.flatMap(_._2)
          val passthrough = if (default.isEmpty) List((marker, srcT)) else Nil
          (sinExpose, branchOuts ++ passthrough)
      }
      val (rootIn, rootOut) = go(node)
      if (labelEdges) {
        /* anchors carry the outer pipeline's in/out types */
        for ((b, t) <- rootIn) { val src = boundary(); edge(src, b, t) }
        for ((a, t) <- rootOut) { val sink = boundary(); edge(a, sink, t) }
      }
      lines += "  classDef junction fill:#000,stroke:#000"
      lines += "  classDef decision fill:#fff,stroke:#000,stroke-width:1px,font-size:10px"
      lines += "  classDef anchor fill:#000,stroke:#000"
      (s"flowchart ${direction.code}" +: lines.toList).mkString("\n")
    }

    /**
     * Interpreter to render Graphviz DOT diagrams. Same defaults as `mermaid`:
     * node labels carry only the stage name; edges carry the wire type.
     */
    def dot(
      node: Node[?, ?],
      showTypes: Boolean = true,
      direction: Direction = Direction.LR,
      typesOnNodes: Boolean = false
    ): String = {
      val lines                   = mutable.ArrayBuffer.empty[String]
      var counter                 = 0
      def fresh(): Int            = { val c = counter; counter += 1; c }
      def box(label: String): Int = {
        val id = fresh(); lines += s"""  n$id [label="$label"];"""; id
      }
      def junction(): Int = {
        val id = fresh()
        lines += s"""  n$id [shape=point, width=0.12];"""
        id
      }
      def decision(): Int = {
        val id = fresh()
        lines += s"""  n$id [shape=diamond, label="?", width=0.3, height=0.3, fixedsize=true, fontsize=10];"""
        id
      }
      def boundary(): Int = {
        val id = fresh()
        lines += s"  n$id [shape=point, width=0.08];"
        id
      }
      def isSyntheticIdentity(f: Node[?, ?]): Boolean = f match {
        case Step(name, _, inN, outN, fullName) =>
          name == "input" && fullName.isEmpty && inN.show == outN.show
        case _ => false
      }
      val labelEdges = showTypes && !typesOnNodes
      def edge(a: Int, b: Int, label: String = "", dashed: Boolean = false): Unit = {
        val attrs = List(
          if (dashed) Some("style=dashed") else None,
          if (label.nonEmpty) Some(s"""label="$label"""") else None
        ).flatten
        val suffix = if (attrs.isEmpty) "" else s" [${attrs.mkString(", ")}]"
        lines += s"  n$a -> n$b$suffix;"
      }

      def go(f: Node[?, ?]): (List[(Int, String)], List[(Int, String)]) = f match {
        case Step(name, _, inN, outN, _) =>
          val label =
            if (showTypes && typesOnNodes) s"$name\\n${inN.show} => ${outN.show}"
            else name
          val id = box(label)
          (List((id, inN.show)), List((id, outN.show)))
        case AndThen(x, y) =>
          val (xin, xout) = go(x)
          val (yin, yout) = go(y)
          for ((a, t) <- xout; (b, _) <- yin) edge(a, b, if (labelEdges) t else "")
          (xin, yout)
        case Par(l, r, _, _) =>
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          val marker      = junction()
          for ((b, t) <- lin) edge(marker, b, if (labelEdges) t else "")
          for ((b, t) <- rin) edge(marker, b, if (labelEdges) t else "")
          val inT = lin.headOption.map(_._2).getOrElse("?")
          (List((marker, inT)), lout ++ rout)
        case Prod(l, r, _, _) =>
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          val marker      = junction()
          for ((b, t) <- lin) edge(marker, b, if (labelEdges) s"._1: $t" else "")
          for ((b, t) <- rin) edge(marker, b, if (labelEdges) s"._2: $t" else "")
          val lT = lin.headOption.map(_._2).getOrElse("?")
          val rT = rin.headOption.map(_._2).getOrElse("?")
          (List((marker, s"($lT, $rT)")), lout ++ rout)
        case Batch(inner, _, _, _)       => go(inner)
        case Validate(inner, _, _, _, _) => go(inner)
        case Then(x, y)                  =>
          val (xin, _)    = go(x)
          val (yin, yout) = go(y)
          (xin ++ yin, yout)
        case Mapped(inner, _)       => go(inner)
        case Tap(inner, _)          => go(inner)
        case Recover(inner, _)      => go(inner)
        case Retry(inner, _, _, _)  => go(inner)
        case Decorated(inner, _, _) => go(inner)
        case FlatMap(s, _)          =>
          val (sin, sout) = go(s)
          val dyn         = box("<dynamic>\\nruntime-decided")
          for ((a, t) <- sout) edge(a, dyn, if (labelEdges) t else "", dashed = true)
          (sin, List((dyn, "?")))
        case f: Fanin[?, ?, ?] =>
          val (lin, lout) = go(f.left)
          val (rin, rout) = go(f.right)
          val marker      = junction()
          for ((a, t) <- lout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          for ((a, t) <- rout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          val outT = lout.headOption.map(_._2).getOrElse("?")
          (lin ++ rin, List((marker, outT)))
        case OrElse(l, r) =>
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          val marker      = junction()
          for ((a, t) <- lout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          for ((a, t) <- rout) edge(a, marker, if (labelEdges) t else "", dashed = true)
          val outT = lout.headOption.map(_._2).getOrElse("?")
          (lin ++ rin, List((marker, outT)))
        case Cond(source, branches, default) =>
          val branchIO          = (branches.map(_._2) ++ default.toList).map(go)
          val marker            = decision()
          val (sinExpose, srcT) =
            if (isSyntheticIdentity(source)) {
              val t = source match {
                case Step(_, _, inN, _, _) => inN.show
                case _                     => "?"
              }
              (List((marker, t)), t)
            } else {
              val (sin, sout) = go(source)
              for ((a, t) <- sout) edge(a, marker, if (labelEdges) t else "")
              (sin, sout.headOption.map(_._2).getOrElse("?"))
            }
          val branchLabels = branches.zipWithIndex.map { case ((p, _), i) =>
            if (p.source.nonEmpty) p.source else s"#${i + 1}"
          } ++ default.toList.map(_ => "else")
          for (((bin, _), label) <- branchIO.zip(branchLabels); (b, _) <- bin)
            edge(marker, b, if (labelEdges) label else "", dashed = true)
          val branchOuts  = branchIO.flatMap(_._2)
          val passthrough = if (default.isEmpty) List((marker, srcT)) else Nil
          (sinExpose, branchOuts ++ passthrough)
      }
      val (rootIn, rootOut) = go(node)
      if (labelEdges) {
        for ((b, t) <- rootIn) { val src = boundary(); edge(src, b, t) }
        for ((a, t) <- rootOut) { val sink = boundary(); edge(a, sink, t) }
      }
      (s"digraph G {\n  rankdir=${direction.code};" +: lines.toList).mkString("\n") + "\n}"
    }

    /**
     * Graphviz DOT with fan-outs (`&`/`*`), conditionals (`?`) and joins drawn
     * as dashed `subgraph cluster_*` boxes instead of marker nodes
     */
    def dotClustered(
      node: Node[?, ?],
      showTypes: Boolean = true,
      direction: Direction = Direction.LR
    ): String = {
      /* label overrides the inbound edge text; product marks one slot of a
         `&`/`*` tuple; shared marks a broadcast input */
      final case class Port(
        id: Int,
        tpe: String,
        dashed: Boolean = false,
        label: Option[String] = None,
        product: Boolean = false,
        shared: Boolean = false
      )

      val nodeLabel               = mutable.LinkedHashMap.empty[Int, String]
      val nodeGroup               = mutable.Map.empty[Int, Int]
      val groups                  = mutable.LinkedHashMap.empty[Int, String]
      val edges                   = mutable.ArrayBuffer.empty[(Int, Int, String, Boolean)]
      val boundaries              = mutable.ArrayBuffer.empty[Int]
      var counter                 = 0
      var groupSeq                = 0
      def fresh(): Int            = { val c = counter; counter += 1; c }
      def box(label: String): Int = { val id = fresh(); nodeLabel(id) = label; id }
      def boundary(): Int         = { val id = fresh(); boundaries += id; id }
      /* claim nodes [from, until) into a fresh dashed cluster; innermost wins */
      def group(symbol: String, from: Int, until: Int): Int = {
        val g = groupSeq
        groupSeq += 1
        groups(g) = symbol
        (from until until).foreach(id => if (!nodeGroup.contains(id)) nodeGroup(id) = g)
        g
      }
      val labelEdges                                                     = showTypes
      def emitEdge(a: Int, b: Int, label: String, dashed: Boolean): Unit =
        edges += ((a, b, if (labelEdges) label else "", dashed))

      def isSyntheticIdentity(f: Node[?, ?]): Boolean = f match {
        case Step(name, _, inN, outN, fullName) =>
          name == "input" && fullName.isEmpty && inN.show == outN.show
        case _ => false
      }

      def go(f: Node[?, ?]): (List[Port], List[Port]) = f match {
        case Step(name, _, inN, outN, _) =>
          val id = box(name)
          (List(Port(id, inN.show)), List(Port(id, outN.show)))
        case AndThen(x, y) =>
          val (xin, xout) = go(x)
          val (yin, yout) = go(y)
          /* product slots converging on one consumer: tag `._1`/`._2` */
          if (xout.size > 1 && xout.forall(_.product))
            for ((a, i) <- xout.zipWithIndex; b <- yin)
              emitEdge(a.id, b.id, s"._${i + 1}: ${a.tpe}", a.dashed || b.dashed)
          /* alternatives (`|`/`orElse`/`?`) yield one value... */
          else if (xout.size > 1 && yin.size > 1 && xout.forall(p => p.dashed && !p.product)) {
            val merge = boundary()
            for (a <- xout) emitEdge(a.id, merge, a.tpe, dashed = true)
            for (b <- yin) emitEdge(merge, b.id, b.label.getOrElse(xout.head.tpe), dashed = true)
          } else
            for (a <- xout; b <- yin)
              emitEdge(a.id, b.id, b.label.getOrElse(a.tpe), a.dashed || b.dashed)
          (xin, yout)
        case Par(l, r, concurrent, _) =>
          /* `&`/`&>`: one input broadcast to every branch; outputs are tuple slots */
          val start       = counter
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          group(if (concurrent) "&>" else "&", start, counter)
          ((lin ++ rin).map(_.copy(shared = true)), (lout ++ rout).map(_.copy(product = true)))
        case Prod(l, r, concurrent, _) =>
          /* `*`/`*>`: tag edges `._1`/`._2`; outputs are tuple slots */
          val start       = counter
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          group(if (concurrent) "*>" else "*", start, counter)
          (
            lin.map(p => p.copy(label = Some(s"._1: ${p.tpe}"))) ++
              rin.map(p => p.copy(label = Some(s"._2: ${p.tpe}"))),
            (lout ++ rout).map(_.copy(product = true))
          )
        case Batch(inner, _, _, _)       => go(inner)
        case Validate(inner, _, _, _, _) => go(inner)
        case Then(x, y)                  =>
          val (xin, _)    = go(x)
          val (yin, yout) = go(y)
          (xin ++ yin, yout)
        case Mapped(inner, _)       => go(inner)
        case Tap(inner, _)          => go(inner)
        case Recover(inner, _)      => go(inner)
        case Retry(inner, _, _, _)  => go(inner)
        case Decorated(inner, _, _) => go(inner)
        case FlatMap(s, _)          =>
          val (sin, sout) = go(s)
          val dyn         = box("<dynamic>\\nruntime-decided")
          for (a <- sout) emitEdge(a.id, dyn, a.tpe, dashed = true)
          (sin, List(Port(dyn, "?")))
        case f: Fanin[?, ?, ?] =>
          /* Either-merge: only one branch fires, so dash inputs and outputs */
          val start       = counter
          val (lin, lout) = go(f.left)
          val (rin, rout) = go(f.right)
          group("|", start, counter)
          ((lin ++ rin).map(_.copy(dashed = true)), (lout ++ rout).map(_.copy(dashed = true)))
        case OrElse(l, r) =>
          /* <|> runs both on the same input; the fallback fires only on failure */
          val start       = counter
          val (lin, lout) = go(l)
          val (rin, rout) = go(r)
          group("orElse", start, counter)
          (
            lin.map(_.copy(shared = true)) ++ rin.map(_.copy(shared = true, dashed = true)),
            (lout ++ rout).map(_.copy(dashed = true))
          )
        case Cond(source, branches, default) =>
          /* branches sit in a `?` cluster fed by the source; labels ride the
             entry node, default is `else`, and only one branch fires */
          val start    = counter
          val branchIO = (branches.map(_._2) ++ default.toList).map(go)
          group("?", start, counter)
          val branchLabels = branches.zipWithIndex.map { case ((p, _), i) =>
            if (p.source.nonEmpty) p.source else s"#${i + 1}"
          } ++ default.toList.map(_ => "else")
          for (((bin, _), lbl) <- branchIO.zip(branchLabels); b <- bin)
            nodeLabel.get(b.id).foreach(cur => nodeLabel(b.id) = s"$cur ($lbl)")
          if (isSyntheticIdentity(source)) {
            val t = source match {
              case Step(_, _, inN, _, _) => inN.show
              case _                     => "?"
            }
            (
              branchIO.flatMap(_._1).map(_.copy(tpe = t, dashed = true)),
              branchIO.flatMap(_._2).map(_.copy(dashed = true))
            )
          } else {
            val (sin, sout) = go(source)
            for (a <- sout; (bin, _) <- branchIO; b <- bin)
              emitEdge(a.id, b.id, a.tpe, dashed = true)
            val passthrough = if (default.isEmpty) sout else Nil
            (sin, (branchIO.flatMap(_._2) ++ passthrough).map(_.copy(dashed = true)))
          }
      }

      val (rootIn, rootOut) = go(node)
      if (labelEdges) {
        /* anchors carry the outer in/out types broadcast blocks (&/&>/orElse) share one anchor per distinct input */
        val (sharedIn, distinctIn) = rootIn.partition(_.shared)
        val sharedGroups           =
          mutable.LinkedHashMap.empty[(Option[String], String), mutable.ArrayBuffer[Port]]
        for (b <- sharedIn)
          sharedGroups.getOrElseUpdate((b.label, b.tpe), mutable.ArrayBuffer.empty) += b
        for ((_, grp) <- sharedGroups) {
          val src = boundary()
          for (b <- grp) emitEdge(src, b.id, b.label.getOrElse(b.tpe), b.dashed)
        }
        for (b <- distinctIn) {
          val src = boundary(); emitEdge(src, b.id, b.label.getOrElse(b.tpe), b.dashed)
        }
        for (a <- rootOut) { val sink = boundary(); emitEdge(a.id, sink, a.tpe, a.dashed) }
      }

      val out = mutable.ArrayBuffer.empty[String]
      out += "digraph G {"
      out += s"  rankdir=${direction.code};"
      for (id <- boundaries) out += s"  n$id [shape=point, width=0.08];"
      for ((id, label) <- nodeLabel if !nodeGroup.contains(id))
        out += s"""  n$id [label="$label"];"""
      for ((g, symbol) <- groups) {
        out += s"  subgraph cluster_$g {"
        out += s"""    label="$symbol"; style=dashed; color=gray60;"""
        for ((id, label) <- nodeLabel if nodeGroup.get(id).contains(g))
          out += s"""    n$id [label="$label"];"""
        out += "  }"
      }
      for ((a, b, label, dashed) <- edges) {
        val attrs = List(
          if (label.nonEmpty) Some(s"""label="$label"""") else None,
          if (dashed) Some("style=dashed") else None
        ).flatten
        val suffix = if (attrs.isEmpty) "" else s" [${attrs.mkString(", ")}]"
        out += s"  n$a -> n$b$suffix;"
      }
      out += "}"
      out.mkString("\n")
    }
  }

  /**
   * The identity effect: `Id[A] = A`. Running a pipeline with `unsafeRun[Id]`
   * (or the plain `unsafeRun`) is fully synchronous
   */
  type Id[A] = A

  /**
   * Folds a pipeline into any `F` that has an [[Effect]] instance in scope. etl4s
   * ships `Id` (synchronous), `Future`, and `Try`; add your own with one instance
   */
  trait Effect[F[_]] {
    def pure[A](a: A): F[A]
    def delay[A](thunk: => A): F[A]
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
    def map[A, B](fa: F[A])(f: A => B): F[B] = flatMap(fa)(a => pure(f(a)))
    def handleErrorWith[A](fa: => F[A])(h: Throwable => F[A]): F[A]
    def both[A, B](fa: F[A], fb: F[B]): F[(A, B)] = flatMap(fa)(a => map(fb)(b => (a, b)))
  }

  object Effect {

    given idEffect: Effect[Id] with {
      def pure[A](a: A): Id[A]                                           = a
      def delay[A](thunk: => A): Id[A]                                   = thunk
      def flatMap[A, B](fa: Id[A])(f: A => Id[B]): Id[B]                 = f(fa)
      def handleErrorWith[A](fa: => Id[A])(h: Throwable => Id[A]): Id[A] =
        try fa
        catch { case NonFatal(t) => h(t) }
    }

    given futureEffect(using ec: ExecutionContext): Effect[Future] with {
      def pure[A](a: A): Future[A]                                   = Future.successful(a)
      def delay[A](thunk: => A): Future[A]                           = Future(thunk)
      def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)
      def handleErrorWith[A](fa: => Future[A])(h: Throwable => Future[A]): Future[A] =
        (try fa
        catch { case NonFatal(t) => Future.failed(t) }).recoverWith { case t => h(t) }
      override def both[A, B](fa: Future[A], fb: Future[B]): Future[(A, B)] = fa.zip(fb)
    }

    given tryEffect: Effect[Try] with {
      def pure[A](a: A): Try[A]                                             = Success(a)
      def delay[A](thunk: => A): Try[A]                                     = Try(thunk)
      def flatMap[A, B](fa: Try[A])(f: A => Try[B]): Try[B]                 = fa.flatMap(f)
      def handleErrorWith[A](fa: => Try[A])(h: Throwable => Try[A]): Try[A] =
        (try fa
        catch { case NonFatal(t) => Failure(t) }) match {
          case Failure(t) => h(t)
          case s          => s
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
    def apply[A, B](func: A => B)(using Name, TypeName[A], TypeName[B]): Pipeline[A, B] = Node(func)
    def apply[B](value: => B)(using Name, TypeName[B]): Pipeline[Any, B] = Node(value)
    def pure[A](using Name, TypeName[A]): Pipeline[A, A]                 = Node.identity[A]
    def requires[T, A, B](f: T => A => B)(using
      Name,
      TypeName[A],
      TypeName[B]
    ): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Extract {
    def apply[A, B](func: A => B)(using Name, TypeName[A], TypeName[B]): Extract[A, B] = Node(func)
    def apply[B](value: => B)(using Name, TypeName[B]): Extract[Any, B]                = Node(value)
    def pure[A](using Name, TypeName[A]): Extract[A, A] = Node.identity[A]
    def requires[T, A, B](f: T => A => B)(using
      Name,
      TypeName[A],
      TypeName[B]
    ): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Transform {
    def apply[A, B](func: A => B)(using Name, TypeName[A], TypeName[B]): Transform[A, B] = Node(
      func
    )
    def apply[B](value: => B)(using Name, TypeName[B]): Transform[Any, B] = Node(value)
    def pure[A](using Name, TypeName[A]): Transform[A, A]                 = Node.identity[A]
    def requires[T, A, B](f: T => A => B)(using
      Name,
      TypeName[A],
      TypeName[B]
    ): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  object Load {
    def apply[A, B](func: A => B)(using Name, TypeName[A], TypeName[B]): Load[A, B] = Node(func)
    def apply[B](value: => B)(using Name, TypeName[B]): Load[Any, B]                = Node(value)
    def pure[A](using Name, TypeName[A]): Load[A, A] = Node.identity[A]
    def requires[T, A, B](f: T => A => B)(using
      Name,
      TypeName[A],
      TypeName[B]
    ): Reader[T, Node[A, B]] = Node.requires[T, A, B](f)
  }

  /**
   * Extension methods for Node factory methods.
   *
   * This allows the pattern: `Transform[Int, Int].requires[Config] { ... }`
   */
  extension [A, B](factory: (A => B) => Node[A, B]) {
    def requires[T](f: T => A => B): Reader[T, Node[A, B]] = {
      Reader { config =>
        factory { a =>
          f(config)(a)
        }
      }
    }
  }

  /**
   * Makes an existing node depend on some configuration type `T`, reusing its
   * `A => B` shape.
   *
   * @example
   * {{{
   * val configNode = someNode.requires[Config] { config => input =>
   *   input * config.multiplier
   * }
   * configNode.provideContext(Config(5)).unsafeRun(10)
   * }}}
   */
  extension [A, B](node: Node[A, B]) {
    def requires[T](
      f: T => A => B
    )(using name: Name, inN: TypeName[A], outN: TypeName[B]): Reader[T, Node[A, B]] =
      Reader(
        config => Node.Step(name.value, f(config), inN, outN, name.fullName),
        sourceName = Some(name)
      )

    /**
     * Compiles the pure pipeline into the effect `F`, returning a [[Node.Runner]] whose
     * `unsafeRun` folds the graph into that `F`: `pipeline.compile[Future].unsafeRun(x)`
     * yields `Future[B]`, and `compile[Try].unsafeRun(x)` a `Try[B]`.
     */
    def compile[F[_]](using E: Effect[F]): Node.Runner[A, B, F] =
      new Node.Runner[A, B, F](node)
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
   * Companion object providing given instances for ReaderCompat.
   *
   * The priority hierarchy ensures the most specific instances are selected first.
   */
  object ReaderCompat extends ReaderCompat2 {

    /** Highest priority: Case 1 - same types */
    given identityCompat[T]: ReaderCompat[T, T, T] = new ReaderCompat[T, T, T] {
      def toT1(r: T): T = r
      def toT2(r: T): T = r
    }
  }

  trait ReaderCompat2 extends ReaderCompat1 {

    /** Case 2: T1 is a subtype of T2 */
    given t1SubT2[T1 <: T2, T2]: ReaderCompat[T1, T2, T1] = new ReaderCompat[T1, T2, T1] {
      def toT1(r: T1): T1 = r
      def toT2(r: T1): T2 = r /* Since T1 <: T2 */
    }
  }

  trait ReaderCompat1 extends ReaderCompat0 {

    /** Case 3: T2 is a subtype of T1 */
    given t2SubT1[T1, T2 <: T1]: ReaderCompat[T1, T2, T2] = new ReaderCompat[T1, T2, T2] {
      def toT1(r: T2): T1 = r /* Since T2 <: T1 */
      def toT2(r: T2): T2 = r
    }
  }

  trait ReaderCompat0 {

    /** Case 4: Unrelated types - use intersection type T1 & T2 */
    given intersectionCompat[T1, T2]: ReaderCompat[T1, T2, T1 & T2] =
      new ReaderCompat[T1, T2, T1 & T2] {
        def toT1(r: T1 & T2): T1 = r
        def toT2(r: T1 & T2): T2 = r
      }
  }

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
  case class Reader[R, +A](
    run: R => A,
    metadata: Any = None,
    getLineage: Option[Lineage] = None,
    sourceName: Option[Name] = None
  ) {
    def map[B](f: A => B): Reader[R, B] = Reader(r => f(run(r)), metadata, getLineage, sourceName)
    def flatMap[B](f: A => Reader[R, B]): Reader[R, B] =
      Reader(r => f(run(r)).run(r), metadata, getLineage, sourceName)

    /**
     * The short binding name captured for this Reader (e.g. from `Node.requires`
     * / `Reader.Extract`), if any.
     */
    def getName: Option[String] = sourceName.map(_.value)

    /**
     * The fully-qualified path of this Reader's binding (enclosing
     * package/object/class chain), captured at compile time, if any.
     */
    def getFullName: Option[String] =
      sourceName.map(_.fullName).filter(_.nonEmpty)
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
    given nodeHasMetadata[A, B]: HasMetadata[[X] =>> Node[A, B]] =
      new HasMetadata[[X] =>> Node[A, B]] {
        def metadata[X](fa: Node[A, B]): Any                       = fa.metadata
        def withMetadata[X](fa: Node[A, B], meta: Any): Node[A, B] = fa.withMetadata(meta)
      }

    given readerHasMetadata[R]: HasMetadata[[A] =>> Reader[R, A]] =
      new HasMetadata[[A] =>> Reader[R, A]] {
        def metadata[A](fa: Reader[R, A]): Any                         = fa.metadata
        def withMetadata[A](fa: Reader[R, A], meta: Any): Reader[R, A] = fa.withMetadata(meta)
      }
  }

  /**
   * Extension methods for composing Reader-wrapped Nodes.
   *
   * These methods enable natural composition of context-dependent operations
   * while handling environment compatibility automatically via ReaderCompat.
   */
  extension [T1, A, B](fa: Reader[T1, Node[A, B]]) {

    /**
     * Sequential composition: Reader(Node) ~> Reader(Node)
     * Uses ReaderCompat to handle type compatibility automatically.
     */
    def ~>[T2, C, R](
      fb: Reader[T2, Node[B, C]]
    )(using compat: ReaderCompat[T1, T2, R]): Reader[R, Node[A, C]] = {
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

    /**
     * Sequential composition: Reader(Node) ~> Node
     */
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
     * Parallel composition: Reader(Node) & Reader(Node)
     * Uses ReaderCompat to handle type compatibility automatically.
     * Auto-flattens tuples: r1 & r2 & r3 produces (Out1, Out2, Out3)
     */
    def &[T2, C, R, O <: Tuple](
      fb: Reader[T2, Node[A, C]]
    )(using
      compat: ReaderCompat[T1, T2, R],
      ta: TupleAppend.Aux[B, C, O]
    ): Reader[R, Node[A, O]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, O]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA & nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Parallel composition: Reader(Node) & Node
     * Auto-flattens tuples.
     */
    def &[C, O <: Tuple](node: Node[A, C])(using
      ta: TupleAppend.Aux[B, C, O]
    ): Reader[T1, Node[A, O]] = {
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
     * Concurrent parallel composition: Reader(Node) &> Reader(Node)
     * Uses ReaderCompat to handle type compatibility automatically.
     * Auto-flattens tuples.
     */
    def &>[T2, C, R, O <: Tuple](fb: Reader[T2, Node[A, C]])(using
      compat: ReaderCompat[T1, T2, R],
      ta: TupleAppend.Aux[B, C, O]
    ): Reader[R, Node[A, O]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, O]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA &> nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Concurrent parallel composition: Reader(Node) &> Node
     * Auto-flattens tuples.
     */
    def &>[C, O <: Tuple](
      node: Node[A, C]
    )(using ta: TupleAppend.Aux[B, C, O]): Reader[T1, Node[A, O]] = {
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
     * Sequence composition (discard first result): Reader(Node) >> Reader(Node)
     * Uses ReaderCompat to handle type compatibility automatically.
     */
    def >>[T2, C, R](
      fb: Reader[T2, Node[A, C]]
    )(using compat: ReaderCompat[T1, T2, R]): Reader[R, Node[A, C]] = {
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

    /**
     * Sequence composition (discard first result): Reader(Node) >> Node
     */
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
     * Fan-in: Reader(Node) | Reader(Node).
     * Routes an `Either` input to whichever branch matches, merging to a common output.
     */
    def |[T2, C, R](
      fb: Reader[T2, Node[C, B]]
    )(using compat: ReaderCompat[T1, T2, R]): Reader[R, Node[Either[A, C], B]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[Either[A, C], B]] { (env: R) =>
        fa.run(compat.toT1(env)) | fb.run(compat.toT2(env))
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Fan-in: Reader(Node) | Node.
     */
    def |[C](node: Node[C, B]): Reader[T1, Node[Either[A, C], B]] = {
      val combined = (fa.getLineage, node.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = fa.map(readerNode => readerNode | node)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Choice: Reader(Node) + Reader(Node).
     * Routes an `Either` input through independent branches, keeping the `Either` on the output.
     */
    def +[T2, C, D, R](
      fb: Reader[T2, Node[C, D]]
    )(using compat: ReaderCompat[T1, T2, R]): Reader[R, Node[Either[A, C], Either[B, D]]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[Either[A, C], Either[B, D]]] { (env: R) =>
        fa.run(compat.toT1(env)) + fb.run(compat.toT2(env))
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Choice: Reader(Node) + Node.
     */
    def +[C, D](node: Node[C, D]): Reader[T1, Node[Either[A, C], Either[B, D]]] = {
      val combined = (fa.getLineage, node.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = fa.map(readerNode => readerNode + node)
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Error fallback: Reader(Node) <|> Reader(Node).
     * Runs the left node; on any throwable runs the right node on the original input.
     */
    def <|>[T2, R](
      fb: Reader[T2, Node[A, B]]
    )(using compat: ReaderCompat[T1, T2, R]): Reader[R, Node[A, B]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, B]] { (env: R) =>
        fa.run(compat.toT1(env)) <|> fb.run(compat.toT2(env))
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Error fallback: Reader(Node) <|> Node.
     */
    def <|>(node: Node[A, B]): Reader[T1, Node[A, B]] = {
      val combined = (fa.getLineage, node.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = fa.map(readerNode => readerNode <|> node)
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

    private def skeleton: Node[A, B] = fa.run(null.asInstanceOf[T1])
    def stages: List[Node.StageInfo] = skeleton.stages
  }

  /**
   * Result container for traced pipeline execution.
   *
   * @tparam A the result type
   * @param result the final result value
   * @param timeElapsedMillis execution duration in milliseconds
   */
  case class Trace[+A](
    result: A,
    timeElapsedMillis: Long = 0L
  ) {

    /** Get timing in seconds */
    def seconds: Double = timeElapsedMillis / 1000.0
  }

  /** Utility functions */
  def tap[A](f: A => Any): Node[A, A] = Node[A, A](a => { f(a); a })

  /**
   * Implicit conversions for validation checks
   */
  implicit def curriedToCheck[T, A](f: T => A => Option[String]): ValidationCheck[T, A] =
    CurriedCheck(f)

  implicit def plainToCheck[T, A](f: A => Option[String]): ValidationCheck[T, A] =
    PlainCheck(f)

  /**
   * Type class for flattening nested tuple structures.
   *
   * This helps transform nested tuples like `((a,b),c)` into flat tuples like `(a,b,c)`.
   * Makes pipelines that combine multiple steps more ergonomic.
   *
   * @tparam A the input type to flatten
   */
  trait Flatten[A] {
    type Out
    def apply(a: A): Out
  }

  object Flatten extends FlattenLowPriority {
    type Aux[A, B] = Flatten[A] { type Out = B }

    /** Flatten nested tuple where head is also a tuple: ((A, B, ...), C) => (A, B, ..., C) */
    given nestedTuple[H <: Tuple, L](using
      hf: Flatten[H]
    ): Flatten.Aux[(H, L), Tuple.Concat[hf.Out & Tuple, L *: EmptyTuple]] =
      new Flatten[(H, L)] {
        type Out = Tuple.Concat[hf.Out & Tuple, L *: EmptyTuple]
        def apply(t: (H, L)): Out = {
          val flatHead = hf(t._1).asInstanceOf[Tuple]
          (flatHead ++ (t._2 *: EmptyTuple)).asInstanceOf[Out]
        }
      }
  }

  trait FlattenLowPriority {

    /** Base case: simple pair (A, B) where A is not a tuple */
    given pair[A, B]: Flatten.Aux[(A, B), (A, B)] =
      new Flatten[(A, B)] {
        type Out = (A, B)
        def apply(t: (A, B)): (A, B) = t
      }

    /** Identity: non-tuple types */
    given base[A]: Flatten.Aux[A, A] = new Flatten[A] {
      type Out = A
      def apply(a: A): A = a
    }
  }

  /**
   * Type class for appending an element to a tuple, building flat tuples.
   * Used by the & operator to auto-flatten parallel compositions.
   *
   * For non-tuple A: (A, B) => (A, B)
   * For tuple A: (A1, A2) & B => (A1, A2, B)
   *
   * This enables: node1 & node2 & node3 to produce Node[In, (Out1, Out2, Out3)]
   * instead of Node[In, ((Out1, Out2), Out3)]
   */
  trait TupleAppend[-A, -B] {
    type Out <: Tuple
    def append(a: A, b: B): Out
  }

  object TupleAppend extends TupleAppendLowPriority {
    type Aux[A, B, O <: Tuple] = TupleAppend[A, B] { type Out = O }

    // When A is already a tuple, append B to it
    given tupleAppend[A <: Tuple, B]: TupleAppend.Aux[A, B, Tuple.Append[A, B]] =
      new TupleAppend[A, B] {
        type Out = Tuple.Append[A, B]
        def append(a: A, b: B): Tuple.Append[A, B] = a :* b
      }
  }

  trait TupleAppendLowPriority {
    // When A is not a tuple, create a pair
    given pairAppend[A, B]: TupleAppend.Aux[A, B, (A, B)] =
      new TupleAppend[A, B] {
        type Out = (A, B)
        def append(a: A, b: B): (A, B) = (a, b)
      }
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
   *     saveToDatabase(config, user)
   *   }
   * 
   *   val pipeline = extractUsers ~> transformUsers ~> saveUser
   * 
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
      def Extract[A, B](
        f: T => A => B
      )(using Name, TypeName[A], TypeName[B]): Reader[T, Extract[A, B]] =
        etl4s.Extract.requires[T, A, B](f)

      def Transform[A, B](
        f: T => A => B
      )(using Name, TypeName[A], TypeName[B]): Reader[T, Transform[A, B]] =
        etl4s.Transform.requires[T, A, B](f)

      def Load[A, B](f: T => A => B)(using Name, TypeName[A], TypeName[B]): Reader[T, Load[A, B]] =
        etl4s.Load.requires[T, A, B](f)

      def Pipeline[A, B](
        f: T => A => B
      )(using Name, TypeName[A], TypeName[B]): Reader[T, Pipeline[A, B]] =
        etl4s.Pipeline.requires[T, A, B](f)

      def Node[A, B](f: T => A => B)(using Name, TypeName[A], TypeName[B]): Reader[T, Node[A, B]] =
        etl4s.Node.requires[T, A, B](f)

      def tap[A](f: T => A => Any)(using name: Name, tn: TypeName[A]): Reader[T, Node[A, A]] =
        Reader { ctx =>
          etl4s.Node.Step[A, A](name.value, a => { f(ctx)(a); a }, tn, tn, name.fullName)
        }

      def If[A, C, Branch](condition: T => Boolean)(branch: Branch)(using
        branchLift: BranchLift[A, C, Branch],
        tn: TypeName[A]
      ): ReaderPartialConditionalBuilder[T & branchLift.Config, A, A, C] = {
        type R = T & branchLift.Config
        ReaderPartialConditionalBuilder[R, A, A, C](
          Reader.pure(etl4s.Node.identity[A](using Name("input"), tn)),
          List(
            (
              ((t: R) => Predicate((_: A) => condition(t))).asInstanceOf[R => Predicate[A]],
              branchLift.lift(branch).asInstanceOf[Reader[R, Node[A, C]]]
            )
          )
        )
      }
    }
  }

  /**
   * Typeclass for rendering lineage information to various formats.
   */
  trait LineageRenderer[T] {
    def toJson(t: T): String
    def toDot(t: T): String
    def toMermaid(t: T): String
  }

  object LineageRenderer {

    /** TODO: maybe revisit since a bit unintuitive ...
      *
      *  but a single node/reader renders its structural graphs whereas a Seq
      *  of them rendered the declared multi-node lineage
      */
    given nodeRenderer[A, B]: LineageRenderer[Node[A, B]] = new LineageRenderer[Node[A, B]] {
      def toJson(t: Node[A, B]): String    = new LineageCollectionOps(Seq(t)).toJson
      def toDot(t: Node[A, B]): String     = Node.dot(t)
      def toMermaid(t: Node[A, B]): String = Node.mermaid(t)
    }

    given readerRenderer[R, A, B]: LineageRenderer[Reader[R, Node[A, B]]] =
      new LineageRenderer[Reader[R, Node[A, B]]] {
        def toJson(t: Reader[R, Node[A, B]]): String    = new LineageCollectionOps(Seq(t)).toJson
        def toDot(t: Reader[R, Node[A, B]]): String     = Node.dot(t.run(null.asInstanceOf[R]))
        def toMermaid(t: Reader[R, Node[A, B]]): String =
          Node.mermaid(t.run(null.asInstanceOf[R]))
      }

    given seqRenderer[T]: LineageRenderer[Seq[T]] = new LineageRenderer[Seq[T]] {
      def toJson(items: Seq[T]): String    = new LineageCollectionOps(items).toJson
      def toDot(items: Seq[T]): String     = new LineageCollectionOps(items).toDot
      def toMermaid(items: Seq[T]): String = new LineageCollectionOps(items).toMermaid
    }
  }

  /**
   * Extension methods for lineage rendering using typeclass.
   */
  extension [T](t: T)(using renderer: LineageRenderer[T]) {
    def toJson: String    = renderer.toJson(t)
    def toDot: String     = renderer.toDot(t)
    def toMermaid: String = renderer.toMermaid(t)
  }

  /**
   * Renders the *structural* graph of a single `Node` or `Reader`, with options.
   */
  trait StructuralRenderer[T] {
    def node(t: T): Node[?, ?]
  }
  object StructuralRenderer {
    given nodeStructural[A, B]: StructuralRenderer[Node[A, B]] with {
      def node(t: Node[A, B]): Node[?, ?] = t
    }
    given readerStructural[R, A, B]: StructuralRenderer[Reader[R, Node[A, B]]] with {
      def node(t: Reader[R, Node[A, B]]): Node[?, ?] = t.run(null.asInstanceOf[R])
    }
  }

  /**
   * Structural diagrams with options (single `Node` / `Reader`)
   * 
   * (`Direction.LR` / `RL` / `TB` / `BT`) to change the layout.
   */
  extension [T](t: T)(using sr: StructuralRenderer[T]) {
    def toDot(
      showTypes: Boolean = true,
      direction: Direction = Direction.LR,
      typesOnNodes: Boolean = false
    ): String =
      Node.dot(sr.node(t), showTypes, direction, typesOnNodes)
    def toMermaid(
      showTypes: Boolean = true,
      direction: Direction = Direction.LR,
      typesOnNodes: Boolean = false
    ): String =
      Node.mermaid(sr.node(t), showTypes, direction, typesOnNodes)

    /** DOT with fan-outs / conditionals / joins drawn as dashed clusters. */
    def toDotClustered: String =
      Node.dotClustered(sr.node(t), true, Direction.LR)
    def toDotClustered(
      showTypes: Boolean = true,
      direction: Direction = Direction.LR
    ): String =
      Node.dotClustered(sr.node(t), showTypes, direction)
  }

  /**
   * Type class for attaching lineage to different types.
   */
  trait LineageAttachable[T, Out] {
    def withLineage(t: T, lineage: Lineage): Out
  }

  given nodeLineageAttachable[A, B]: LineageAttachable[Node[A, B], Node[A, B]] with {
    def withLineage(node: Node[A, B], lineage: Lineage): Node[A, B] = node.withLineage(lineage)
  }

  given readerLineageAttachable[R, A]: LineageAttachable[Reader[R, A], Reader[R, A]] with {
    def withLineage(reader: Reader[R, A], lineage: Lineage): Reader[R, A] =
      reader.withLineage(lineage)
  }

  /**
   * Universal extension for adding lineage to any type with LineageAttachable.
   */
  extension [T, Out](t: T)(using attachable: LineageAttachable[T, Out]) {

    /**
     * Attaches lineage information.
     *
     * @param name the unique name/identifier for this pipeline component
     * @param inputs list of input data source names
     * @param outputs list of output data source names
     * @param schedule optional schedule information
     * @param cluster optional cluster/group name
     * @param upstreams list of upstream Node/Reader objects or String names this depends on
     * @return a new instance with the attached lineage
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
    ): Out = attachable.withLineage(
      t,
      Lineage(name, inputs, outputs, upstreams, schedule, cluster, description, group, tags, links)
    )
  }

  /**
   * Extension methods for adding validation to Nodes.
   *
   * Validation functions return None if valid, Some(errorMessage) if invalid.
   * All validation errors are collected into the thrown exception message.
   */
  extension [A, B](node: Node[A, B]) {

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
      else Node.Validate(node, input, output, change, concurrent = false)

    /**
     * Like `ensure`, but the checks within each stage are eligible to run
     * concurrently
     */
    def ensurePar(
      input: Seq[A => Option[String]] = Nil,
      output: Seq[B => Option[String]] = Nil,
      change: Seq[((A, B)) => Option[String]] = Nil
    ): Node[A, B] =
      if (input.isEmpty && output.isEmpty && change.isEmpty) node
      else Node.Validate(node, input, output, change, concurrent = true)

    /**
     * Conditional branching for Nodes. Delegates to [[IfMacros.nodeIf]] which
     * captures the source text of the predicate at the call site so
     * `.If(_ < 0)(...)` stores `"_ < 0"` on the branch label.
     */
    inline def If[C](inline condition: B => Boolean)(
      inline branch: Node[B, C]
    ): PartialConditionalBuilder[A, B, C] =
      ${ IfMacros.nodeIf[A, B, C]('node, 'condition, 'branch) }
  }

  /**
   * Non-exhaustive conditional builder for Nodes with heterogeneous output types.
   * Each branch can produce a different output type, accumulating as a union.
   *
   * @tparam A input type to the source node
   * @tparam B output type from source node (input to branches)
   * @tparam C accumulated union type of all branch outputs
   */
  case class PartialConditionalBuilder[A, B, C](
    sourceNode: Node[A, B],
    branches: List[(Predicate[B], Node[B, C])]
  ) {

    /**
     * Add another conditional branch with potentially different output type.
     * The output types union together: C | C2.
     * Delegates to [[IfMacros.partialElseIf]] for source-text capture.
     */
    inline def ElseIf[C2](
      inline condition: B => Boolean
    )(inline branch: Node[B, C2]): PartialConditionalBuilder[A, B, C | C2] =
      ${ IfMacros.partialElseIf[A, B, C, C2]('this, 'condition, 'branch) }

    /**
     * Complete the conditional with a default branch.
     * Output type becomes C | C2 (union of all branches including default).
     */
    def Else[C2](branch: Node[B, C2]): Node[A, C | C2] =
      Node.Cond[A, B, C | C2](
        sourceNode,
        branches.map { case (cond, node) => (cond, node.asInstanceOf[Node[B, C | C2]]) },
        Some(branch.asInstanceOf[Node[B, C | C2]])
      )
  }

  /**
   * Exhaustive conditional builder for Nodes with heterogeneous output types.
   * Produces a Node[A, C] where C is the union of all branch output types.
   *
   * @tparam A input type to the source node
   * @tparam B output type from source node (input to branches)
   * @tparam C union type of all branch outputs
   */
  case class CompleteConditionalBuilder[A, B, C](
    sourceNode: Node[A, B],
    branches: List[(Predicate[B], Node[B, C])],
    defaultBranch: Node[B, C]
  ) {

    /**
     * Add another conditional branch, inserting before the default.
     * Output type expands to C | C2.
     * Delegates to [[IfMacros.completeElseIf]] for source-text capture.
     */
    inline def ElseIf[C2](inline condition: B => Boolean)(
      inline branch: Node[B, C2]
    ): CompleteConditionalBuilder[A, B, C | C2] =
      ${ IfMacros.completeElseIf[A, B, C, C2]('this, 'condition, 'branch) }

    def build: Node[A, C] =
      Node.Cond[A, B, C](sourceNode, branches, Some(defaultBranch))
  }

  implicit def conditionalBuilderToNode[A, B, C](
    builder: CompleteConditionalBuilder[A, B, C]
  ): Node[A, C] = builder.build

  /**
   * Starts a pipeline with a conditional branch on the input value.
   * Without a trailing `Else`, unmatched inputs pass through unchanged.
   * Delegates to [[IfMacros.topIf]] which captures the predicate's source
   * text at the call site so `If[Int](_ < 10)` labels the branch `"_ < 10"`.
   */
  inline def If[A](inline condition: A => Boolean): ValueIfStart[A] =
    ${ IfMacros.topIf[A]('condition) }

  final class ValueIfStart[A](private val condition: Predicate[A]) {
    // TypeName captured here (where A is concrete), not on `If`.
    def apply[C](branch: Node[A, C])(using tn: TypeName[A]): PartialConditionalBuilder[A, A, C] =
      PartialConditionalBuilder(
        Node.identity[A](using Name("input"), tn),
        List((condition, branch))
      )
  }

  /** Uses a partial builder as a Node: unmatched inputs pass through unchanged. */
  implicit def partialConditionalBuilderToNode[A, B, C](
    builder: PartialConditionalBuilder[A, B, C]
  ): Node[A, C | B] =
    Node.Cond[A, B, C | B](
      builder.sourceNode,
      builder.branches.map { case (cond, node) => (cond, node.asInstanceOf[Node[B, C | B]]) },
      None
    )

  /**
   * Type class for lifting branches (Node or Reader) to Reader.
   * Given a branch type, determines the config type needed.
   * For Nodes, config type is Any (no requirement).
   * For Readers, config type is the Reader's type parameter.
   */
  trait BranchLift[B, C, Branch] {
    type Config
    def lift(branch: Branch): Reader[Config, Node[B, C]]
  }

  object BranchLift {
    // Node branch: no config requirement (Any)
    given nodeToReader[B, C]: BranchLift[B, C, Node[B, C]] with {
      type Config = Any
      def lift(branch: Node[B, C]): Reader[Any, Node[B, C]] = Reader.pure(branch)
    }

    // Reader branch: uses the Reader's config type
    given readerLift[T, B, C]: BranchLift[B, C, Reader[T, Node[B, C]]] with {
      type Config = T
      def lift(branch: Reader[T, Node[B, C]]): Reader[T, Node[B, C]] = branch
    }
  }

  /**
   * Type class for lifting conditions (plain, source-carrying, or curried) to
   * Reader-aware form. Enables clean syntax:
   * `.If(_.age > 18)` instead of `.If((_: Cfg) => _.age > 18)`.
   * Result is a `Config => Predicate[B]` so branch source text (when carried
   * by an explicit [[Predicate]]) can be surfaced in diagram labels. Plain
   * `B => Boolean` inputs on the Reader side land with an empty source; the
   * value-side (`Node.If`) captures source automatically via the macro.
   */
  trait ConditionLift[B, Cond] {
    type Config
    def lift(cond: Cond): Config => Predicate[B]
  }

  trait ConditionLiftLowPriority {
    // Curried form: lower priority (uses the condition's config type)
    given curriedLift[T, B]: ConditionLift[B, T => B => Boolean] with {
      type Config = T
      def lift(cond: T => B => Boolean): T => Predicate[B] = t => Predicate(cond(t))
    }
  }

  trait ConditionLiftMidPriority extends ConditionLiftLowPriority {
    // Plain `B => Boolean` form, kept for backward compatibility; carries no
    // captured source text (branch labels fall back to `#N` in diagrams).
    given plainFnLift[B]: ConditionLift[B, B => Boolean] with {
      type Config = Any
      def lift(cond: B => Boolean): Any => Predicate[B] = _ => Predicate(cond)
    }
  }

  object ConditionLift extends ConditionLiftMidPriority {
    // Source-carrying form: highest priority. When the user has already
    // wrapped a predicate in `Predicate(...)`, keep the captured source.
    given predicateLift[B]: ConditionLift[B, Predicate[B]] with {
      type Config = Any
      def lift(cond: Predicate[B]): Any => Predicate[B] = _ => cond
    }
  }

  /**
   * Non-exhaustive conditional builder for Reader-wrapped nodes with heterogeneous types.
   * Config types accumulate via intersection, output types via union.
   */
  case class ReaderPartialConditionalBuilder[T, A, B, C](
    sourceReader: Reader[T, Node[A, B]],
    branches: List[(T => Predicate[B], Reader[T, Node[B, C]])]
  ) {

    /**
     * Add another conditional branch.
     * Supports both plain conditions ((_: Int) > 0) and curried conditions ((cfg: Config) => (n: Int) => ...).
     * For Node branches: config unchanged, output types union.
     * For Reader branches: config types intersect, output types union.
     */
    def ElseIf[C2, Branch, Cond](condition: Cond)(branch: Branch)(using
      condLift: ConditionLift[B, Cond],
      branchLift: BranchLift[B, C2, Branch]
    ): ReaderPartialConditionalBuilder[T & condLift.Config & branchLift.Config, A, B, C | C2] = {
      type R   = T & condLift.Config & branchLift.Config
      type Out = C | C2
      ReaderPartialConditionalBuilder(
        sourceReader.asInstanceOf[Reader[R, Node[A, B]]],
        branches.map((c, r) =>
          (c.asInstanceOf[R => Predicate[B]], r.asInstanceOf[Reader[R, Node[B, Out]]])
        ) :+
          (
            condLift.lift(condition).asInstanceOf[R => Predicate[B]],
            branchLift.lift(branch).asInstanceOf[Reader[R, Node[B, Out]]]
          )
      )
    }

    /** Add branch based purely on config/environment (ignores data). */
    def ElseIfCtx[C2, Branch](condition: T => Boolean)(branch: Branch)(using
      branchLift: BranchLift[B, C2, Branch]
    ): ReaderPartialConditionalBuilder[T & branchLift.Config, A, B, C | C2] = {
      type R   = T & branchLift.Config
      type Out = C | C2
      ReaderPartialConditionalBuilder(
        sourceReader.asInstanceOf[Reader[R, Node[A, B]]],
        branches.map((c, r) =>
          (c.asInstanceOf[R => Predicate[B]], r.asInstanceOf[Reader[R, Node[B, Out]]])
        ) :+
          (
            ((t: R) => Predicate((_: B) => condition(t))).asInstanceOf[R => Predicate[B]],
            branchLift.lift(branch).asInstanceOf[Reader[R, Node[B, Out]]]
          )
      )
    }

    /** Complete the conditional with a default branch. */
    def Else[C2, Branch](branch: Branch)(using
      lift: BranchLift[B, C2, Branch]
    ): Reader[T & lift.Config, Node[A, C | C2]] = {
      type R   = T & lift.Config
      type Out = C | C2
      Reader { ctx =>
        val source    = sourceReader.asInstanceOf[Reader[R, Node[A, B]]].run(ctx)
        val evaluated = branches.map((c, r) =>
          (c.asInstanceOf[R => Predicate[B]](ctx), r.asInstanceOf[Reader[R, Node[B, Out]]].run(ctx))
        )
        val default = lift.lift(branch).asInstanceOf[Reader[R, Node[B, Out]]].run(ctx)
        Node.Cond[A, B, Out](source, evaluated, Some(default))
      }
    }
  }

  /**
   * Exhaustive conditional builder for Reader-wrapped nodes with heterogeneous types
   */
  case class ReaderCompleteConditionalBuilder[T, A, B, C](
    sourceReader: Reader[T, Node[A, B]],
    branches: List[(T => Predicate[B], Reader[T, Node[B, C]])],
    defaultBranch: Reader[T, Node[B, C]]
  ) {

    /** Add another conditional branch before the default.
     * Supports both plain conditions ((_: Int) > 0) and curried conditions ((cfg: Config) => (n: Int) => ...).
     */
    def ElseIf[C2, Branch, Cond](condition: Cond)(branch: Branch)(using
      condLift: ConditionLift[B, Cond],
      branchLift: BranchLift[B, C2, Branch]
    ): ReaderCompleteConditionalBuilder[T & condLift.Config & branchLift.Config, A, B, C | C2] = {
      type R   = T & condLift.Config & branchLift.Config
      type Out = C | C2
      ReaderCompleteConditionalBuilder(
        sourceReader.asInstanceOf[Reader[R, Node[A, B]]],
        branches.map((c, r) =>
          (c.asInstanceOf[R => Predicate[B]], r.asInstanceOf[Reader[R, Node[B, Out]]])
        ) :+
          (
            condLift.lift(condition).asInstanceOf[R => Predicate[B]],
            branchLift.lift(branch).asInstanceOf[Reader[R, Node[B, Out]]]
          ),
        defaultBranch.asInstanceOf[Reader[R, Node[B, Out]]]
      )
    }

    /** Add branch based purely on config/environment (ignores data) */
    def ElseIfCtx[C2, Branch](condition: T => Boolean)(branch: Branch)(using
      branchLift: BranchLift[B, C2, Branch]
    ): ReaderCompleteConditionalBuilder[T & branchLift.Config, A, B, C | C2] = {
      type R   = T & branchLift.Config
      type Out = C | C2
      ReaderCompleteConditionalBuilder(
        sourceReader.asInstanceOf[Reader[R, Node[A, B]]],
        branches.map((c, r) =>
          (c.asInstanceOf[R => Predicate[B]], r.asInstanceOf[Reader[R, Node[B, Out]]])
        ) :+
          (
            ((t: R) => Predicate((_: B) => condition(t))).asInstanceOf[R => Predicate[B]],
            branchLift.lift(branch).asInstanceOf[Reader[R, Node[B, Out]]]
          ),
        defaultBranch.asInstanceOf[Reader[R, Node[B, Out]]]
      )
    }

    def build: Reader[T, Node[A, C]] = Reader { ctx =>
      val source    = sourceReader.run(ctx)
      val evaluated = branches.map((c, r) => (c(ctx), r.run(ctx)))
      val default   = defaultBranch.run(ctx)
      Node.Cond[A, B, C](source, evaluated, Some(default))
    }
  }

  implicit def readerConditionalBuilderToReader[T, A, B, C](
    builder: ReaderCompleteConditionalBuilder[T, A, B, C]
  ): Reader[T, Node[A, C]] = builder.build

  /** Uses a Reader partial builder as a Reader: unmatched inputs pass through unchanged */
  implicit def readerPartialConditionalBuilderToReader[T, A, B, C](
    builder: ReaderPartialConditionalBuilder[T, A, B, C]
  ): Reader[T, Node[A, C | B]] = Reader { ctx =>
    val source    = builder.sourceReader.run(ctx)
    val evaluated =
      builder.branches.map((c, r) => (c(ctx), r.run(ctx).asInstanceOf[Node[B, C | B]]))
    Node.Cond[A, B, C | B](source, evaluated, None)
  }

  /**
   * Type class for batch containers that `each`, `eachPar`, and `eachSlice`
   * iterate over. Instances preserve the concrete collection type `F`
   */
  trait Batchable[CA, Elem, Coll[_]] {
    def toSeq(ca: CA): Seq[Elem]
    def fromElems(xs: Seq[Elem]): CA
    def fromSeq[B](xs: Seq[B]): Coll[B]
  }

  object Batchable {
    given [A]: Batchable[List[A], A, List] with {
      def toSeq(ca: List[A]): Seq[A]      = ca
      def fromElems(xs: Seq[A]): List[A]  = xs.toList
      def fromSeq[B](xs: Seq[B]): List[B] = xs.toList
    }
    given [A]: Batchable[Vector[A], A, Vector] with {
      def toSeq(ca: Vector[A]): Seq[A]      = ca
      def fromElems(xs: Seq[A]): Vector[A]  = xs.toVector
      def fromSeq[B](xs: Seq[B]): Vector[B] = xs.toVector
    }
    given [A]: Batchable[Seq[A], A, Seq] with {
      def toSeq(ca: Seq[A]): Seq[A]      = ca
      def fromElems(xs: Seq[A]): Seq[A]  = xs
      def fromSeq[B](xs: Seq[B]): Seq[B] = xs
    }
    given [A]: Batchable[Set[A], A, Set] with {
      def toSeq(ca: Set[A]): Seq[A]      = ca.toSeq
      def fromElems(xs: Seq[A]): Set[A]  = xs.toSet
      def fromSeq[B](xs: Seq[B]): Set[B] = xs.toSet
    }
    given [A]: Batchable[Iterable[A], A, Iterable] with {
      def toSeq(ca: Iterable[A]): Seq[A]      = ca.toSeq
      def fromElems(xs: Seq[A]): Iterable[A]  = xs
      def fromSeq[B](xs: Seq[B]): Iterable[B] = xs
    }
  }

  /** Element-wise batch step; see [[each]]. */
  final class Each[A, B](private[etl4s] val node: Node[A, B])

  /** Concurrent element-wise batch step; see [[eachPar]]. */
  final class EachPar[A, B](
    private[etl4s] val parallelism: Int,
    private[etl4s] val node: Node[A, B]
  )

  /** Map-and-keep batch step; see [[collectEach]]. */
  final class CollectEach[A, B](private[etl4s] val node: Node[A, Option[B]])

  /** Concurrent map-and-keep batch step; see [[collectEachPar]]. */
  final class CollectEachPar[A, B](
    private[etl4s] val parallelism: Int,
    private[etl4s] val node: Node[A, Option[B]]
  )

  /**
   * Applies `node` to every element of a batch, preserving the collection type
   *
   * {{{ extractBatch ~> each(clean ~> enrich) ~> load }}}
   */
  def each[A, B](node: Node[A, B]): Each[A, B] = new Each(node)

  /**
   * Like `each`, but runs up to `parallelism` elements concurrently.
   * Output order matches input order.
   *
   * {{{ extractBatch ~> eachPar(8)(clean ~> enrich) ~> load }}}
   */
  def eachPar[A, B](parallelism: Int)(node: Node[A, B]): EachPar[A, B] =
    new EachPar(parallelism, node)

  /**
   * Chunks the batch into windows of `size`, applying `node` to each window and
   * yielding one output per window.
   *
   * {{{ extractBatch ~> eachSlice(1000)(loadBatch) }}}
   */
  def eachSlice[CA, E, B, C[_]](size: Int)(node: Node[CA, B])(using
    ba: Batchable[CA, E, C]
  ): Node[CA, C[B]] =
    Node { ca =>
      val out = ba.toSeq(ca).grouped(size).map(chunk => node.f(ba.fromElems(chunk)))
      ba.fromSeq(out.toVector)
    }

  /**
   * Applies `node` to every element and keeps only the `Some` results
   * (map + filter fused), preserving the collection type.
   *
   * {{{ fetch ~> collectEach(parseOpt) ~> load }}}
   */
  def collectEach[A, B](node: Node[A, Option[B]]): CollectEach[A, B] = new CollectEach(node)

  /** Like `collectEach`, but runs up to `parallelism` elements concurrently. */
  def collectEachPar[A, B](parallelism: Int)(node: Node[A, Option[B]]): CollectEachPar[A, B] =
    new CollectEachPar(parallelism, node)

  /**
   * Keeps the elements for which `pred` holds, preserving the collection type.
   * `pred` is a full node, so it can be config-driven or effectful.
   *
   * {{{ fetch ~> filterEach(isValid) ~> load }}}
   */
  def filterEach[A](pred: Node[A, Boolean])(using Name, TypeName[A]): CollectEach[A, A] =
    collectEach((Node.identity[A] & pred).map { case (a, keep) => if (keep) Some(a) else None })

  /** Like `filterEach`, but runs up to `parallelism` predicates concurrently. */
  def filterEachPar[A](parallelism: Int)(pred: Node[A, Boolean])(using
    Name,
    TypeName[A]
  ): CollectEachPar[A, A] =
    collectEachPar(parallelism)(
      (Node.identity[A] & pred).map { case (a, keep) => if (keep) Some(a) else None }
    )

  /** Attaches `each` / `eachPar` steps, inferring the collection type from the batch */
  extension [X, CA](self: Node[X, CA]) {
    def ~>[A, B, C[_]](step: Each[A, B])(using ba: Batchable[CA, A, C]): Node[X, C[B]] =
      self ~> Node.Batch[CA, A, B, C[B]](
        step.node,
        1,
        (ca: CA) => ba.toSeq(ca),
        (xs: Seq[B]) => ba.fromSeq(xs)
      )

    def ~>[A, B, C[_]](step: EachPar[A, B])(using
      ba: Batchable[CA, A, C]
    ): Node[X, C[B]] =
      self ~> Node.Batch[CA, A, B, C[B]](
        step.node,
        step.parallelism,
        (ca: CA) => ba.toSeq(ca),
        (xs: Seq[B]) => ba.fromSeq(xs)
      )

    def ~>[A, B, C[_]](step: CollectEach[A, B])(using
      ba: Batchable[CA, A, C]
    ): Node[X, C[B]] =
      self ~> Node.Batch[CA, A, Option[B], C[B]](
        step.node,
        1,
        (ca: CA) => ba.toSeq(ca),
        (xs: Seq[Option[B]]) => ba.fromSeq(xs.flatten)
      )

    def ~>[A, B, C[_]](step: CollectEachPar[A, B])(using
      ba: Batchable[CA, A, C]
    ): Node[X, C[B]] =
      self ~> Node.Batch[CA, A, Option[B], C[B]](
        step.node,
        step.parallelism,
        (ca: CA) => ba.toSeq(ca),
        (xs: Seq[Option[B]]) => ba.fromSeq(xs.flatten)
      )
  }

  /**
   * Context-aware validation helper for Reader[T, Node[A, B]]
   */
  private object ReaderValidationHelper {
    def ensureImpl[T, A, B](
      fa: Reader[T, Node[A, B]],
      input: Seq[ValidationCheck[T, A]],
      output: Seq[ValidationCheck[T, B]],
      change: Seq[ValidationCheck[T, (A, B)]]
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
  }

  /**
   * Extension methods for conditional branching and validation on Reader-wrapped Nodes.
   *
   * Validation functions use curried form (T => A => Option[String]) to match
   * the Reader pattern. This allows validations to be context-aware and composable.
   */
  extension [T, A, B](fa: Reader[T, Node[A, B]]) {

    /**
     * Conditional branching for Reader-wrapped Nodes.
     * Works with both Reader and plain Node branches.
     * Supports both plain conditions ((_: User).age > 18) and curried conditions ((cfg: Config) => (u: User) => ...).
     * Config types accumulate via intersection, output types via union.
     *
     * @example
     * {{{
     * val result = sourceReader
     *   .If((_: User).age > 18)(readerBranchA)
     *   .ElseIf((cfg: Config) => (u: User) => u.age > cfg.minAge)(branchB)
     *   .Else(nodeBranchC)
     * }}}
     */
    def If[C, Branch, Cond](condition: Cond)(branch: Branch)(using
      condLift: ConditionLift[B, Cond],
      branchLift: BranchLift[B, C, Branch]
    ): ReaderPartialConditionalBuilder[T & condLift.Config & branchLift.Config, A, B, C] = {
      type R = T & condLift.Config & branchLift.Config
      ReaderPartialConditionalBuilder(
        fa.asInstanceOf[Reader[R, Node[A, B]]],
        List(
          (
            condLift.lift(condition).asInstanceOf[R => Predicate[B]],
            branchLift.lift(branch).asInstanceOf[Reader[R, Node[B, C]]]
          )
        )
      )
    }

    /** Conditional branching based purely on config/environment (ignores data)
     * @example
     * {{{
     * sourceReader
     *   .IfCtx((_: Config).isBackfill)(backfillBranch)
     *   .Else(normalBranch)
     * }}}
     */
    def IfCtx[C, Branch](condition: T => Boolean)(branch: Branch)(using
      branchLift: BranchLift[B, C, Branch]
    ): ReaderPartialConditionalBuilder[T & branchLift.Config, A, B, C] = {
      type R = T & branchLift.Config
      ReaderPartialConditionalBuilder(
        fa.asInstanceOf[Reader[R, Node[A, B]]],
        List(
          (
            ((t: R) => Predicate((_: B) => condition(t))).asInstanceOf[R => Predicate[B]],
            branchLift.lift(branch).asInstanceOf[Reader[R, Node[B, C]]]
          )
        )
      )
    }

  }

  /**
   * Reader validation extensions using implicit class to allow default arguments
   * without conflicting with Node extension methods.
   */
  implicit class ReaderNodeValidationOps[T, A, B](private val fa: Reader[T, Node[A, B]])
      extends AnyVal {

    /**
     * Adds multiple context-aware validation checks in one call.
     * Uses curried form (T => A => Option[String]) for validators that need config access.
     *
     * @param input validation functions for input (curried: T => A => Option[String])
     * @param output validation functions for output (curried: T => B => Option[String])
     * @param change validation functions for the transformation
     * @return a new Reader with all validations applied
     *
     * @example
     * {{{
     * val checkMin = (cfg: Config) => (x: Int) => if (x >= cfg.min) None else Some("too small")
     * val node = Reader[Config, Node[Int, Int]] { _ => Node(identity) }
     *   .ensure(input = Seq(checkMin))
     * }}}
     */
    def ensure(
      input: Seq[ValidationCheck[T, A]] = Nil,
      output: Seq[ValidationCheck[T, B]] = Nil,
      change: Seq[ValidationCheck[T, (A, B)]] = Nil
    ): Reader[T, Node[A, B]] = ReaderValidationHelper.ensureImpl(fa, input, output, change)
  }

  /**
   * LineageCollectionOps - helper class for lineage graph operations.
   */
  class LineageCollectionOps[T](val items: Seq[T]) {

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
      /* Reject duplicate pipeline names: the graph keys on them. */
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

      val pipelineOutputs         = lineages.map(l => (l.name, l.outputs)).toMap
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
      val ind           = "    " * indent
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
