# Common Patterns

## Chain pipelines

```scala
import etl4s._

val stringify = Pipeline((i: Int) => i.toString)
val addBang    = Pipeline((s: String) => s + "!")

val pipeline = stringify ~> addBang
```
`pipeline` has type `Int => String`.

## Parallel extraction

```scala
val e1 = Extract(1)
val e2 = Extract("two")
val e3 = Extract(3.0)

val combined = e1 & e2 & e3
```
`combined` produces an `(Int, String, Double)` tuple.

`&` runs the branches sequentially. Its concurrent counterpart `&>` runs them
in parallel, but only under a concurrent effect via `.compile[Future]`:

```scala
import etl4s._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val parse = Node[String, Int](_.trim.toInt)
val inc   = Node[Int, Int](_ + 1)
val neg   = Node[Int, Int](-_)

val fanOut = parse ~> (inc &> neg)

fanOut.compile[Future].unsafeRun("10")
```
`fanOut` has type `Node[String, (Int, Int)]`.

You will get:
```
Future((11, -10))
```

See [Effect polymorphism](effect-polymorphism.md) for `.compile[Id|Try|Future]`
and how to add your own effect. Use `*`/`*>` to combine nodes that take
*different* inputs into a tuple result.

## Batch processing

Run a node over each element of a collection with `each`, `eachPar(n)`, or
`eachSlice(size)`:

```scala
import etl4s._

val src   = Node[Unit, List[Int]](_ => List(1, 2, 3))
val clean = Node[Int, Int](_ + 1)

(src ~> each(clean)).unsafeRun(())
(src ~> eachPar(2)(clean)).unsafeRun(())
```

You will get:
```
List(2, 3, 4)
List(2, 3, 4)
```

See [Batch operations](batch.md) for the full set and how they behave under
each effect.

## Debugging with `.tap`

Inspect values mid-pipeline without affecting the flow:

```scala
import etl4s._

val extract   = Extract("42")
val transform = Transform[String, Int](_.toInt)
val load      = Load[Int, Int](_ * 2)

val pipeline =
  extract.tap(data => println(s"Extracted: $data")) ~>
  transform.tap(result => println(s"Transformed: $result")) ~>
  load

pipeline.unsafeRun(())
```

Prints:
```
Extracted: 42
Transformed: 42
```
and returns `84`.

## Sequential side-effects with `>>`

Run multiple effects in order, same input to each. Only the last result is returned:

```scala
val logStart  = Node[String, Unit](s => println(s"Starting: $s"))
val logMiddle = Node[String, Unit](s => println(s"Processing: $s"))
val process   = Node[String, Int](_.length)

val pipeline = logStart >> logMiddle >> process

pipeline.unsafeRun("hello")
```

Prints:
```
Starting: hello
Processing: hello
```
and returns `5`.

Useful for setup/teardown:

```scala
val clearCache    = Node { println("Clearing cache...") }
val warmCache     = Node { println("Warming cache...") }
val mainPipeline  = Node[Unit, String](_ => "done")

val pipeline = clearCache >> warmCache >> mainPipeline

pipeline.unsafeRun(())
```

Prints:
```
Clearing cache...
Warming cache...
```
and returns `done`.

## Conditional branching

Route the input based on conditions. The predicates see the incoming value,
each branch produces the result:

```scala
val classify = Node.identity[Int]
  .If(_ < 0)(Node(_ => "negative"))
  .ElseIf(_ == 0)(Node(_ => "zero"))
  .Else(Node(_ => "positive"))

classify.unsafeRun(-5)
classify.unsafeRun(0)
classify.unsafeRun(10)
```

You will get:
```
"negative"
"zero"
"positive"
```

## Error handling with `.onFailure`

Provide fallback values:

```scala
val risky = Node[String, Int](_.toInt)
  .onFailure(_ => -1)

risky.unsafeRun("42")
risky.unsafeRun("bad")
```

You will get:
```
42
-1
```

## Retry with backoff

```scala
val flaky = Node[String, Response](callExternalApi)
  .withRetry(maxAttempts = 3, initialDelayMs = 100, backoffFactor = 2.0)
```

## Reactive pipelines

Carry the outcome in the data and branch on it:

```scala
val upstream = Transform[String, Int](_.length)

val downstream = upstream
  .If((n: Int) => n == 0)(Transform(_ => "FALLBACK"))
  .Else(Transform(n => s"Length: $n"))

downstream.unsafeRun("")
downstream.unsafeRun("hello")
```

You will get:
```
"FALLBACK"
"Length: 5"
```
