# Philosophy

## Discipline Upon Assignment

**etl4s** espouses the idea that is is beneficial to banish the assignment (`=`) operator
at the key "wiring" stage of dataflow programs.

Wiring via raw composition (`g andThen f` style) has limitations: chiefly, config and DI type-slots clutter call-sites
and run orthogonal to dataflow) and wiring via monadic stacks
don't impose a total discipline over the assingment operator and creating new bindings.

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

## Controlled fan-out with reconvergence
**etl4s** deliberately channels you into a linearized "Function1 model" of "give me ONE input, I'll give you ONE output".

That said, it also lets you snap together pipelines with multiple input sources tupled together, easily fork-off conditional
branches with heterogeneous types, and chain together side-outputs.

The idea is to give the programmer clear little two ended pipes basically,
not multi-sided puzzle pieces.

Imagine we have:
```scala
import et4ls._

val p = (e1 & e2) ~> t ~> log >> saveS3 >> .If(_ > 0)(enrich ~> dbLoad)
                                           .Else(process ~> purgatoryLoad)
```

Different branches can have different types and requirements, but once stitched together you have a single node
that has intersected upstream, and unioned downstream the branch types.


## Metrics as business logic
In OLAP, observability metrics are not mere infrastructure concerns. Things like "Records processed", "validation failures", "data quality scores"
tend to be part of your logic-proper.

**etl4s** lets you write metrics inline with `Tel` calls. They are all zero-cost no-ops until you provide an implementation.


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

