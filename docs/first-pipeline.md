# Your First Pipeline

Let's build a pipeline in 5 minutes. No Spark, no databases - just the basics.

## Install

```bash
scala-cli repl --dep io.github.mattlianje::etl4s:0.1.0
```

## A Node wraps a function

```scala
import etl4s._

val double = Transform[Int, Int](_ * 2)

double(5)  // 10
```

That's it. A `Node` wraps a function. You can call it like a function.

## Chain with `~>`

```scala
val double = Transform[Int, Int](_ * 2)
val addTen = Transform[Int, Int](_ + 10)

val pipeline = double ~> addTen

pipeline(5)  // 20
```

The `~>` operator chains nodes. Output of left becomes input of right. Types must match or it won't compile.

## Extract, Transform, Load

```scala
val extract = Extract(5)                           // starts with 5
val transform = Transform[Int, Int](_ * 2)         // double it
val load = Load[Int, Unit](x => println(s"Result: $x"))  // print it

val pipeline = extract ~> transform ~> load

pipeline.unsafeRun(())  // prints "Result: 10"
```

`Extract`, `Transform`, `Load` are just aliases for `Node`. Use them to show intent.

## Run in parallel with `&`

```scala
val double = Transform[Int, Int](_ * 2)
val triple = Transform[Int, Int](_ * 3)

val combine = Transform[(Int, Int), Int] { case (a, b) => a + b }

val pipeline = Extract(5) ~> (double & triple) ~> combine

pipeline.unsafeRun(())  // (10, 15) -> 25
```

`(double & triple)` runs both in parallel. Results collected as a tuple and passed to `combine`.

## Add config with `.requires`

```scala
case class Config(multiplier: Int)

val transform = Transform[Int, Int]
  .requires[Config] { config => x =>
    x * config.multiplier
  }

val load = Load[Int, Unit]
  .requires[Config] { config => x =>
    println(s"Result with multiplier ${config.multiplier}: $x")
  }

val pipeline = Extract(5) ~> transform ~> load

val config = Config(multiplier = 3)
pipeline.provide(config).unsafeRun(())  // prints "Result with multiplier 3: 15"
```

`.requires[Config]` declares a dependency. `.provide(config)` supplies it. No globals, no parameter drilling.

## That's it

You now know:

- `Node` wraps functions
- `~>` chains nodes
- `&` runs nodes in parallel
- `.requires` / `.provide` handles config

Next: [Core Concepts](core-concepts.md) for more details, or [Examples](examples.md) to see real usage with Spark/Flink.
