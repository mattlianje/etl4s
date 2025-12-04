# Tracing

When writing dataflows, you often want to:

- Log what's happening at each step
- Have downstream nodes react to upstream failures
- Get timing and debug info after execution

`Trace` is a shared, append-only log that flows through your pipeline. Nodes can write to it, read from it, and react to what happened upstream.

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

Under the hood, two [ThreadLocal](https://docs.oracle.com/javase/8/docs/api/java/lang/ThreadLocal.html) channels (like Unix stdout/stderr) accumulate across your pipeline. Thread-safe with minimal overhead.

## Nodes That React to Each Other

Downstream nodes can see what happened upstream:

```scala
val A = Transform[String, Int] { s =>
  if (s.isEmpty) Trace.error("empty")
  s.length
}

val B = Transform[Int, String] { n =>
  if (Trace.hasErrors) "FALLBACK" else s"len: $n"
}

val pipeline = A ~> B

pipeline.unsafeRun("hello")  // "len: 5"
pipeline.unsafeRun("")       // "FALLBACK"
```

No wiring required. `B` checks `Trace.hasErrors` and switches to fallback mode.

## Live Pipeline State

Check elapsed time, error counts, etc. mid-execution:

```scala
val p = Transform[String, String] { input =>
  if (Trace.getElapsedTimeMillis > 1000) {
    "TIMEOUT"
  } else {
    input.toUpperCase
  }
}
```

## Quick Reference

### Write
| Method | Description |
|:-------|:------------|
| `Trace.log(message)` | Log any value |
| `Trace.error(err)` | Log an error |

### Check
| Method | Description |
|:-------|:------------|
| `Trace.hasErrors` | Any errors so far? |
| `Trace.hasLogs` | Any logs so far? |

### Read
| Method | Description |
|:-------|:------------|
| `Trace.getCurrent` | Full current state |
| `Trace.getLogs` | All logs |
| `Trace.getErrors` | All errors |
| `Trace.getElapsedTimeMillis` | Time since start |
| `Trace.getLogCount` | Number of logs |
| `Trace.getErrorCount` | Number of errors |
| `Trace.getLastLog` | Most recent log |
| `Trace.getLastError` | Most recent error |

## Trace Result

After calling `.unsafeRunTrace()` or `.safeRunTrace()`:

| Property | Type | Description |
|:---------|:-----|:------------|
| `result` | `A` or `Try[A]` | Execution result |
| `logs` | `List[Any]` | All logged values |
| `errors` | `List[Any]` | All errors |
| `timeElapsedMillis` | `Long` | Total execution time |
| `hasErrors` | `Boolean` | Quick error check |
