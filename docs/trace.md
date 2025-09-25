# Pipeline Tracing with `Trace`

Transform your nodes from blind boxes into aware, living components. With `Trace` and `runTraced`, nodes can access their execution context, react to upstream problems, and adapt their behavior dynamically.

`Trace` calls are no-ops with regular `.unsafeRun()` - zero overhead until you need insights.

## Basic Traced Execution

```scala
import etl4s._

val pipeline = Transform[String, Int](_.length)
val trace = pipeline.unsafeRunTraced("hello")

trace.result     // 5
trace.timing     // Some(2) - milliseconds  
trace.hasErrors  // false
```

## Safe Traced Execution

```scala
val pipeline = Transform[String, Int] { input =>
  if (input.isEmpty) throw new RuntimeException("Empty!")
  input.length
}

val trace = pipeline.safeRunTraced("")
trace.result.isFailure  // true - Try[Int] 
trace.timing.isDefined  // true - still get timing
```

## Logging During Execution

```scala
val pipeline = Transform[String, Int] { input =>
  Trace.log("Processing started")
  val result = input.length * 2
  Trace.log(s"Result: $result")
  result
}

val trace = pipeline.unsafeRunTraced("test")
trace.result  // 8
trace.logs    // List("Processing started", "Result: 8")
```

## Validation Errors

```scala
val pipeline = Transform[String, Int] { input =>
  if (input.isEmpty) Trace.logValidation("Empty input")
  input.length
}

val trace = pipeline.unsafeRunTraced("")
trace.hasErrors        // true
trace.validationErrors // List("Empty input")
```

## Cross-Node Communication

Nodes can react to upstream validation errors:

```scala
val upstream = Transform[String, Int] { input =>
  if (input.isEmpty) Trace.logValidation("Empty input")
  input.length
}

val downstream = Transform[Int, String] { value =>
  if (Trace.hasValidationErrors) "FALLBACK" else s"Length: $value"
}

val pipeline = upstream ~> downstream

pipeline.unsafeRunTraced("hello")  // "Length: 5"
pipeline.unsafeRunTraced("")       // "FALLBACK"
```

## Live Execution State

```scala
val pipeline = Transform[String, String] { input =>
  val current = Trace.current
  if (current.timing.exists(_ > 1000)) {
    "TIMEOUT"  // Fast path for slow executions
  } else {
    input.toUpperCase
  }
}
```

## Trace Object Methods

| Method | Description | Example |
|:-------|:------------|:--------|
| `Trace.log(message)` | Log any value | `Trace.log("Processing started")` |
| `Trace.logValidation(error)` | Log validation error | `Trace.logValidation("Invalid format")` |  
| `Trace.hasValidationErrors` | Check for errors | `if (Trace.hasValidationErrors) ...` |
| `Trace.current` | Get live execution state | `val state = Trace.current` |
| `Trace.logs` | Current logs | `val logs = Trace.logs` |
| `Trace.validationErrors` | Current errors | `val errors = Trace.validationErrors` |

## Trace Result Properties

| Property | Type | Description |
|:---------|:-----|:------------|
| `result` | `A` or `Try[A]` | Execution result |
| `logs` | `List[Any]` | Collected log values |
| `timing` | `Option[Long]` | Execution time in ms |
| `validationErrors` | `List[Any]` | Validation errors |
| `hasErrors` | `Boolean` | Quick error check |
| `seconds` | `Option[Double]` | Timing in seconds |

This makes **etl4s** pipelines fully observable and self-aware - nodes can communicate, react to problems, and provide rich debugging information automatically.
