# Pipeline Tracing with `Trace`

Build aware, living components with `Trace`. Nodes can access their execution context, react to upstream problems, and adapt their behavior dynamically.

**Trace information is collected during all runs.** Use `runTraced` methods to get the full execution details.

## Regular Runs vs Traced Runs

All run methods collect trace information internally:

```scala
import etl4s._

val pipeline = Transform[String, Int] { input =>
  Trace.log("Processing input")
  input.length
}

// Regular run - trace collected but not returned
val result: Int = pipeline.unsafeRun("hello")  // 5

// Traced run - full trace returned with result
val trace = pipeline.unsafeRunTraced("hello")
```

```
Trace(
  result = 5,
  logs = List("Processing input"),
  timeElapsed = 2L,
  validationErrors = List()
)
```

## Pipeline with Logging & Validation

```scala
val toUpper = Transform[String, String] { input =>
  Trace.log("Converting to uppercase")
  if (input.isEmpty) Trace.logValidation("Empty input provided")
  Thread.sleep(10)
  input.toUpperCase
}

val getLength = Transform[String, Int] { input =>
  Trace.log("Calculating length")
  if (input.length < 3) Trace.logValidation("Input too short")
  
  /* React to upstream problems */
  val delay = if (Trace.hasValidationErrors) 1 else 5
  Thread.sleep(delay)
  input.length
}

val pipeline = toUpper ~> getLength

val trace = pipeline.unsafeRunTraced("hi")
```

```
Trace(
  result = 2,
  logs = List("Converting to uppercase", "Calculating length"),
  timeElapsed = 16L,
  validationErrors = List("Input too short")
)
```

## Safe Traced Execution

```scala
val pipeline = Transform[String, Int] { input =>
  if (input.isEmpty) throw new RuntimeException("Empty!")
  input.length
}

val trace = pipeline.safeRunTraced("")
trace.result.isFailure  // true - Try[Int] 
trace.timeElapsed >= 0  // true - still get timing
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
  if (current.timeElapsed > 1000) {
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
| `timeElapsed` | `Long` | Execution time in ms |
| `validationErrors` | `List[Any]` | Validation errors |
| `hasErrors` | `Boolean` | Quick error check |
| `seconds` | `Double` | Timing in seconds |

This makes **etl4s** pipelines fully observable and self-aware - nodes can communicate, react to problems, and provide rich debugging information automatically.
