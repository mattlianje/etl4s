# FAQ

## General

**Q: What is etl4s?**
A single-file, zero-dependency Scala library for expressing code as composable pipelines. Chain with `~>`, parallelize with `&`, inject dependencies with `.requires`.

**Q: Is this a framework?**
No, and never will be. It's an ultralight library that doesn't impose a worldview. Try it zero-cost on one pipeline today.

**Q: Is this built on Cats Effect or ZIO?**
No. Pure Scala with no runtime dependencies. If you're already using Cats Effect or ZIO, use those directly. etl4s is an ultralight effect system with no fiber runtime.

**Q: Does this replace Spark/Flink/Pandas?**
No. etl4s structures your pipeline logic. You still use Spark/Flink/Pandas for actual data processing. etl4s makes that code composable and type-safe.

**Q: Is this a workflow orchestrator like Airflow?**
No. etl4s doesn't schedule jobs or manage distributed execution. Use Airflow or any scheduler for that. etl4s structures the code those tools run.

**Q: Where can I use it?**
Anywhere: local scripts, web servers, alongside any framework like Spark or Flink.

**Q: Can I use this in production?**
Yes. It powers grocery deliveries at [Instacart](https://www.instacart.com/). Type safety catches bugs at compile time. No runtime dependencies means nothing to break.

## How it works

**Q: What does `~>` actually do?**
Connects pipeline stages. It's an overloaded symbolic operator that works with plain nodes (`Node[In, Out]`) or nodes that need config (`Reader[Env, Node[In, Out]]`). Mix them freely - the operator figures out what environment is needed. If two stages need different configs, it automatically merges them.

**Q: Why are pipelines type-safe?**
Types must match: output of left stage must equal input of right stage. Won't compile otherwise.

**Q: What makes pipelines composable?**
Every pipeline is a `Node[In, Out]`. Share them, compose them, test them. Snap together with `~>` and `&`. This ability to share and stitch pipelines as values is the bedrock of self-serve.

**Q: How do config dependencies work?**
Use `.requires[DbConfig]` on any node. Pass it with `.provide(config)` at call site. No globals. No guessing what a pipeline needs. The type system tracks what config is needed - forget to provide it and it won't compile.

## Usage

**Q: How do I run stages in parallel?**
Use `&`. Example: `(stageA & stageB & stageC) ~> combine`. All three stages run concurrently, results passed to `combine`.

**Q: What happens if a stage fails?**  
Execution halts immediately. Use `.safeRun()` to get a `Try[Result]`, or handle errors with `.onFailure()`.

**Q: Can I mix sync and async code?**  
Yes. All stages run as effects. You can have blocking and non-blocking operations in the same pipeline.

**Q: How do I add retry logic?**  
Use `.withRetry(attempts, delay)` on any stage. Example: `riskyStage.withRetry(3, 1.second)`.

## Observability

**Q: How do I know what happened during execution?**
Call `.unsafeRunTrace()` instead of `.unsafeRun()`. Returns `Trace` with logs, errors, and timing:

```scala
val trace = pipeline.unsafeRunTrace(data)
trace.logs                // everything logged during execution
trace.errors              // all errors encountered
trace.timeElapsedMillis   // how long it took
```

**Q: How does tracing work?**
Uses ThreadLocal to collect logs and errors during execution. Any stage can call `Trace.log()` or `Trace.error()`. Downstream stages see upstream issues automatically via `Trace.current` - no passing state through function parameters.

**Q: How do I add metrics?**
Use `Tel.addCounter()`, `Tel.setGauge()`, `Tel.recordHistogram()` in your stages. Zero-cost by default. Provide `Etl4sTelemetry` implementation to light them up in prod:

```scala
val process = Transform[List[User], Int] { users =>
  Tel.addCounter("users_processed", users.size)
  users.filter(_.isValid).length
}
```

**Q: Can I use this with Prometheus/DataDog/etc?**
Yes. Implement the `Etl4sTelemetry` trait for your backend. See the [Telemetry docs](opentelemetry.md).

**Q: How do I generate lineage diagrams?**
Nodes are objects - attach custom metadata at compile time (impossible with raw functions). Call `.toMermaid` or `.toDot` on your pipeline. Returns diagram markup you can render.

## Configuration

**Q: Can I have multiple config types in one pipeline?**  
Yes. Each stage can require different config types. Use `.provide()` to supply all required configs at once.

**Q: What if I forget to provide config?**  
Won't compile. The type system tracks what config is needed.

**Q: Can I use environment variables for config?**  
Sure, but load them into a config case class first, then use `.provide()`. Don't access env vars directly in stages.
