# Pipeline Tracing with `Trace`

Nodes can access and update their runtime state:

- **Access** current execution state (`Trace.current`, `Trace.hasErrors`, etc)
- **Update** runtime state (`Trace.log()`, `Trace.error()`). Thread-safe and append only
- **Share** state automatically across the entire pipeline. All your Nodes "know" what happened before them and can act with this knowledge

Use `runTrace` to get all your logs and errors cleanly after you've run your pipeline.

**How it works:** Trace uses two [ThreadLocal](https://docs.oracle.com/javase/8/docs/api/java/lang/ThreadLocal.html) channels (like Unix stdout/stderr) that automatically accumulate across your pipeline - thread-safe with minimal overhead:

```scala
val p = Transform[String, Int] { input =>
  Trace.log("Processing input")
  input.length
}

val res: Int = p.unsafeRun("hello")  // 5
val resTrace: Trace[Int] = p.unsafeRunTrace("hello")
```

```
Trace(
  result = 5,
  logs = List("Processing input"),
  errors = List(),
  timeElapsed = 2L
)
```

## Nodes That React to Each Other

Downstream nodes can instantly see what happened upstream and adapt their behavior.

```scala
val upstream = Transform[String, Int] { input =>
  if (input.isEmpty) Trace.error("Empty input")
  input.length
}

val downstream = Transform[Int, String] { value =>
  if (Trace.hasErrors) "FALLBACK" else s"Length: $value"  
}

val p = upstream ~> downstream

p.unsafeRun("hello")  /* "Length: 5" */
p.unsafeRun("")       /* "FALLBACK" */
```

**No wiring required.** The downstream node automatically knows about upstream problems and switches to fallback mode since it can access the run's `Trace`

## Debug Any Pipeline Instantly

```scala
val p = Transform[String, Int] { input =>
  Trace.log("Processing started")
  if (input.isEmpty) Trace.error("Empty input!")
  input.length * 2
}

val trace = p.unsafeRunTrace("test")
```

**Get everything in one shot:**
```
Trace(
  result = 8,
  logs = List("Processing started"),
  errors = List(),
  timeElapsed = 2L
)
```

## Live Pipeline State

In any `Node` you can check what is happening right now with `Trace.current`

```scala
val p = Transform[String, String] { input =>
  val current = Trace.current
  if (current.timeElapsed > 1000) {
    "TIMEOUT"  /* Fast path for slow executions */
  } else {
    input.toUpperCase
  }
}
```

**React to problems instantly:**
```scala
if (Trace.hasErrors) {
  /* Switch to fallback mode */
} else {
  /* Continue normal processing */
}
```

## Quick Reference


| Method | Description | Example |
|:-------|:------------|:--------|
| `Trace.log(message)` | Log any value | `Trace.log("Processing started")` |
| `Trace.error(err)` | Log error | `Trace.error("Invalid format")` |  
| `Trace.hasErrors` | Check for errors | `if (Trace.hasErrors) ...` |
| `Trace.current` | Get live execution state | `val state = Trace.current` |
| `Trace.logs` | Current logs | `val logs = Trace.logs` |
| `Trace.errors` | Current errors | `val errors = Trace.errors` |

## Trace Result Properties

| Property | Type | Description |
|:---------|:-----|:------------|
| `result` | `A` or `Try[A]` | Execution result |
| `logs` | `List[Any]` | Collected log values |
| `timeElapsed` | `Long` | Execution time in ms |
| `errors` | `List[Any]` | Errors |
| `hasErrors` | `Boolean` | Quick error check |
| `seconds` | `Double` | Timing in seconds |

This makes **etl4s** pipelines fully observable and self-aware - nodes can communicate, react to problems, and provide rich debugging information automatically.
