# Tradeoffs

## Tracing

`Trace` uses two `ThreadLocal` lists - one for logs, one for errors. Appending is O(1) but not zero cost. If you're doing `Trace.log()` in a tight loop over millions of records, you're allocating. For normal ETL granularity (log per stage, per batch, per failure), you won't notice.

## Parallelism

`&>` uses `Future` under the hood (for now). You bring the `ExecutionContext`:

```scala
import scala.concurrent.ExecutionContext.Implicits.global

val parallel = (e1 &> e2 &> e3).zip
```

!!! warning "Keep in mind"
    - Each `&>` branch submits a `Future`
	- So avoid folding over some interrable of size `n` with `&>` since it would fire off a syscall for an OS thread `n` number of times

The plan is to make an effect polymorphic **etl4s** concurrency subsystem (soon) ...so you could plug in ZIO, CE, Kyo or keep `Future`.

## Telemetry

`Tel` compiles to no-ops when there's no `Etl4sTelemetry` in implicit scope. Zero allocation, zero overhead.
