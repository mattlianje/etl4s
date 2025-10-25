# Pipeline Tracing with `Trace`

Nodes can access and update their runtime state:

- **Access** current execution state (`Trace.current`, `Trace.hasErrors`, etc)
- **Update** runtime state (`Trace.log()`, `Trace.error()`). Thread-safe and append only
- **Share** state automatically across the entire pipeline. All your Nodes "know" what happened before them and can act with this knowledge

Use `runTrace` to get all your logs and errors cleanly after you've run your pipeline.

**How it works:** Trace uses two [ThreadLocal](https://docs.oracle.com/javase/8/docs/api/java/lang/ThreadLocal.html) channels (like Unix stdout/stderr) that automatically accumulate across your pipeline - thread-safe with minimal overhead:

```scala
val A = Transform[String, Int] { s =>
  Trace.log("Processing")
  s.length
}

val res: Int = A.unsafeRun("hello")  // 5
val trace: Trace[Int] = A.unsafeRunTrace("hello")
```

```
Trace(
  result = 5,
  logs = List("Processing"),
  errors = List(),
  timeElapsedMillis = 2L
)
```

## Nodes That React to Each Other

Downstream nodes can instantly see what happened upstream and adapt their behavior.

```scala
val A = Transform[String, Int] { s =>
  if (s.isEmpty) Trace.error("empty")
  s.length
}

val B = Transform[Int, String] { n =>
  if (Trace.hasErrors) "FALLBACK" else s"len: $n"  
}

val pipeline = A ~> B

pipeline.unsafeRun("hello")  /* "len: 5" */
pipeline.unsafeRun("")       /* "FALLBACK" */
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
  timeElapsedMillis = 2L
)
```

## Live Pipeline State

In any `Node` you can check what is happening right now with `Trace.getCurrent`

```scala
val p = Transform[String, String] { input =>
  val current = Trace.getCurrent
  if (current.timeElapsedMillis > 1000) {
    "TIMEOUT"  /* Fast path for slow executions */
  } else {
    input.toUpperCase
  }
}
```

**Or use the direct getters:**
```scala
val p = Transform[String, String] { input =>
  if (Trace.getElapsedTimeMillis > 1000) {
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

### Logging and Error Reporting
| Method | Description | Example |
|:-------|:------------|:--------|
| `Trace.log(message)` | Log any value | `Trace.log("Processing started")` |
| `Trace.error(err)` | Log error | `Trace.error("Invalid format")` |

### State Checking
| Method | Description | Example |
|:-------|:------------|:--------|
| `Trace.hasErrors` | Check for errors | `if (Trace.hasErrors) ...` |
| `Trace.hasLogs` | Check for logs | `if (Trace.hasLogs) ...` |

### Getting Current State
| Method | Description | Example |
|:-------|:------------|:--------|
| `Trace.getCurrent` | Get live execution state | `val state = Trace.getCurrent` |
| `Trace.getLogs` | Current logs | `val logs = Trace.getLogs` |
| `Trace.getErrors` | Current errors | `val errors = Trace.getErrors` |
| `Trace.getLogsAsStrings` | Logs as strings | `val logStrings = Trace.getLogsAsStrings` |
| `Trace.getErrorsAsStrings` | Errors as strings | `val errorStrings = Trace.getErrorsAsStrings` |

### Timing Information
| Method | Description | Example |
|:-------|:------------|:--------|
| `Trace.getElapsedTimeMillis` | Time in milliseconds | `val ms = Trace.getElapsedTimeMillis` |
| `Trace.getElapsedTimeSeconds` | Time in seconds | `val secs = Trace.getElapsedTimeSeconds` |

### Counts and Recent Items
| Method | Description | Example |
|:-------|:------------|:--------|
| `Trace.getLogCount` | Number of logs | `val count = Trace.getLogCount` |
| `Trace.getErrorCount` | Number of errors | `val count = Trace.getErrorCount` |
| `Trace.getLastLog` | Most recent log | `val last = Trace.getLastLog` |
| `Trace.getLastError` | Most recent error | `val last = Trace.getLastError` |

## Trace Result Properties

| Property | Type | Description |
|:---------|:-----|:------------|
| `result` | `A` or `Try[A]` | Execution result |
| `logs` | `List[Any]` | Collected log values |
| `timeElapsedMillis` | `Long` | Execution time in ms |
| `errors` | `List[Any]` | Errors |
| `hasErrors` | `Boolean` | Quick error check |
| `seconds` | `Double` | Timing in seconds |

This makes **etl4s** pipelines fully observable and self-aware - nodes can communicate, react to problems, and provide rich debugging information automatically.
