
**etl4s** has 1 core building block: `Node`. Nodes are just wrappers around lazily-evaluated functions
`In => Out` that we chain together with `~>`

## `Node[-In, +Out]`

**etl4s** offers four node aliases purely to make your pipelines more readable and express intent clearly: `Extract`, `Transform`, `Load` and
`Pipeline`. They all behave identically under the hood.

```scala
import etl4s._

/* Create nodes "purely" */
val extract: Extract[Unit, String] = Extract("hello")

/* Or you can just wrap any lambda or `Function1` */
val extract2     = Extract[Int, String](n => n.toString)
val getStringLen = Transform[String, Int](_.length)

/* Run nodes like calling functions */
println(extract(()))
println(getStringLen("test"))
```
This will print out:
```
hello
4
```

## `unsafeRun`, `safeRun`, `unsafeRunTimedMillis`
You can be more deliberate about running nodes by calling them with
`unsafeRun()` or `safeRun()` and providing
the `In`.

Let's start with some nodes:
```scala
import etl4s._

val E = Extract("hello")
val T = Transform[String, Int](_.length)
val L = Load[Int, String](n => s"Length: $n")

/* Build pipelines by chaining nodes */
val pipeline = E ~> T ~> L

/* Alternatively, create a `Pipeline` directly from a function */
val simplePipeline = Pipeline((s: String) => s.toUpperCase)

/* Execute pipelines with `unsafeRun(<PIPELINE_INPUT>)` */
println(pipeline.unsafeRun(()))      
println(simplePipeline.unsafeRun("hi"))
```

This will print to stdout:
```
Length: 5
HI
```

Use `safeRun` to handle exceptions safely 
```scala
import etl4s._

val riskyPipeline = Pipeline[String, Int](s => s.toInt)
val safeResult = riskyPipeline.safeRun("not a number")

println(safeResult)  /* Failure(java.lang.NumberFormatException: ..) */
```

Use `unsafeRunTimedMillis` to time your pipeline executions:
```scala
import etl4s._

val sleepDuration = 100
val sleepNode = Node[Unit, Unit] { _ =>
  Thread.sleep(sleepDuration)
}
val (_, elapsedTime) = sleepNode.unsafeRunTimedMillis(())
```


## Of note...
- Ultimately - these nodes and pipelines are just reifications of functions and values (with a few niceties like built in retries, failure handling, concurrency-shorthand, and Future based parallelism).
- Chaotic, framework/infra-coupled ETL codebases that grow without an imposed discipline drive dev-teams and data-orgs to their knees.
- **etl4s** is a little DSL to enforce discipline, type-safety and re-use of pure functions - 
and see [functional ETL](https://maximebeauchemin.medium.com/functional-data-engineering-a-modern-paradigm-for-batch-data-processing-2327ec32c42a) for what it is... and could be.
