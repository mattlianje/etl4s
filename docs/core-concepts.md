**etl4s** has 2 building blocks

## `Node[-In, +Out]`
`Node`'s are the pipeline building blocks. A Node is just a wrapper around a function `In => Out` that we chain together with `~>` to form pipelines.

**etl4s** offers three nodes aliases purely to make your pipelines more readable and express intent clearly

`Extract`, `Transform` and `Load`. They all behave identically under the hood.

```scala
/*
 * Option 1: Create nodes "purely", will have -In type "Unit"
 */
val extract = Extract("hello")

/*
 * Option 2: Just wrap any lambda
 */
val extract2     = Extract[Int, String](n => n.toString)
val getStringLen = Transform[String, Int](_.length)

/*
 * To run nodes
 */
println(extract(()))
println(getStringLen("test"))
```
This will print out:
```
hello
4
```

## `Pipeline[-In, +Out]`
`Pipeline`'s are the core abstraction of **etl4s**. They're lazily evaluated data transformations take input `In`
and produce output type `Out`. 

A pipeline won't execute until you call `unsafeRun()` or `safeRun()` on it and provide
the `In`.

```scala
val extract = Extract("hello")
val transform = Transform[String, Int](_.length)
val load = Load[Int, String](n => s"Length: $n")

/*
 * Build pipelines by chaining nodes 
 */
val pipeline = extract ~> transform ~> load

/*
 * Alternative: create directly from function
 */
val simplePipeline = Pipeline((s: String) => s.toUpperCase)

/* 
 * Execute pipelines with unsafeRun
 */
println(pipeline.unsafeRun(()))         /* "Length: 5" */
println(simplePipeline.unsafeRun("hi")) /* "HI" */

/*
 * Using safeRun to handle exceptions safely 
 */
val riskyPipeline = Pipeline[String, Int](s => s.toInt)
val safeResult = riskyPipeline.safeRun("not a number")

println(safeResult)  /* Failure(java.lang.NumberFormatException: ..) */
```


## Of note...
- Ultimately - these nodes and pipelines are just reifications of functions and values (with a few niceties like built in retries, failure handling, concurrency-shorthand, and Future based parallelism).
- Chaotic, framework/infra-coupled ETL codebases that grow without an imposed discipline drive dev-teams and data-orgs to their knees.
- **etl4s** is a little DSL to enforce discipline, type-safety and re-use of pure functions - 
and see [functional ETL](https://maximebeauchemin.medium.com/functional-data-engineering-a-modern-paradigm-for-batch-data-processing-2327ec32c42a) for what it is... and could be.
