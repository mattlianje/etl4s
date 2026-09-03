/**
 * A lightweight, zero-dep library for writing whiteboard-style dataflows
 * using the core [[Node]] and [[Reader]] abstractions.
 *
 * Compose pipelines with the overloaded `~>` operator.
 */
package object etl4s {
  import scala.language.{higherKinds, implicitConversions}
  import scala.language.experimental.macros
  import scala.concurrent.{Future, ExecutionContext}
  import scala.concurrent.duration._
  import scala.util.{Try, Success, Failure}
  import scala.util.control.NonFatal
  import scala.annotation.unchecked.uncheckedVariance
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
    def unsafeRun()(implicit ev: Any <:< A): B =
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
     * doubledNode("hello") // returns 10
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
     * process("5") // returns "~~~~~"
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
     * pipeline("hello")
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
     * val getAll = getName & getAge & getEmail  // returns (String, Int, String) - auto-flattened!
     * }}}
     */
    def &[A1 <: A, C, O](that: Node[A1, C])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
    ): Node[A1, O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[A1, O] =
        Node.Par[A1, B, C, O](this, that, false, (b: B, c: C) => ta.append(b, c))
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Parallel composition with a Reader-wrapped node.
     */
    def &[T, A1 <: A, C, O](
      that: Reader[T, Node[A1, C]]
    )(implicit ta: TupleAppend.Aux[B @uncheckedVariance, C, O]): Reader[T, Node[A1, O]] = {
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
     * sequentially: same result, no `ExecutionContext` needed.
     *
     * @tparam C the output type of the other node
     * @param that the other node to run concurrently
     * @return a new Node that returns a tuple of both results
     *
     * @example
     * {{{
     * val fetchUser = Node[UserId, User](id => fetchFromDB(id))
     * val fetchPrefs = Node[UserId, Preferences](id => fetchPrefsFromCache(id))
     * val fetchBoth = fetchUser &> fetchPrefs  // concurrent under as[Future]
     * val fetchAll = fetchUser &> fetchPrefs &> fetchSettings 
     * }}}
     */
    def &>[A1 <: A, C, O](that: Node[A1, C])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
    ): Node[A1, O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[A1, O] =
        Node.Par[A1, B, C, O](this, that, true, (b: B, c: C) => ta.append(b, c))
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Concurrent parallel composition with a Reader-wrapped node.
     */
    def &>[T, A1 <: A, C, O](that: Reader[T, Node[A1, C]])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
    ): Reader[T, Node[A1, O]] = {
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
     * val both      = parseName * parseAge // Node[(String, Int), (Name, Age)]
     * both.unsafeRun(("alice", 30))
     * }}}
     */
    def *[C, D, O](that: Node[C, D])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, D, O]
    ): Node[(A, C), O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[(A, C), O] =
        Node.Prod[A, C, B, D, O](this, that, false, (b: B, d: D) => ta.append(b, d))
      combined.fold(result)(lin => result.withLineage(lin))
    }

    /**
     * Concurrent product composition: like `*`, but marks the two independent
     * branches to run concurrently under an effect runner (via [[Effect]]).
     * The plain synchronous `unsafeRun` runs them sequentially.
     */
    def *>[C, D, O](that: Node[C, D])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, D, O]
    ): Node[(A, C), O] = {
      val combined = (this.getLineage, that.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Node[(A, C), O] =
        Node.Prod[A, C, B, D, O](this, that, true, (b: B, d: D) => ta.append(b, d))
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
     *   .onFailure(_ => 0)  // return 0 for invalid strings
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
    def |[T, A2, BB >: B](
      right: Reader[T, Node[A2, BB]]
    ): Reader[T, Node[Either[A, A2], BB]] = {
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
     * val ch  = dbl + up    // Node[Either[Int, String], Either[Int, String]]
     * }}}
     */
    def +[C, D](right: Node[C, D]): Node[Either[A, C], Either[B, D]] =
      Node.Fanin(this.map((b: B) => Left[B, D](b)), right.map((d: D) => Right[B, D](d)))

    /** Choice with a Reader-wrapped node. */
    def +[T, C, D](
      right: Reader[T, Node[C, D]]
    ): Reader[T, Node[Either[A, C], Either[B, D]]] = {
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
    def <|>[T, A1 <: A, BB >: B](
      right: Reader[T, Node[A1, BB]]
    ): Reader[T, Node[A1, BB]] = {
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
      Node.Mapped(this, (b: BB) => flattener(b))

    /**
     * The pipeline as a flat list of leaf stages (name + in/out type names),
     * in execution order.
     *
     * For diagrams use `.toMermaid` / `.toDot`, which render this node's internal
     * stage structure. (On a `Seq` of nodes/readers those same names render the
     * declared data-lineage graph instead - see [[LineageCollectionOps]].)
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
    )(implicit name: Name, inN: TypeName[A], outN: TypeName[B]): Node[A, B] =
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
    def apply[B](value: => B)(implicit name: Name, outN: TypeName[B]): Node[Any, B] =
      Node((_: Any) => value)

    def identity[A](implicit name: Name, tn: TypeName[A]): Node[A, A] =
      Node(a => a)
    def unit[B](value: => B)(implicit name: Name, outN: TypeName[B]): Node[Unit, B] =
      Node((_: Unit) => value)
    def effect(action: => Unit)(implicit name: Name): Node[Unit, Unit] =
      Node((_: Unit) => action)
    def pure[A, B](b: B)(implicit name: Name, inN: TypeName[A], outN: TypeName[B]): Node[A, B] =
      Node((_: A) => b)

    def requires[T, A, B](
      f: T => A => B
    )(implicit name: Name, inN: TypeName[A], outN: TypeName[B]): Reader[T, Node[A, B]] = {
      Reader(
        config => Step(name.value, f(config), inN, outN, name.fullName),
        sourceName = Some(name)
      )
    }

    /** A leaf: an opaque `A => B` with its captured name and type names. */
    final case class Step[A, B](
      name: String,
      run: A => B,
      inN: TypeName[A],
      outN: TypeName[B],
      fullName: String = ""
    ) extends Node[A, B]

    /** `a ~> b` : sequential composition. */
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
     * `a * b` (sequential) / `a *> b` (concurrent) product composition:
     */
    final case class Prod[A, C, B, D, O](
      left: Node[A, B],
      right: Node[C, D],
      concurrent: Boolean,
      append: (B, D) => O
    ) extends Node[(A, C), O]

    /**
     * `each` / `eachPar` apply `inner` to every element of a batch,
     * preserving the collection type.
     */
    final case class Batch[CA, A, B, CB](
      inner: Node[A, B],
      parallelism: Int,
      toSeq: CA => Seq[A],
      fromSeq: Seq[B] => CB
    ) extends Node[CA, CB]

    /**
     * `a.ensure(...)` / `a.ensurePar(...)` : reified input/output/change
     * validation around `inner`. Each check is `V => Option[String]` (None = ok)
     */
    final case class Validate[A, B](
      inner: Node[A, B],
      input: Seq[A => Option[String]],
      output: Seq[B => Option[String]],
      change: Seq[((A, B)) => Option[String]],
      concurrent: Boolean
    ) extends Node[A, B]

    /** `a >> b` run `a` for effect, then `b` */
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
     * `If(...) / .ElseIf(...) / .Else(...)` : reified conditional branching
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
        // Scala 2's GADT inference widens `b` to B while `g` keeps the pattern
        // type, so the cast is required (and sound) here.
        val gg = g.asInstanceOf[B => Any]
        (a: A) => { val b = cf(a); gg(b); b }
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
        // Scala 2's GADT inference widens the source's output to Any while the
        // branch fns keep their type...
        val sf  = interpret(source)
        val bfs = branches.map { case (p, n) =>
          (p.f.asInstanceOf[Any => Boolean], interpret(n).asInstanceOf[Any => B])
        }
        val dfO = default.map(n => interpret(n).asInstanceOf[Any => B])
        (a: A) => {
          val b: Any = sf(a)
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
     * user `implicit`).
     */
    private[etl4s] def interpretF[F[_], A, B](
      node: Node[A, B]
    )(implicit E: Effect[F]): A => F[B] =
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
            case x :: Nil  => E.map(x)((v: bb) => List(v))
            case x :: rest => E.map(E.both(x, group(rest)))((t: (bb, List[bb])) => t._1 :: t._2)
          }
          def runGroups(gs: List[Seq[a]]): F[List[bb]] = gs match {
            case Nil       => E.pure(Nil)
            case g :: rest =>
              E.flatMap(group(g.toList.map(innerF)))((head: List[bb]) =>
                E.map(runGroups(rest))((tail: List[bb]) => head ++ tail)
              )
          }
          (
            (in: ca) =>
              E.map(runGroups(b.toSeq(in).grouped(par).toList))((bs: List[bb]) => b.fromSeq(bs))
          ).asInstanceOf[A => F[B]]
        case v: Validate[a, b] =>
          val cf   = interpretF[F, a, b](v.inner)
          val conc = v.concurrent

          /** Checks in a stage run concurrently via `both` when `conc`, else
            * sequentially; a stage fails via `delay(throw ...)` (an F-failure)
            */
          def collect(fos: List[F[Option[String]]]): F[List[Option[String]]] =
            fos match {
              case Nil       => E.pure(Nil)
              case x :: Nil  => E.map(x)((o: Option[String]) => List(o))
              case x :: rest =>
                if (conc)
                  E.map(E.both(x, collect(rest)))((t: (Option[String], List[Option[String]])) =>
                    t._1 :: t._2
                  )
                else
                  E.flatMap(x)((o: Option[String]) =>
                    E.map(collect(rest))((os: List[Option[String]]) => o :: os)
                  )
            }
          def guard[T](stage: String, checks: Seq[T => Option[String]], t: T): F[Unit] =
            if (checks.isEmpty) E.pure(())
            else
              E.flatMap(collect(checks.toList.map((c: T => Option[String]) => E.delay(c(t)))))(
                (os: List[Option[String]]) => E.delay(raiseIfInvalid(stage, os.flatten))
              )
          (
            (in: a) =>
              E.flatMap(guard("Input", v.input, in))((_: Unit) =>
                E.flatMap(cf(in))((out: b) =>
                  E.flatMap(guard("Output", v.output, out))((_: Unit) =>
                    E.map(guard[(a, b)]("Change", v.change, (in, out)))((_: Unit) => out)
                  )
                )
              )
          ).asInstanceOf[A => F[B]]
        case Then(x, y) =>
          val xf = interpretF[F, Any, Any](x.asInstanceOf[Node[Any, Any]])
          val yf = interpretF[F, Any, Any](y.asInstanceOf[Node[Any, Any]])
          ((a: A) => E.flatMap(xf(a))((_: Any) => yf(a))).asInstanceOf[A => F[B]]
        case Mapped(inner, g) =>
          val cf = interpretF[F, Any, Any](inner.asInstanceOf[Node[Any, Any]])
          (a: A) => E.map(cf(a))(g.asInstanceOf[Any => B])
        case t: Tap[a, b] =>
          val cf = interpretF[F, a, b](t.inner)
          val g  = t.g
          ((in: a) => E.flatMap(cf(in))(bv => E.map(E.delay(g(bv)))((_: Any) => bv)))
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
            E.flatMap(sf(a)) { b =>
              val fn = interpretF[F, A, B](k.asInstanceOf[Any => Node[A, B]](b)); fn(a)
            }
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
          val dfO = default.asInstanceOf[Option[Node[Any, B]]].map(n => interpretF[F, Any, B](n))
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
     * `unsafeRun` folds the graph through [[interpretF]] into F
     */
    final class Runner[A, B, F[_]](node: Node[A, B])(implicit E: Effect[F]) {

      /** Runs the pipeline on `a`, producing an `F[B]` */
      def unsafeRun(a: A): F[B] = {
        val fn = interpretF[F, A, B](node); fn(a)
      }

      /** Runs an input-free pipeline (`Node[Any, B]`), producing an `F[B]` */
      def unsafeRun()(implicit ev: Any <:< A): F[B] = {
        val fn = interpretF[F, A, B](node); fn(null.asInstanceOf[A])
      }
    }

    /**
     * One leaf stage of a pipeline: its name, in/out type names, and the
     * fully-qualified path of its binding (empty when uncaptured/synthetic).
     */
    final case class StageInfo(name: String, in: String, out: String, fullName: String = "")

    /**
     * Flattens the tree to its leaf stages, in execution order.
     */
    def stages(node: Node[_, _]): List[StageInfo] = node match {
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
      case f: Fanin[_, _, _]               => stages(f.left) ++ stages(f.right)
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
      node: Node[_, _],
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
      def isSyntheticIdentity(f: Node[_, _]): Boolean = f match {
        case Step(name, _, inN, outN, fullName) =>
          name == "input" && fullName.isEmpty && inN.show == outN.show
        case _ => false
      }
      val labelEdges = showTypes && !typesOnNodes
      def edge(a: Int, b: Int, label: String = "", dashed: Boolean = false): Unit = {
        val arrow     = if (dashed) "-.->" else "-->"
        val labelPart = if (label.isEmpty) "" else s"""|"$label"|"""
        lines += s"  n$a $arrow$labelPart n$b"
      }

      def go(f: Node[_, _]): (List[(Int, String)], List[(Int, String)]) = f match {
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
        case f: Fanin[_, _, _] =>
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
      node: Node[_, _],
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
      def isSyntheticIdentity(f: Node[_, _]): Boolean = f match {
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

      def go(f: Node[_, _]): (List[(Int, String)], List[(Int, String)]) = f match {
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
        case f: Fanin[_, _, _] =>
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
     * as dashed `subgraph cluster_*` boxes instead of marker nodes. Edges clip
     * to the cluster boundary via `compound=true` + `lhead`/`ltail`, so the
     * grouping reads as one gesture. Branch predicates ride the node label
     * (`price (else)`); wire types ride the edge.
     */
    def dotClustered(
      node: Node[_, _],
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

      def isSyntheticIdentity(f: Node[_, _]): Boolean = f match {
        case Step(name, _, inN, outN, fullName) =>
          name == "input" && fullName.isEmpty && inN.show == outN.show
        case _ => false
      }

      def go(f: Node[_, _]): (List[Port], List[Port]) = f match {
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
          /* alternatives (`|`/`orElse`/`?`) yield one value — funnel through a
             merge point rather than draw an N×M mesh */
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
        case f: Fanin[_, _, _] =>
          /* Either-merge: only one branch fires, so dash inputs and outputs */
          val start       = counter
          val (lin, lout) = go(f.left)
          val (rin, rout) = go(f.right)
          group("|", start, counter)
          ((lin ++ rin).map(_.copy(dashed = true)), (lout ++ rout).map(_.copy(dashed = true)))
        case OrElse(l, r) =>
          /* `<|>` runs both on the same input; the fallback fires only on failure */
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
            for (a <- sout; bi <- branchIO; b <- bi._1)
              emitEdge(a.id, b.id, a.tpe, dashed = true)
            val passthrough = if (default.isEmpty) sout else Nil
            (sin, (branchIO.flatMap(_._2) ++ passthrough).map(_.copy(dashed = true)))
          }
      }

      val (rootIn, rootOut) = go(node)
      if (labelEdges) {
        /* anchors carry the outer in/out types; broadcast blocks
           (`&`/`&>`/orElse) share one anchor per distinct input */
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
   * ships `Id` (synchronous), `Future`, and `Try`; add your own with one instance.
   *
   * `both` powers `&>`/`eachPar`/`ensurePar`. Its default sequences via `flatMap`;
   * override it (as `Future` does with `zip`) when `F` can run the two in parallel.
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

    implicit val idEffect: Effect[Id] = new Effect[Id] {
      def pure[A](a: A): Id[A]                                           = a
      def delay[A](thunk: => A): Id[A]                                   = thunk
      def flatMap[A, B](fa: Id[A])(f: A => Id[B]): Id[B]                 = f(fa)
      def handleErrorWith[A](fa: => Id[A])(h: Throwable => Id[A]): Id[A] =
        try fa
        catch { case NonFatal(t) => h(t) }
    }

    implicit def futureEffect(implicit ec: ExecutionContext): Effect[Future] =
      new Effect[Future] {
        def pure[A](a: A): Future[A]                                   = Future.successful(a)
        def delay[A](thunk: => A): Future[A]                           = Future(thunk)
        def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)
        def handleErrorWith[A](fa: => Future[A])(h: Throwable => Future[A]): Future[A] =
          (try fa
          catch { case NonFatal(t) => Future.failed(t) }).recoverWith { case t => h(t) }
        override def both[A, B](fa: Future[A], fb: Future[B]): Future[(A, B)] = fa.zip(fb)
      }

    implicit val tryEffect: Effect[Try] = new Effect[Try] {
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
    def apply[A, B](
      func: A => B
    )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Pipeline[A, B]           = Node(func)
    def apply[B](value: => B)(implicit n: Name, o: TypeName[B]): Pipeline[Any, B] = Node(value)
    def pure[A](implicit n: Name, t: TypeName[A]): Pipeline[A, A]                 = Node.identity[A]
    def requires[T, A, B](
      f: T => A => B
    )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Node[A, B]] =
      Node.requires[T, A, B](f)
  }

  object Extract {
    def apply[A, B](func: A => B)(implicit n: Name, i: TypeName[A], o: TypeName[B]): Extract[A, B] =
      Node(func)
    def apply[B](value: => B)(implicit n: Name, o: TypeName[B]): Extract[Any, B] = Node(value)
    def pure[A](implicit n: Name, t: TypeName[A]): Extract[A, A]                 = Node.identity[A]
    def requires[T, A, B](
      f: T => A => B
    )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Node[A, B]] =
      Node.requires[T, A, B](f)
  }

  object Transform {
    def apply[A, B](
      func: A => B
    )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Transform[A, B]           = Node(func)
    def apply[B](value: => B)(implicit n: Name, o: TypeName[B]): Transform[Any, B] = Node(value)
    def pure[A](implicit n: Name, t: TypeName[A]): Transform[A, A] = Node.identity[A]
    def requires[T, A, B](
      f: T => A => B
    )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Node[A, B]] =
      Node.requires[T, A, B](f)
  }

  object Load {
    def apply[A, B](func: A => B)(implicit n: Name, i: TypeName[A], o: TypeName[B]): Load[A, B] =
      Node(func)
    def apply[B](value: => B)(implicit n: Name, o: TypeName[B]): Load[Any, B] = Node(value)
    def pure[A](implicit n: Name, t: TypeName[A]): Load[A, A]                 = Node.identity[A]
    def requires[T, A, B](
      f: T => A => B
    )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Node[A, B]] =
      Node.requires[T, A, B](f)
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
   * Makes an existing node depend on some configuration type `T`, reusing its
   * `A => B` shape.
   *
   * @example
   * {{{
   * val configNode = someNode.requires[Config] { config => input =>
   *   input * config.multiplier
   * }
   * configNode.provideContext(Config(5)).unsafeRun(10) // 50
   * }}}
   */
  implicit class NodeRequiresOps[A, B](val node: Node[A, B]) {
    def requires[T](
      f: T => A => B
    )(implicit name: Name, inN: TypeName[A], outN: TypeName[B]): Reader[T, Node[A, B]] =
      Reader(
        config => Node.Step(name.value, f(config), inN, outN, name.fullName),
        sourceName = Some(name)
      )
  }

  /**
   * Compiles the pure pipeline into the effect `F`, returning a [[Node.Runner]] whose
   * `unsafeRun` folds the graph into that `F`: `pipeline.compile[Future].unsafeRun(x)`
   * yields `Future[B]`, and `compile[Try].unsafeRun(x)` a `Try[B]`.
   */
  implicit class NodeRunFOps[A, B](val node: Node[A, B]) {
    def compile[F[_]](implicit E: Effect[F]): Node.Runner[A, B, F] =
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
    // Type-lambda syntax: `({ type L[X] = SomeType })#L` builds a type constructor
    // from a multi-parameter type so it fits a single-parameter typeclass like
    // `HasMetadata[F[_]]`, keeping the 2.12/2.13/3.x cross builds simple.
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
    def &[C, O](
      fb: Reader[T1, Node[A, C]]
    )(implicit ta: TupleAppend.Aux[B @uncheckedVariance, C, O]): Reader[T1, Node[A, O]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Reader[T1, Node[A, O]] = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA & nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def &[T2, C, R, O](fb: Reader[T2, Node[A, C]])(implicit
      compat: ReaderCompat[T1, T2, R],
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
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

    def &[C, O](node: Node[A, C])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
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
      *  &>: Reader(Node) &> {Reader(Node) | Reader(Node) compat | Node}
      */
    def &>[C, O](fb: Reader[T1, Node[A, C]])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
    ): Reader[T1, Node[A, O]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result: Reader[T1, Node[A, O]] = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA &> nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def &>[T2, C, R, O](fb: Reader[T2, Node[A, C]])(implicit
      compat: ReaderCompat[T1, T2, R],
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
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

    def &>[C, O](node: Node[A, C])(implicit
      ta: TupleAppend.Aux[B @uncheckedVariance, C, O]
    ): Reader[T1, Node[A, O]] = {
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
      *  |: Reader(Node) | {Reader(Node) | Reader(Node) compat | Node}
      * Fan-in: routes an `Either` input to whichever branch matches, merging to a common output.
      */
    def |[C](fb: Reader[T1, Node[C, B]]): Reader[T1, Node[Either[A, C], B]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA | nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def |[T2, C, R](fb: Reader[T2, Node[C, B]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[Either[A, C], B]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[Either[A, C], B]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA | nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

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
      *  +: Reader(Node) + {Reader(Node) | Reader(Node) compat | Node}
      * Choice: routes an `Either` input through independent branches, keeping the `Either` on output.
      */
    def +[C, D](fb: Reader[T1, Node[C, D]]): Reader[T1, Node[Either[A, C], Either[B, D]]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA + nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def +[T2, C, D, R](fb: Reader[T2, Node[C, D]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[Either[A, C], Either[B, D]]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[Either[A, C], Either[B, D]]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA + nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

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
      *  <|>: Reader(Node) <|> {Reader(Node) | Reader(Node) compat | Node}
      * Error fallback: runs the left node; on any throwable runs the right node on the original input.
      */
    def <|>(fb: Reader[T1, Node[A, B]]): Reader[T1, Node[A, B]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = for {
        nodeA <- fa
        nodeB <- fb
      } yield nodeA <|> nodeB
      combined.fold(result)(lin => result.withLineage(lin))
    }

    def <|>[T2, R](fb: Reader[T2, Node[A, B]])(implicit
      compat: ReaderCompat[T1, T2, R]
    ): Reader[R, Node[A, B]] = {
      val combined = (fa.getLineage, fb.getLineage) match {
        case (Some(l1), Some(l2)) => Some(l1.combine(l2))
        case (Some(l), None)      => Some(l)
        case (None, Some(l))      => Some(l)
        case _                    => None
      }
      val result = Reader[R, Node[A, B]] { (env: R) =>
        val nodeA = fa.run(compat.toT1(env))
        val nodeB = fb.run(compat.toT2(env))
        nodeA <|> nodeB
      }
      combined.fold(result)(lin => result.withLineage(lin))
    }

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

    /**
     * Config-free structural views so we can treat Reader[?, Node[?, ?]]'s
     * like Nodes without having to use .provideContext
     */
    private def skeleton: Node[A, B] = fa.run(null.asInstanceOf[T1])
    def stages: List[Node.StageInfo] = skeleton.stages

  }

  /**
   * Result container for traced pipeline execution
   *
   * @tparam A the result type
   * @param result the final result value
   * @param timeElapsedMillis execution duration in milliseconds
   */
  case class Trace[+A](
    result: A,
    timeElapsedMillis: Long = 0L
  ) {
    def seconds: Double = timeElapsedMillis / 1000.0
  }

  /**
   * tap utility function availble for infix use
   */
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
  trait TupleAppend[-A, -B] {
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
      def Extract[A, B](
        f: T => A => B
      )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Extract[A, B]] =
        etl4s.Extract.requires[T, A, B](f)

      def Transform[A, B](
        f: T => A => B
      )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Transform[A, B]] =
        etl4s.Transform.requires[T, A, B](f)

      def Load[A, B](
        f: T => A => B
      )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Load[A, B]] =
        etl4s.Load.requires[T, A, B](f)

      def Pipeline[A, B](
        f: T => A => B
      )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Pipeline[A, B]] =
        etl4s.Pipeline.requires[T, A, B](f)

      def Node[A, B](
        f: T => A => B
      )(implicit n: Name, i: TypeName[A], o: TypeName[B]): Reader[T, Node[A, B]] =
        etl4s.Node.requires[T, A, B](f)

      def tap[A](f: T => A => Any)(implicit name: Name, tn: TypeName[A]): Reader[T, Node[A, A]] =
        Reader { ctx =>
          etl4s.Node.Step[A, A](name.value, { a => f(ctx)(a); a }, tn, tn, name.fullName)
        }

      /**
       * Starts a context-aware pipeline with a conditional branch on config
       * Without a trailing `Else`, unmatched inputs pass through unchanged
       */
      def If[A, C, Branch](condition: T => Boolean)(branch: Branch)(implicit
        branchLift: BranchLift[T, A, C, Branch],
        tn: TypeName[A]
      ): ReaderPartialConditionalBuilder[T, A, A, C] =
        ReaderPartialConditionalBuilder(
          Reader.pure(etl4s.Node.identity[A](Name("input"), tn)),
          List(((t: T) => Predicate((_: A) => condition(t)), branchLift.lift(branch)))
        )
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
    // A single node/reader renders its *structural* graph (stages + in/out
    // types); `.toJson` still emits the lineage metadata. A `Seq` of them
    // renders the declared *data-lineage* graph (see seqRenderer).
    implicit def nodeRenderer[A, B]: LineageRenderer[Node[A, B]] =
      new LineageRenderer[Node[A, B]] {
        def toJson(t: Node[A, B]): String    = new LineageCollectionOps(Seq(t)).toJson
        def toDot(t: Node[A, B]): String     = Node.dot(t)
        def toMermaid(t: Node[A, B]): String = Node.mermaid(t)
      }

    implicit def readerRenderer[R, A, B]: LineageRenderer[Reader[R, Node[A, B]]] =
      new LineageRenderer[Reader[R, Node[A, B]]] {
        def toJson(t: Reader[R, Node[A, B]]): String    = new LineageCollectionOps(Seq(t)).toJson
        def toDot(t: Reader[R, Node[A, B]]): String     = Node.dot(t.run(null.asInstanceOf[R]))
        def toMermaid(t: Reader[R, Node[A, B]]): String =
          Node.mermaid(t.run(null.asInstanceOf[R]))
      }

    implicit def seqRenderer[T]: LineageRenderer[Seq[T]] = new LineageRenderer[Seq[T]] {
      def toJson(items: Seq[T]): String    = new LineageCollectionOps(items).toJson
      def toDot(items: Seq[T]): String     = new LineageCollectionOps(items).toDot
      def toMermaid(items: Seq[T]): String = new LineageCollectionOps(items).toMermaid
    }
  }

  /**
   * Extension methods for lineage rendering using typeclass.
   */
  /**
   * Renders the *structural* graph of a single `Node` or `Reader`, with options.
   */
  trait StructuralRenderer[T] {
    def node(t: T): Node[_, _]
  }
  object StructuralRenderer {
    implicit def nodeStructural[A, B]: StructuralRenderer[Node[A, B]] =
      new StructuralRenderer[Node[A, B]] {
        def node(t: Node[A, B]): Node[_, _] = t
      }
    implicit def readerStructural[R, A, B]: StructuralRenderer[Reader[R, Node[A, B]]] =
      new StructuralRenderer[Reader[R, Node[A, B]]] {
        def node(t: Reader[R, Node[A, B]]): Node[_, _] = t.run(null.asInstanceOf[R])
      }
  }

  /**
   * Extension methods for lineage/structural rendering.
   *
   * The no-arg `toDot` / `toMermaid` render with defaults: types on the arrows,
   * one wire type per edge (structural for a single `Node`/`Reader`, lineage
   * for a `Seq`). The overloads on a single `Node`/`Reader` take options: pass
   * `showTypes = false` to drop the signatures, `typesOnNodes = true` for the
   * older `name<br/>In => Out` node-label style, or `direction`
   * (`Direction.LR` / `RL` / `TB` / `BT`).
   */
  implicit class LineageOps[T](val t: T)(implicit renderer: LineageRenderer[T]) {
    def toJson: String    = renderer.toJson(t)
    def toDot: String     = renderer.toDot(t)
    def toMermaid: String = renderer.toMermaid(t)

    def toDot(
      showTypes: Boolean = true,
      direction: Direction = Direction.LR,
      typesOnNodes: Boolean = false
    )(implicit sr: StructuralRenderer[T]): String =
      Node.dot(sr.node(t), showTypes, direction, typesOnNodes)

    def toMermaid(
      showTypes: Boolean = true,
      direction: Direction = Direction.LR,
      typesOnNodes: Boolean = false
    )(implicit sr: StructuralRenderer[T]): String =
      Node.mermaid(sr.node(t), showTypes, direction, typesOnNodes)

    /** DOT with fan-outs / conditionals / joins drawn as dashed clusters. */
    def toDotClustered(implicit sr: StructuralRenderer[T]): String =
      Node.dotClustered(sr.node(t), true, Direction.LR)
    def toDotClustered(
      showTypes: Boolean = true,
      direction: Direction = Direction.LR
    )(implicit sr: StructuralRenderer[T]): String =
      Node.dotClustered(sr.node(t), showTypes, direction)
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

  /**
   * Extension methods for adding validation to Nodes.
   * 
   * Validation functions return None if valid, Some(errorMessage) if invalid.
   * All validation errors are collected into the thrown exception message.
   */
  implicit class NodeValidationOps[A, B](val node: Node[A, B]) {

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

  }

  /**
   * Non-exhaustive conditional builder for Nodes. `ElseIf` is a macro
   * delegation ([[ConditionalOpsMacros.partialElseIf]]) that captures the
   * predicate's source text at the call site; see [[NodeConditionalOps]].
   */
  case class PartialConditionalBuilder[A, B, C](
    sourceNode: Node[A, B],
    branches: List[(Predicate[B], Node[B, C])]
  ) {
    def ElseIf(condition: B => Boolean)(branch: Node[B, C]): PartialConditionalBuilder[A, B, C] =
      macro ConditionalOpsMacros.partialElseIf[A, B, C]

    def Else(branch: Node[B, C]): Node[A, C] =
      Node.Cond[A, B, C](sourceNode, branches, Some(branch))
  }

  /**
   * Exhaustive conditional builder for Nodes. `ElseIf` is a macro delegation
   * ([[ConditionalOpsMacros.completeElseIf]]) for source-text capture.
   */
  case class CompleteConditionalBuilder[A, B, C](
    sourceNode: Node[A, B],
    branches: List[(Predicate[B], Node[B, C])],
    defaultBranch: Node[B, C]
  ) {
    def ElseIf(condition: B => Boolean)(branch: Node[B, C]): CompleteConditionalBuilder[A, B, C] =
      macro ConditionalOpsMacros.completeElseIf[A, B, C]

    def build: Node[A, C] =
      Node.Cond[A, B, C](sourceNode, branches, Some(defaultBranch))
  }

  /**
   * Conditional branching for Nodes. The DSL param stays `B => Boolean` so
   * `_ < 0` elaborates naturally; the [[ConditionalOpsMacros.nodeIf]] macro
   * rewrites the call to wrap the predicate in a source-carrying
   * [[Predicate]] (Scala 2 won't drive underscore-lambda inference through an
   * expected type of `Predicate[B]`, so we can't just take one directly).
   */
  implicit class NodeConditionalOps[A, B](val node: Node[A, B]) {
    def If[C](condition: B => Boolean)(branch: Node[B, C]): PartialConditionalBuilder[A, B, C] =
      macro ConditionalOpsMacros.nodeIf[A, B, C]
  }

  implicit def conditionalBuilderToNode[A, B, C](
    builder: CompleteConditionalBuilder[A, B, C]
  ): Node[A, C] =
    builder.build

  /**
   * Starts a pipeline with a conditional branch on the input value.
   * Without a trailing `Else`, unmatched inputs pass through unchanged.
   * Macro delegation ([[ConditionalOpsMacros.topIf]]) captures predicate
   * source text at the call site.
   */
  def If[A](condition: A => Boolean): ValueIfStart[A] = macro ConditionalOpsMacros.topIf[A]

  class ValueIfStart[A](condition: Predicate[A]) {
    def apply[C](branch: Node[A, C])(implicit tn: TypeName[A]): PartialConditionalBuilder[A, A, C] =
      PartialConditionalBuilder(
        Node.identity[A](Name("input"), tn),
        List((condition, branch))
      )
  }

  /** Uses a partial builder as a Node: unmatched inputs pass through unchanged. */
  implicit def partialConditionalBuilderToNode[A, B, C](
    builder: PartialConditionalBuilder[A, B, C]
  )(implicit ev: B <:< C): Node[A, C] =
    Node.Cond[A, B, C](builder.sourceNode, builder.branches, None)

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
   * Type class for lifting conditions (plain, source-carrying, or curried) to
   * Reader-aware form. Enables clean syntax:
   * `.If(_.age > 18)` instead of `.If((_: Cfg) => _.age > 18)`.
   * Result is a `T => Predicate[B]` so branch source text (when carried by an
   * explicit [[Predicate]]) can be surfaced in diagram labels. Plain
   * `B => Boolean` inputs on the Reader side land with an empty source; the
   * value-side (`Node.If`) captures source automatically via the macro.
   */
  trait ConditionLift[T, B, Cond] {
    def lift(cond: Cond): T => Predicate[B]
  }

  object ConditionLift extends ConditionLiftMidPriority {
    // Highest priority: explicit Predicate (source already captured)
    implicit def predicateIdentity[T, B]: ConditionLift[T, B, Predicate[B]] =
      new ConditionLift[T, B, Predicate[B]] {
        def lift(cond: Predicate[B]): T => Predicate[B] = _ => cond
      }
  }

  trait ConditionLiftMidPriority extends ConditionLiftLowPriority {
    // Curried form: pass through (mid priority)
    implicit def curriedIdentity[T, B]: ConditionLift[T, B, T => B => Boolean] =
      new ConditionLift[T, B, T => B => Boolean] {
        def lift(cond: T => B => Boolean): T => Predicate[B] = t => Predicate(cond(t))
      }
  }

  trait ConditionLiftLowPriority {
    // Plain form: lift to ignore context (lower priority)
    implicit def plainToReader[T, B]: ConditionLift[T, B, B => Boolean] =
      new ConditionLift[T, B, B => Boolean] {
        def lift(cond: B => Boolean): T => Predicate[B] = _ => Predicate(cond)
      }
  }

  /**
   * Non-exhaustive conditional builder for Reader-wrapped nodes.
   */
  case class ReaderPartialConditionalBuilder[T, A, B, C](
    sourceReader: Reader[T, Node[A, B]],
    branches: List[(T => Predicate[B], Reader[T, Node[B, C]])]
  ) {
    def ElseIf[Branch, Cond](condition: Cond)(branch: Branch)(implicit
      condLift: ConditionLift[T, B, Cond],
      branchLift: BranchLift[T, B, C, Branch]
    ): ReaderPartialConditionalBuilder[T, A, B, C] =
      ReaderPartialConditionalBuilder(
        sourceReader,
        branches :+ (condLift.lift(condition), branchLift.lift(branch))
      )

    /** Add branch based purely on config/environment (ignores data). */
    def ElseIfCtx[Branch](condition: T => Boolean)(branch: Branch)(implicit
      branchLift: BranchLift[T, B, C, Branch]
    ): ReaderPartialConditionalBuilder[T, A, B, C] =
      ReaderPartialConditionalBuilder(
        sourceReader,
        branches :+ ((t: T) => Predicate((_: B) => condition(t)), branchLift.lift(branch))
      )

    def Else[Branch](branch: Branch)(implicit
      lift: BranchLift[T, B, C, Branch]
    ): Reader[T, Node[A, C]] = Reader { ctx =>
      val sourceNode        = sourceReader.run(ctx)
      val evaluatedBranches = branches.map { case (check, readerBranch) =>
        (check(ctx), readerBranch.run(ctx))
      }
      val evaluatedDefault = lift.lift(branch).run(ctx)
      Node.Cond[A, B, C](sourceNode, evaluatedBranches, Some(evaluatedDefault))
    }
  }

  /**
   * Exhaustive conditional builder for Reader-wrapped nodes.
   */
  case class ReaderCompleteConditionalBuilder[T, A, B, C](
    sourceReader: Reader[T, Node[A, B]],
    branches: List[(T => Predicate[B], Reader[T, Node[B, C]])],
    defaultBranch: Reader[T, Node[B, C]]
  ) {
    def ElseIf[Branch, Cond](condition: Cond)(branch: Branch)(implicit
      condLift: ConditionLift[T, B, Cond],
      branchLift: BranchLift[T, B, C, Branch]
    ): ReaderCompleteConditionalBuilder[T, A, B, C] =
      ReaderCompleteConditionalBuilder(
        sourceReader,
        branches :+ (condLift.lift(condition), branchLift.lift(branch)),
        defaultBranch
      )

    /** Add branch based purely on config/environment (ignores data). */
    def ElseIfCtx[Branch](condition: T => Boolean)(branch: Branch)(implicit
      branchLift: BranchLift[T, B, C, Branch]
    ): ReaderCompleteConditionalBuilder[T, A, B, C] =
      ReaderCompleteConditionalBuilder(
        sourceReader,
        branches :+ ((t: T) => Predicate((_: B) => condition(t)), branchLift.lift(branch)),
        defaultBranch
      )

    def build: Reader[T, Node[A, C]] = Reader { ctx =>
      val sourceNode        = sourceReader.run(ctx)
      val evaluatedBranches = branches.map { case (check, readerBranch) =>
        (check(ctx), readerBranch.run(ctx))
      }
      val evaluatedDefault = defaultBranch.run(ctx)
      Node.Cond[A, B, C](sourceNode, evaluatedBranches, Some(evaluatedDefault))
    }
  }

  implicit def readerConditionalBuilderToReader[T, A, B, C](
    builder: ReaderCompleteConditionalBuilder[T, A, B, C]
  ): Reader[T, Node[A, C]] = builder.build

  /** Uses a Reader partial builder as a Reader: unmatched inputs pass through unchanged. */
  implicit def readerPartialConditionalBuilderToReader[T, A, B, C](
    builder: ReaderPartialConditionalBuilder[T, A, B, C]
  )(implicit ev: B <:< C): Reader[T, Node[A, C]] = Reader { ctx =>
    val sourceNode        = builder.sourceReader.run(ctx)
    val evaluatedBranches = builder.branches.map { case (check, readerBranch) =>
      (check(ctx), readerBranch.run(ctx))
    }
    Node.Cond[A, B, C](sourceNode, evaluatedBranches, None)
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
    implicit def listBatchable[A]: Batchable[List[A], A, List] = new Batchable[List[A], A, List] {
      def toSeq(ca: List[A]): Seq[A]      = ca
      def fromElems(xs: Seq[A]): List[A]  = xs.toList
      def fromSeq[B](xs: Seq[B]): List[B] = xs.toList
    }
    implicit def vectorBatchable[A]: Batchable[Vector[A], A, Vector] =
      new Batchable[Vector[A], A, Vector] {
        def toSeq(ca: Vector[A]): Seq[A]      = ca
        def fromElems(xs: Seq[A]): Vector[A]  = xs.toVector
        def fromSeq[B](xs: Seq[B]): Vector[B] = xs.toVector
      }
    implicit def seqBatchable[A]: Batchable[Seq[A], A, Seq] = new Batchable[Seq[A], A, Seq] {
      def toSeq(ca: Seq[A]): Seq[A]      = ca
      def fromElems(xs: Seq[A]): Seq[A]  = xs
      def fromSeq[B](xs: Seq[B]): Seq[B] = xs
    }
    implicit def setBatchable[A]: Batchable[Set[A], A, Set] = new Batchable[Set[A], A, Set] {
      def toSeq(ca: Set[A]): Seq[A]      = ca.toSeq
      def fromElems(xs: Seq[A]): Set[A]  = xs.toSet
      def fromSeq[B](xs: Seq[B]): Set[B] = xs.toSet
    }
    implicit def iterableBatchable[A]: Batchable[Iterable[A], A, Iterable] =
      new Batchable[Iterable[A], A, Iterable] {
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
   * Applies `node` to every element of a batch, preserving the collection type.
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
  def eachSlice[CA, E, B, C[_]](size: Int)(node: Node[CA, B])(implicit
    ba: Batchable[CA, E, C]
  ): Node[CA, C[B]] =
    Node { ca =>
      val out = ba.toSeq(ca).grouped(size).map(chunk => node.f(ba.fromElems(chunk)))
      ba.fromSeq(out.toVector)
    }

  /**
   * Applies `node` to every element and keeps only the `Some` results, dropping
   * `None` (a batch-flavoured `collect`), preserving the collection type.
   *
   * {{{ fetch ~> collectEach(parseOpt) ~> load }}}
   */
  def collectEach[A, B](node: Node[A, Option[B]]): CollectEach[A, B] = new CollectEach(node)

  /** Like `collectEach`, but runs up to `parallelism` elements concurrently. */
  def collectEachPar[A, B](parallelism: Int)(node: Node[A, Option[B]]): CollectEachPar[A, B] =
    new CollectEachPar(parallelism, node)

  /**
   * Keeps only the elements for which the predicate node holds, preserving the
   * collection type (a batch-flavoured `filter`).
   *
   * {{{ fetch ~> filterEach(isValid) ~> load }}}
   */
  def filterEach[A](pred: Node[A, Boolean])(implicit n: Name, tn: TypeName[A]): CollectEach[A, A] =
    collectEach((Node.identity[A] & pred).map { case (a, keep) => if (keep) Some(a) else None })

  /** Like `filterEach`, but runs up to `parallelism` predicates concurrently. */
  def filterEachPar[A](parallelism: Int)(pred: Node[A, Boolean])(implicit
    n: Name,
    tn: TypeName[A]
  ): CollectEachPar[A, A] =
    collectEachPar(parallelism)(
      (Node.identity[A] & pred).map { case (a, keep) => if (keep) Some(a) else None }
    )

  /** Attaches `each` / `eachPar` steps, inferring the collection type from the batch. */
  implicit class BatchNodeOps[X, CA](private val self: Node[X, CA]) {
    def ~>[A, B, C[_]](step: Each[A, B])(implicit
      ba: Batchable[CA, A, C]
    ): Node[X, C[B]] =
      self ~> Node.Batch[CA, A, B, C[B]](
        step.node,
        1,
        (ca: CA) => ba.toSeq(ca),
        (xs: Seq[B]) => ba.fromSeq(xs)
      )

    def ~>[A, B, C[_]](step: EachPar[A, B])(implicit
      ba: Batchable[CA, A, C]
    ): Node[X, C[B]] =
      self ~> Node.Batch[CA, A, B, C[B]](
        step.node,
        step.parallelism,
        (ca: CA) => ba.toSeq(ca),
        (xs: Seq[B]) => ba.fromSeq(xs)
      )

    def ~>[A, B, C[_]](step: CollectEach[A, B])(implicit
      ba: Batchable[CA, A, C]
    ): Node[X, C[B]] =
      self ~> Node.Batch[CA, A, Option[B], C[B]](
        step.node,
        1,
        (ca: CA) => ba.toSeq(ca),
        (xs: Seq[Option[B]]) => ba.fromSeq(xs.flatten)
      )

    def ~>[A, B, C[_]](step: CollectEachPar[A, B])(implicit
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
   * Conditional branching for Reader-wrapped Nodes.
   * Supports both plain conditions ((_: User).age > 18) and curried conditions ((cfg: Config) => (u: User) => ...).
   */
  implicit class ReaderConditionalOps[T, A, B](val reader: Reader[T, Node[A, B]]) {
    def If[C, Branch, Cond](condition: Cond)(branch: Branch)(implicit
      condLift: ConditionLift[T, B, Cond],
      branchLift: BranchLift[T, B, C, Branch]
    ): ReaderPartialConditionalBuilder[T, A, B, C] =
      ReaderPartialConditionalBuilder(
        reader,
        List((condLift.lift(condition), branchLift.lift(branch)))
      )

    /** Conditional branching based purely on config/environment (ignores data). */
    def IfCtx[C, Branch](condition: T => Boolean)(branch: Branch)(implicit
      branchLift: BranchLift[T, B, C, Branch]
    ): ReaderPartialConditionalBuilder[T, A, B, C] =
      ReaderPartialConditionalBuilder(
        reader,
        List(((t: T) => Predicate((_: B) => condition(t)), branchLift.lift(branch)))
      )
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
     *       checkPositive
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
