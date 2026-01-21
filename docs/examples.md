# Common Patterns

## Chain pipelines

```scala
import etl4s._

val A = Pipeline((i: Int) => i.toString)
val B = Pipeline((s: String) => s + "!")

val C = A ~> B  // Int => String
```

## Parallel extraction

```scala
val e1 = Extract(1)
val e2 = Extract("two")
val e3 = Extract(3.0)

val combined = e1 & e2 & e3  // (Int, String, Double)
```

## Debugging with `.tap`

Inspect values mid-pipeline without affecting the flow:

```scala
val pipeline = extract
  .tap(data => println(s"Extracted: $data"))
  ~> transform
  .tap(result => println(s"Transformed: $result"))
  ~> load
```

## Sequential side-effects with `>>`

Run multiple effects in order, same input to each. Only the last result is returned:

```scala
val logStart  = Node[String, Unit](s => println(s"Starting: $s"))
val logMiddle = Node[String, Unit](s => println(s"Processing: $s"))
val process   = Node[String, Int](_.length)

val pipeline = logStart >> logMiddle >> process

pipeline.unsafeRun("hello")
// prints: Starting: hello
// prints: Processing: hello
// returns: 5
```

Useful for setup/teardown:

```scala
val clearCache = Node { println("Clearing cache...") }
val warmCache  = Node { println("Warming cache...") }

val pipeline = clearCache >> warmCache >> mainPipeline
```

## Conditional branching

Route data based on conditions:

```scala
val classify = Node[Int, Int](identity)
  .If(_ < 0)(Node(_ => "negative"))
  .ElseIf(_ == 0)(Node(_ => "zero"))
  .Else(Node(_ => "positive"))

classify.unsafeRun(-5)  // "negative"
classify.unsafeRun(0)   // "zero"
classify.unsafeRun(10)  // "positive"
```

## Error handling with `.onFailure`

Provide fallback values:

```scala
val risky = Node[String, Int](_.toInt)
  .onFailure(_ => -1)

risky.unsafeRun("42")   // 42
risky.unsafeRun("bad")  // -1
```

## Retry with backoff

```scala
val flaky = Node[String, Response](callExternalApi)
  .withRetry(maxAttempts = 3, initialDelayMs = 100, backoffMultiplier = 2.0)
```

## Reactive pipelines with Trace

Branch on upstream errors:

```scala
val upstream = Transform[String, Int] { input =>
  if (input.isEmpty) Trace.error("Empty input")
  input.length
}

val downstream = Transform[Int, String] { value =>
  if (Trace.hasErrors) "FALLBACK"
  else s"Length: $value"
}

val pipeline = upstream ~> downstream

pipeline.unsafeRun("")      // "FALLBACK"
pipeline.unsafeRun("hello") // "Length: 5"
```
