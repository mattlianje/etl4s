# FAQ

## General

**Q: What is etl4s?**  
A single-file, zero-dependency Scala library for expressing code as composable pipelines. Chain with `~>`, parallelize with `&`, inject dependencies with `.requires`.

**Q: Is this a framework?**  
No, and never will be. It's an ultralight library that doesn't impose a worldview. Try it zero-cost on one pipeline today.

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

## Usage

**Q: What happens if a stage fails?**  
The exception propagates out of `.unsafeRun()`. Recover inline with `.onFailure()`, or wrap the call in your own `Try`/`try`-`catch`.

**Q: Can I mix sync and async code?**  
Yes. By default (`.unsafeRun`) stages are plain synchronous functions run on the `Id` interpreter, with no threads and no effect wrapping. They only run inside an effect `F` when you `.compile[F]` (e.g. `Future`), which is also what enables concurrency for `&>`. You can freely place blocking and non-blocking operations in the same pipeline.

**Q: What is effect polymorphism / `.compile[F]`?**  
etl4s pipelines are effect polymorphic. `.compile[F]` picks the interpreter that runs your pipeline: built-in choices are `Id` (synchronous, the `unsafeRun` default), `Try` (error-capturing), and `Future` (concurrent for `&>`, `*>`, `eachPar`, `.ensurePar`). You can add your own by providing a `given Effect[F]` (implementing `pure`, `delay`, `flatMap`, `handleErrorWith`, and overriding `both` for concurrency), letting you run on top of Cats Effect `IO`, ZIO, Kyo, etc. See the [Effect polymorphism](effect-polymorphism.md) docs.

## Observability

**Q: How do I know how long a run took?**  
Call `.unsafeRunTrace()` instead of `.unsafeRun()`. Returns a `Trace` with the result and timing:

```scala
val trace = pipeline.unsafeRunTrace(data)
trace.result
trace.timeElapsedMillis
```
`trace.result` is the result; `trace.timeElapsedMillis` is how long it took.

**Q: What about logging, metrics, and distributed tracing?**  
`etl4s` stays out of your way here. Bring your own tools. Call your logger,
metrics client, or tracer directly inside node bodies or via `tap`. See the
[Tracing docs](trace.md) for the pattern.
