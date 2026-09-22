# FAQ

## General

**Q: What is etl4s?**  
A zero-dependency Scala library for expressing code as composable pipelines. Chain with `~>`, parallelize with `&`, inject dependencies with `.requires`.

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

## Usage

**Q: What happens if a stage fails?**  
The exception propagates out of `.unsafeRun()`. Recover inline with `.onFailure()`, or wrap the call in your own `Try`/`try`-`catch`.

**Q: Can I mix sync and async code?**  
Yes. By default (`.unsafeRun`) stages are plain synchronous functions run on the `Id` interpreter, with no threads and no effect wrapping. They only run inside an effect `F` when you `.compile[F]` (e.g. `Future`), which is also what enables concurrency for `&>`. You can freely place blocking and non-blocking operations in the same pipeline.
