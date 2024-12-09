<p align="center">
  <img src="pix/etl4s.png" width="700">
</p>

# etl4s _(𝛼)_
**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency, library for writing type-safe, beautiful ✨🍰  data flows in functional Scala. 

## Features
- White-board style ETL
- Monadic composition for sequencing pipelines
- Drop **etl4s.scala** into any Scala project like a header file
- Type-safe, compile-time checked pipelines
- Effortless concurrent execution of parallelizable tasks
- Built in retry-mechanism

## Get started
When alpha testing is done, **etl4s** will be available on MavenCentral - until then, try it in your scala repl:
```bash
curl -Ls raw.githubusercontent.com/mattlianje/etl4s/master/Etl4s.scala > .Etl4s.swp.scala && scala-cli repl .Etl4s.swp.scala
```

## Core Concepts
**etl4s** has 4 building blocks

#### `Pipeline[-In, +Out]`
A fully created pipeline composed of nodes chained with `~>`. It takes a type `In` and gives a `Out` when run.
Call `unsafeRun()` to run or throw - `safeRun()` will yield a `Try` Monad.

#### `Node[-In, +Out]`
`Node` Is the base abstraction of **etl4s**. A pipelines is stitched out of nodes. The node types are:

- ##### `Extract[-In, +Out]`
The start of your pipeline. An extract can either be plugged into another function or pipeline or produce an element "purely" with `Extract(2)`. This is shorthand for `val e: Extract[Unit, Int] = Extract(_ => 2)`

- ##### `Transform[-In, +Out]`
A `Node` that represent a transformation. It can be composed with other nodes via `andThen`

- ##### `Load[-In, +Out]` 
A `Node` used to represent the end of a pipeline.

Ultimately - these nodes and pipelines are just reifications of functions and values (with a few niceties like built in retries and Future based parallelism).

Un-safe, framework-coupled ETL codebases that grow without an imposed discipline drive dev teams and data-orgs to their knees.

**etl4s** is a little DSL to enforce this discipline, type-safety and re-use of pure functions - 
and see [functional ETL](https://maximebeauchemin.medium.com/functional-data-engineering-a-modern-paradigm-for-batch-data-processing-2327ec32c42a) for what it is.


## Examples
Chain together two pipelines:
```scala
val fetchUser:      Transform[String, String]= Transform(id => s"Fetching user $id")
val loadUser:       Load[String, String]     = Load(msg => s"User loaded: $msg")

val fetchOrder:     Transform[Int, String]   = Transform(id => s"Fetching order $id")
val loadOrder:      Load[String, String]     = Load(msg => s"Order loaded: $msg")

val userPipeline:   Pipeline[Unit, String]   = Extract("user123") ~> fetchUser ~> loadUser
val ordersPipeline: Pipeline[Unit, String]   = Extract(42) ~> fetchOrder ~> loadOrder

val combinedPipeline: Pipeline[Unit, String] = (for {
  userData <- userPipeline
  orderData <- ordersPipeline
} yield Extract(s"$userData | $orderData") ~>
  Transform { _.toUpperCase } ~>
  Load { x => s"Final result: $x" }).flatten

combinedPipeline.unsafeRun(())

// result:  "Final result: USER LOADED: FETCHING USER USER123 | ORDER LOADED: FETCHING ORDER 42"
```

Run config driven pipelines using the built in `Reader` monad:
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
  userLoader <- loadUser
} yield Extract("user123") ~> userTransform ~> userLoader

/* Run with config */
val result = configuredPipeline.run(config).unsafeRun(())

// result: "User loaded with key secret: Fetching user user123 from https://api.com"
```

Parallelize tasks with task groups using `&>` or sequence them with `&`:
```scala
def time[A](description: String)(f: => A): A = {
  val start = System.currentTimeMillis()
  val result = f
  val end = System.currentTimeMillis()
  println(s"$description took ${end - start}ms")
  result
}
val slowLoad = Load[String, Int] { s => Thread.sleep(100); s.length }

// Using &> for parallelized tasks
time("Using &> operator") {
  val pipeline = Extract("hello") ~> (slowLoad &> slowLoad &> slowLoad)
  pipeline.runSync(())
}

/* Using & for sequenced tasks */
time("Using & operator") {
    val pipeline = Extract("hello") ~> (slowLoad & slowLoad & slowLoad)
    pipeline.runSync(())
}

// Prints: (as expected 3x faster)
//   Using &> operator took 100ms
//   Using & operator took 305ms
```

Give individual `Nodes` or whole `Pipelines` retry capability using `.withRetry(<YOUR_CONF>: RetryConfig)` 
and the batteries included `RetryConfig` which does exponential backoff:
```scala
case class RetryConfig( /* default config */
  maxAttempts: Int = 3,
  initialDelay: Duration = 100.millis,
  backoffFactor: Double = 2.0
)

val transform = Transform[Int, String] { n => 
  if (math.random() < 0.5) throw new Exception("Random failure")
  s"Processed $n"
}

val pipeline = Extract(42) ~> transform.withRetry(RetryConfig())
val result: Try[String] = pipeline.runSyncSafe(())
``` 

## Inspiration
- Debashish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)


