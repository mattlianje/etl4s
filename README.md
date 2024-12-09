<p align="center">
  <img src="pix/etl4s.png" width="700">
</p>

# etl4s
**Easy, whiteboard-style data**

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

### `Pipeline[-In, +Out]`
A fully created pipeline composed of nodes chained with `~>`. It takes a type `In` and gives a `Out` when run.

### `Node[-In, +Out]`
`Node` Is the base abstraction of **etl4s**. A pipelines is stitched out of nodes. The node types are:

#### `Extract[-In, +Out]`
The start of your pipeline. An extract can either be plugged into another function or pipeline or produce an element "purely" with `Extract(2)`. This is shorthand for `val e: Extract[Unit, Int] = Extract(_ => 2)`

#### `Transform[-In, +Out]`
A `Node` that represent a transformation. It can be composed with other nodes via `andThen`

#### `Load[-In, +Out]` 
A `Node` used to represent the end of a pipeline.

## Inspiration
- Debashish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)


