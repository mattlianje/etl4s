<p align="center">
  <img src="pix/etl4s.png" width="700">
</p>

# <img src="pix/etl4s-logo.png" width="50"> etl4s
**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency, library for writing type-safe, beautiful ✨🍰  data flows in functional Scala. Part of [d4s](https://github.com/mattlianje/d4s) 

## Features
- White-board style ETL
- Monadic composition for sequencing pipelines
- Drop **Etl4s.scala** into any Scala project like a header file
- Type-safe, compile-time checked pipelines
- Effortless concurrent execution of parallelizable tasks
- Built in retry/on-failure mechanism for nodes + pipelines

## Get started
> [!WARNING]  
> Releases sub `1.0.0` are experimental - breaking API changes might happen

**etl4s** is on MavenCentral:
```scala
"xyz.matthieucourt" % "etl4s_2.13" % "0.0.4"
```

Try it in your repl:
```bash
scala-cli repl --dep xyz.matthieucourt:etl4s_2.13:0.0.4
```

All you need:
```scala
import etl4s.core._
```

## Core Concepts
**etl4s** has 2 building blocks

#### `Pipeline[-In, +Out]`
A fully created pipeline composed of nodes chained with `~>`. It takes a type `In` and gives a `Out` when run.
Call `unsafeRun()` to "run-or-throw" - `safeRun()` will yield a `Try[Out]` Monad.

#### `Node[-In, +Out]`
`Node` Is the base abstraction of **etl4s**. A pipeline is stitched out of nodes. Nodes are just abstractions which defer the application of some run function: `In => Out`. The node types are:

- ##### `Extract[-In, +Out]`
The start of your pipeline. An extract can either be plugged into another function or pipeline or produce an element "purely" with `Extract(2)`. This is shorthand for `val e: Extract[Unit, Int] = Extract(_ => 2)`

- ##### `Transform[-In, +Out]`
A `Node` that represent a transformation. It can be composed with other nodes via `andThen`

- ##### `Load[-In, +Out]` 
A `Node` used to represent the end of a pipeline.

## Of note...
- Ultimately - these nodes and pipelines are just reifications of functions and values (with a few niceties like built in retries and Future based parallelism).
- Chaotic, framework/infra-coupled ETL codebases that grow without an imposed discipline drive dev-teams and data-orgs to their knees.
- **etl4s** is a little DSL to enforce discipline, type-safety and re-use of pure functions - 
and see [functional ETL](https://maximebeauchemin.medium.com/functional-data-engineering-a-modern-paradigm-for-batch-data-processing-2327ec32c42a) for what it is... and could be.


## Examples
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

#### Chain pipelines together:
Connect the output of two pipelines to a third:
```scala
val fetchUser:      Transform[String, String]= Transform(id => s"Fetching user $id")
val loadUser:       Load[String, String]     = Load(msg => s"User loaded: $msg")

val fetchOrder:     Transform[Int, String]   = Transform(id => s"Fetching order $id")
val loadOrder:      Load[String, String]     = Load(msg => s"Order loaded: $msg")

val userPipeline:   Pipeline[Unit, String]   = Extract("user123") ~> fetchUser ~> loadUser
val ordersPipeline: Pipeline[Unit, String]   = Extract(42) ~> fetchOrder ~> loadOrder

val combinedPipeline: Pipeline[Unit, String] = (for {
  userData  <- userPipeline
  orderData <- ordersPipeline
} yield {
  Extract(s"$userData | $orderData") ~> Transform { _.toUpperCase }
     ~> Load { x => s"Final result: $x"}
}).flatten

combinedPipeline.unsafeRun(())

// "Final result: USER LOADED: FETCHING USER USER123 | ORDER LOADED: FETCHING ORDER 42"
```

#### Config-driven pipelines
Use the built in `Reader` monad:
```scala
case class ApiConfig(url: String, key: String)
val config = ApiConfig("https://api.com", "secret")

val fetchUser = Reader[ApiConfig, Transform[String, String]] { config =>
  Transform(id => s"Fetching user $id from ${config.url}")
}

val loadUser = Reader[ApiConfig, Load[String, String]] { config =>
  Load(msg => s"User loaded with key ${config.key}: $msg")
}

val configuredPipeline = for {
  userTransform <- fetchUser
  userLoader    <- loadUser
} yield Extract("user123") ~> userTransform ~> userLoader

/* Run with config */
val result = configuredPipeline.run(config).unsafeRun(())

// "User loaded with key secret: Fetching user user123 from https://api.com"
```

#### Parallelizing tasks
Parallelize tasks with task groups using `&>` or sequence them with `&`:
```scala
val slowLoad = Load[String, Int] { s => Thread.sleep(100); s.length }

/* Using &> for parallelized tasks */
time("Using &> operator") {
  val pipeline = Extract("hello") ~> (slowLoad &> slowLoad &> slowLoad)
  pipeline.unsafeRun(())
}

/* Using & for sequenced tasks */
time("Using & operator") {
    val pipeline = Extract("hello") ~> (slowLoad & slowLoad & slowLoad)
    pipeline.unsafeRun(())
}

/*
 * Using &> operator took: 100ms
 * Using & operator took:  305ms
 */
```

#### Retry and onFailure
Give individual `Nodes` or whole `Pipelines` retry capability using `.withRetry(<YOUR_CONF>: RetryConfig)` 
and the batteries included `RetryConfig` which does exponential backoff:
```scala
/*
case class RetryConfig(
  maxAttempts: Int = 3,
  initialDelay: Duration = 100.millis,
  backoffFactor: Double = 2.0
)
*/

val transform = Transform[Int, String] { n => 
  if (math.random() < 0.5) throw new Exception("Random failure")
  s"Processed $n"
}

val pipeline: Pipeline[Unit, String] = Extract(42) ~> transform.withRetry(RetryConfig())
val result:   Try[String]            = pipeline.safeRun(())
```

## Real-world examples
See the [tutorial](tutorial.md) for examples of **etl4s** in combat. With Spark, with unbounded streams ... you name it.


## Inspiration
- Debasish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)
- Various Rich Hickey talks


