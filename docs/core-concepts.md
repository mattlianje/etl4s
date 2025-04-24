**etl4s** has 2 building blocks

## `Pipeline[-In, +Out]`
`Pipeline`'s are the core abstraction of **etl4s**. They're lazily evaluated data transformations take input `In`
and produce output type `Out`. 

A pipeline won't execute until you call `unsafeRun()` or `safeRun()` on it and provide
the `In`.

Build pipelines by:
- Chaining nodes with `~>`
- Wrap functions directly with `Pipeline(x => x + 1)`
- Connect existing pipelines with the same `~>` operator

## `Node[-In, +Out]`
`Node`'s are the pipeline building blocks. A Node is just a wrapper around a function `In => Out` that we chain together with `~>` to form pipelines.

**etl4s** offers three nodes aliases purely to make your pipelines more readable and express intent clearly:

- `Extract[-In, +Out]` - Gets your data. You can create data from "purely" with `Extract(2)` (which is shorthand for `Extract(_ => 2)`)
- `Transform[-In, +Out]` - Changes data shape or content
- `Load[-In, +Out]` - Finalizes the pipeline, often with a side-effect like writing to storage

They all behave identically under the hood.

## Type safety
**etl4s** won't let you chain together "blocks" that don't fit together:
```scala
 val fiveExtract: Extract[Unit, Int]        = Extract(5)
 val exclaim:     Transform[String, String] = Transform(_ + "!")

 fiveExtract ~> exclaim
```
The above will not compile with:
```shell
-- [E007] Type Mismatch Error: -------------------------------------------------
4 | fiveExtract ~> exclaim
  |                ^^^^^^^
  |                Found:    (exclaim : Transform[String, String])
  |                Required: Node[Int, Any]
```

## Of note...
- Ultimately - these nodes and pipelines are just reifications of functions and values (with a few niceties like built in retries, failure handling, concurrency-shorthand, and Future based parallelism).
- Chaotic, framework/infra-coupled ETL codebases that grow without an imposed discipline drive dev-teams and data-orgs to their knees.
- **etl4s** is a little DSL to enforce discipline, type-safety and re-use of pure functions - 
and see [functional ETL](https://maximebeauchemin.medium.com/functional-data-engineering-a-modern-paradigm-for-batch-data-processing-2327ec32c42a) for what it is... and could be.
