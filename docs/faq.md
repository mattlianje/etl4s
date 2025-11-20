# FAQ

## General

**Q: Is this built on Cats Effect or ZIO?**  
No. Pure Scala with no runtime dependencies. If you're already using Cats Effect or ZIO, use those directly.

**Q: Does this replace Spark/Flink/Pandas?**  
No. etl4s structures your pipeline logic. You still use Spark/Flink/Pandas for actual data processing. etl4s makes that code composable and type-safe.

**Q: Is this a workflow orchestrator like Airflow?**  
No. etl4s doesn't schedule jobs or manage distributed execution. Use Airflow or any scheduler for that. etl4s structures the code those tools run.

**Q: Can I use this in production?**  
Yes. Type safety catches bugs at compile time. No runtime dependencies means nothing to break. But the project is still young - expect some rough edges.

## Usage

**Q: What does `~>` actually do?**  
Connects two pipeline stages. Types must match: output of left stage must equal input of right stage. Won't compile otherwise.

**Q: How do I run stages in parallel?**  
Use `&`. Example: `(stageA & stageB & stageC) ~> combine`. All three stages run concurrently, results passed to `combine`.

**Q: How does config injection work?**  
Use `.requires[ConfigType]` on any stage. Provide config with `.provide(config)` before running. No globals, no parameter drilling.

**Q: What happens if a stage fails?**  
Execution halts immediately. Use `.safeRun()` to get a `Try[Result]`, or handle errors with `.onFailure()`.

**Q: Can I mix sync and async code?**  
Yes. All stages run as effects. You can have blocking and non-blocking operations in the same pipeline.

**Q: How do I add retry logic?**  
Use `.withRetry(attempts, delay)` on any stage. Example: `riskyStage.withRetry(3, 1.second)`.

## Observability

**Q: How do I know what happened during execution?**  
Call `.unsafeRunTrace()` instead of `.unsafeRun()`. Returns `Trace` with logs, errors, and timing.

**Q: How does tracing work?**  
Uses ThreadLocal to collect logs and errors during execution. Any stage can call `Trace.log()` or `Trace.error()`. No manual wiring needed.

**Q: How do I add metrics?**  
Use `Tel.addCounter()`, `Tel.setGauge()`, `Tel.recordHistogram()` in your stages. Zero-cost by default. Provide `Etl4sTelemetry` implementation to light them up in prod.

**Q: Can I use this with Prometheus/DataDog/etc?**  
Yes. Implement the `Etl4sTelemetry` trait for your backend. See the Telemetry docs.

**Q: How do I generate lineage diagrams?**  
Call `.toMermaid` or `.toDot` on your pipeline. Returns diagram markup you can render.

## Configuration

**Q: Can I have multiple config types in one pipeline?**  
Yes. Each stage can require different config types. Use `.provide()` to supply all required configs at once.

**Q: What if I forget to provide config?**  
Won't compile. The type system tracks what config is needed.

**Q: Can I use environment variables for config?**  
Sure, but load them into a config case class first, then use `.provide()`. Don't access env vars directly in stages.
