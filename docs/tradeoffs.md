# Tradeoffs

## Tracing

`Trace` is a plain immutable value holding the result and elapsed time — no
ambient state, no allocation beyond the wrapper. If you need logging, metrics,
or distributed tracing, bring your own tools inside node bodies.

## Parallelism

`&>` uses `Future` under the hood (for now). You bring the `ExecutionContext`:

```scala
import scala.concurrent.ExecutionContext.Implicits.global

val parallel = e1 &> e2 &> e3
```

!!! warning "Keep in mind"
    - Each `&>` branch submits a `Future`
	- So avoid folding over some interrable of size `n` with `&>` since it would fire off a syscall for an OS thread `n` number of times

The plan is to make an effect polymorphic **etl4s** concurrency subsystem (soon) ...so you could plug in ZIO, CE, Kyo or keep `Future`.
