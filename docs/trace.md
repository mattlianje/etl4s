# Tracing

Sometimes you want the result of a run *plus* how long it took, without
reaching for a stopwatch or an external metrics library.

`Trace[A]` is a tiny, pure return value: the result and the elapsed time.
Nothing ambient, no shared state, no threading concerns.

```scala
val countChars = Transform[String, Int] { s =>
  s.length
}

val res: Int          = countChars.unsafeRun("hello")
val trace: Trace[Int] = countChars.unsafeRunTrace("hello")
```

You will get `res = 5`, and `trace`:
```
Trace(
  result = 5,
  timeElapsedMillis = 2L
)
```

## Trace Result

After calling `.unsafeRunTrace()`:

| Property            | Type     | Description                    |
|:--------------------|:---------|:-------------------------------|
| `result`            | `A`      | Execution result               |
| `timeElapsedMillis` | `Long`   | Total execution time in millis |
| `seconds`           | `Double` | Elapsed time in seconds        |

If the node throws, the exception propagates out of `unsafeRunTrace`. Wrap the
call in your own `Try` if you want to capture failures.

## Bring your own observability

`etl4s` deliberately keeps `Trace` minimal. If you need structured logging,
metrics, or distributed tracing, wire in your own tools with a plain `tap` or
inside your node bodies. You keep full control over your observability stack:

```scala
val instrumented = Transform[String, Int] { s =>
  logger.info(s"processing $s")
  s.length
} ~> tap(n => metrics.gauge("length", n))
```
