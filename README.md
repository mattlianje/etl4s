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
`Node` Is the base abstraction of **etl4s**. A pipeline is stitched out of two or more nodes with `~>`. Nodes are just abstractions which defer the application of some run function: `In => Out`. The node types are:

- ##### `Extract[-In, +Out]`
The start of your pipeline. An extract can either be plugged into another function or pipeline or produce an element "purely" with `Extract(2)`. This is shorthand for `val e: Extract[Unit, Int] = Extract(_ => 2)`

- ##### `Transform[-In, +Out]`
A `Node` that represent a transformation. It can be composed with other nodes via `andThen`

- ##### `Load[-In, +Out]` 
A `Node` used to represent the end of a pipeline.

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


## Handling Failures
**etl4s** comes with 2 methods you can use to handle failures out of the box:

#### `withRetry`
Give retry capability to a Node or Pipeline using the built-in `RetryConfig`:
```scala
val riskyTransformWithRetry = Transform[Int, String] { n =>
    var attempts = 0
    attempts += 1
    if (attempts < 3) throw new RuntimeException(s"Attempt $attempts failed")
    else s"Success after $attempts attempts"
}.withRetry(
    RetryConfig(maxAttempts = 3, initialDelay = 10.millis)
)

val pipeline = Extract(42) ~> riskyTransformWithRetry

pipeline.unsafeRun(())
```
This prints:
```
Success after 3 attempts
```

#### `onFailure`
Catch some exception and perform some action for Nodes or Pipelines:
```scala
val riskyExtract =
    Extract[Unit, String](_ => throw new RuntimeException("Boom!"))

val safeExtract = riskyExtract
                    .onFailure(e => s"Failed with: ${e.getMessage} ... firing missile")
val consoleLoad: Load[String, Unit] = Load(println(_))

val pipeline = safeExtract ~> consoleLoad
pipeline.unsafeRun(())
``` 
This prints:
```
Failed with: Boom! ... firing missile
```

## Parallelizing Tasks
**etl4s** has an elegant shorthand for parallelizing operations:
```scala
/* Simulate slow IO operations (e.g., DB calls, API requests) */
val e1 = Extract { Thread.sleep(100); 42 }
val e2 = Extract { Thread.sleep(100); "hello" }
val e3 = Extract { Thread.sleep(100); true }

/* Sequential (~300ms total) */
val sequential = e1 & e2 & e3        // Type: Extract[Unit, ((Int, String), Boolean)]

/* Parallel (~100ms total) */
val parallel = e1 &> e2 &> e3        // Same result, much faster!

/* Clean up nested tuples */
val clean = (e1 & e2 & e3).zip       // Type: Extract[Unit, (Int, String, Boolean)]

/* Mix sequential and parallel */
val mixed = (e1 &> e2) & e3          // First two parallel (~100ms), then third (~100ms)

/* Parallel pipeline */
val pipeline = (e1 &> e2 &> e3).zip ~>    // Parallel extracts (~100ms)
  Transform { case (i, s, b) =>           // Transform combined results
    s"$i-$s-$b"
  } ~> 
  (Load(println) &> Load(save))           // Parallel loads (~100ms)
```
Use `&` for sequential operations (each takes full time), `&>` for parallel (only takes longest operation time), and `.zip` to flatten nested tuples.


## Built-in Monads
**etl4s** comes with 3 powerful built in Monads to make your pipelines hard like iron, and flexible like bamboo.
You can use the same ideas with the Cats datatypes or your own.

You just need to:
```scala
import etl4s.types.*
```

#### `Reader`
Make your pipelines config-driven and run in the context of the environment they need:
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


#### `Writer`
Trace your pipeline's transformations:
```scala
type Log = List[String]
type DataWriter[A] = Writer[Log, A]

val fetchUser = Transform[String, DataWriter[String]] { id =>
  Writer(
    List(s"Fetching user $id"),
    s"User $id"
  )
}

val processUser = Transform[DataWriter[String], DataWriter[String]] { writerInput =>
  for {
    value <- writerInput
    result <- Writer(
      List(s"Processing $value"),
      s"Processed: $value"
    )
  } yield result
}

val pipeline = Extract("123") ~> fetchUser ~> processUser
val (logs, result) = pipeline.unsafeRun(()).run()

// Logs: ["Fetching user 123", "Processing User 123"]
// Result: "Processed: User 123"
```


#### `Validated`
Instead of failing fast, collect ALL validation errors in one go:

```scala
case class User(name: String, age: Int)

def validateName(name: String): Validated[String, String] =
  if (!name.matches("[A-Za-z ]+")) Validated.invalid("Name can only contain letters")
  else Validated.valid(name)

def validateAge(age: Int): Validated[String, Int] =
  if (age < 0) Validated.invalid("Age must be positive")
  else if (age > 150) Validated.invalid("Age not realistic")
  else Validated.valid(age)

val validateUser = Transform[User, Validated[String, User]] {
  case User(name, age) =>
    validateName(name)
      .zip(validateAge(age))
      .map { case (name, age) => User(name, age) }
}

val formatUser: Transform[Validated[String, User], String] = 
  Transform {
    case Validated.Valid(user) => s"${user.name} is ${user.age} years old"
    case Validated.Invalid(errors) => throw new Exception(s"Validation failed: ${errors.mkString("AND ")}")
  } ~>

val consoleLoad: Load[String, Unit] = Load(println(_))

val invalidPipeline = 
  Extract(User("Alice4", -1)) ~> validateUser ~> formatUser ~> consoleLoad

invalidPipeline.unsafeRun(())
``` 
This prints:
```
Name can only contain letters AND Age must be positive
```


## Examples

#### Chain pipelines
Simple piping of two pipelines:
```scala
val plusFiveExclaim: Pipeline[Unit, String] =
          Extract(1) ~> Transform((x: Int) => x + 5) ~> Transform((x: Int) => x.toString + "!")

val doubleString: Pipeline[String, String] =
          Extract((s: String) => s) ~> Transform((s: String) => s ++ s)

val plusFiveExclaimDouble: Pipeline[Int, Str] = plusFiveExclaimPipeline ~> doubleStrPipeline

println(plusFiveExclaimDouble(2))
// Prints: "7!7!"
```

Connect the output of two pipelines to a third:
```scala
val fetchUser = Transform[String, String](id => s"Fetching $id")
val loadUser = Load[String, String](msg => s"Loaded: $msg")

/* Create two simple pipelines */
val namePipeline = Extract("alice") ~> fetchUser ~> loadUser
val agePipeline = Extract(25) ~> Transform(age => s"Age: $age")

/* Combine them */
val combined = for {
  name <- namePipeline
  age <- agePipeline
} yield Extract(s"$name | $age") ~> 
  Transform(_.toUpperCase) ~> 
  Load(println)

combined.unsafeRun(())
// Prints: "LOADED: FETCHING ALICE | AGE: 25"
```


## Real-world examples
See the [tutorial](tutorial.md) for examples of **etl4s** in combat. It works great with anything:
- Spark / Flink / Beam
- Bounded / Unbounded-data - ETL / Streaming
- Multi-physical-machine big data workflows
- Little web-server dataflows


## Inspiration
- Debasish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)
- Various Rich Hickey talks


