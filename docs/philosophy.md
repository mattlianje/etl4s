# Philosophy

## Design principles

**Discipline upon assignment**  
At the end of the day, pipelines are just functions: but raw composition has limitations and monadic stacks don't impose a
total discipline over creating new bindings.

Say we have
```scala
for {
  e1 <- extract1
  t1 <- transform(e1)
  l <- load(t1)
} yield ()
```

this is good, our eyes can in one top-down motion read: `e > t > l`

but begins the discipline over assignment and vertical dataflow is broken if we introduce bindings:
```scala
for {
  filterDate = ???
  endDate = ???
  e1 <- extract1
  startDate = ???
  t1 <- transform(e1, filterDate)
  l <- load(t1, startDate, endDate)
} yield()
```

Our eyes now need to do:
`filterDate > endDate > e > startDate > t > (Back to filterDate) > l > (Back to startDate) > (Back to endDate)`

The limitation of function composition is that in 90% of cases, configuration parameters run orthogonal to the actual dataflow.
We have something

**Metrics as business logic**  
In OLAP, observability metrics ARE business logic. "Records processed", "validation failures", "data quality scores" - these aren't infrastructure concerns. They're the product. etl4s lets you write metrics inline with `Tel` calls. Zero cost until you provide an implementation.

## What etl4s is NOT

**Not a workflow orchestrator**  
etl4s doesn't schedule jobs, or handle distributed coordination. Use Airflow or whatever flavour of scheduler for this. However,
you will find that when your data 

**Not a data processing engine**  
etl4s doesn't move data or execute transformations. Use Spark, Flink, Pandas for that. etl4s makes your Spark/Flink job logic composable and type-safe.

**Not a replacement for monadic IO with fiber runtimes**  
If you're already using Cats Effect or ZIO, you probably do not need etl4s (although in the near the
concurrency subsystem will not be tied to `Future` so you will be able to run **etl4s** on top of CE, ZIO, Kyo). It's for teams that want structure without committing to an effect system and learning its abstractions.


## Tradeoffs

**Performance**
Each **etl4s** pipeline spawns a daemon-thread to accumulate tracing. Minimal overhead but still something to keep in mind

