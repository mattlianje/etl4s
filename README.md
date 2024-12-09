<p align="center">
  <img src="pix/etl4s.png" width="700">
</p>

# etl4s
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
???

## Core Concepts
**etl4s** has 4 building blocks and 2 main operators

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
/* result:  "Final result: USER LOADED: FETCHING USER USER123 | ORDER LOADED: FETCHING ORDER 42" */
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
/* Result: "User loaded with key secret: Fetching user user123 from https://api.com" */
```

## Inspiration
- Debashish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)


