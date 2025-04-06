<p align="center">
  <img src="pix/etl4s.png" width="700">
</p>

# <img src="pix/etl4s-logo.png" width="50"> etl4s
**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency library for writing type-safe, beautiful ✨🍰  data flows in functional Scala. 
Battle-tested at [Instacart](https://www.instacart.com/)

## Features
- White-board style ETL
- Drop **Etl4s.scala** into any Scala project like a header file
- Type-safe, compile-time checked pipelines
- Effortless concurrent execution of parallelizable tasks with `&>`
- Easy monadic composition of pipelines
- Chain pipelines with `~>`, sequence them with `>>`
- Built in retry/on-failure mechanism for nodes + pipelines

## Get started

**etl4s** is on MavenCentral and cross-built for Scala, 2.12, 2.13, 3.x:
```scala
"xyz.matthieucourt" %% "etl4s" % "1.2.0"
```

Try it in your repl:
```bash
scala-cli repl --scala 3 --dep xyz.matthieucourt:etl4s_3:1.2.0
```

All you need:
```scala
import etl4s.*
```
## Table of Contents
- [Features](#features)
- [Get started](#get-started)
- [Code Example](#code-example)
- [Core Concepts](#core-concepts)
  - [Pipeline](#pipeline-in-out)
  - [Node](#node-in-out)
- [Type safety](#type-safety)
- [Of note...](#of-note)
- [Operators](#operators)
- [Handling Failures](#handling-failures)
  - [withRetry](#withretry)
  - [onFailure](#onfailure)
- [Observation with `tap`](#observation-with-tap)
- [Parallelizing Tasks](#parallelizing-tasks)
- [Built-in Tools](#built-in-tools)
  - [Reader[R, A]](#readerr-a-config-driven-pipelines)
  - [Writer[W, A]](#writerw-a-log-accumulating-pipelines)
  - [Validated[T]](#validatedt-easy-validation-stacking)
- [Examples](#examples)
  - [Chain two pipelines](#chain-two-pipelines)
  - [Complex chaining](#complex-chaining)
- [Real-world examples](#real-world-examples)
- [Inspiration](#inspiration)

## Code Example
```scala
import etl4s.*

/* Define components */
val getUser  = Extract("john_doe") ~> Transform(_.toUpperCase)
val getOrder = Extract("2 items")
val process  = Transform[(String, String), String] { case (user, order) => 
  s"$user ordered $order" 
}
val saveDb    = Load[String, String](s => { println(s"DB: $s"); s })
val sendEmail = Load[String, Unit](s => println(s"Email: $s"))
val cleanup   = Pipeline[Unit, Unit](_ => println("Cleanup complete"))

/* Group tasks with &, Connect with ~>, Sequence with >> */
val pipeline =
     (getUser & getOrder) ~> process ~> (saveDb & sendEmail) >> cleanup
pipeline.unsafeRun(())
```

## Core Concepts
**etl4s** has 2 building blocks

#### `Pipeline[-In, +Out]`
`Pipeline`'s are the core abstraction of **etl4s**. They're lazily evaluated data transformations take input `In`
and produce output type `Out`. 

A pipeline won't execute until you call `unsafeRun()` or `safeRun()` on it and provide
the `In`.

Build pipelines by:
- Chaining nodes with `~>`
- Wrap functions directly with `Pipeline(x => x + 1)`
- Connect existing pipelines with the same `~>` operator

#### `Node[-In, +Out]`
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

## Operators

etl4s uses a few simple operators to build pipelines:

| Operator | Name | Description | Example |
|----------|------|-------------|---------|
| `~>` | Connect | Chains operations in sequence | `e1 ~> t1 ~> l1` |
| `&` | Combine | Group sequential operations with same input | `t1 & t2` |
| `&>` | Parallel | Group concurrent operations with same input | `t1 &> t2` |
| `>>` | Sequence | Runs pipelines in order (ignoring previous output) | `p1 >> p2` |


## Handling Failures
**etl4s** comes with 2 methods you can use (on a `Node` or `Pipeline`) to handle failures out of the box:

#### `withRetry`
Give retry capability using the built-in `withRetry`:
```scala
import etl4s.*

var attempts = 0

val riskyTransformWithRetry = Transform[Int, String] {
    n =>
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"Attempt $attempts failed")
      else s"Success after $attempts attempts"
}.withRetry(maxAttempts = 3, initialDelayMs = 10)

val pipeline = Extract(42) ~> riskyTransformWithRetry
pipeline.unsafeRun(())
```
This prints:
```
Success after 3 attempts
```

#### `onFailure`
Catch exceptions and perform some action:
```scala
import etl4s.*

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

## Observation with `tap`
The `tap` method allows you to observe values flowing through your pipeline without modifying them. 
This is useful for logging, debugging, or collecting metrics.

```scala
import etl4s._

val sayHello   = Extract("hello world")
val splitWords = Transform[String, Array[String]](_.split(" "))
val toUpper    = Transform[Array[String], Array[String]](_.map(_.toUpperCase))

val pipeline = sayHello ~> 
               splitWords ~>
               tap(words => println(s"Processing ${words.length} words")) ~> 
               toUpper

val result = pipeline.unsafeRun(())
// Result: Array("HELLO", "WORLD")
//    but also prints: "Processing 2 words"
```


## Parallelizing Tasks
**etl4s** has an elegant shorthand for grouping and parallelizing operations that share the same input type:
```scala
/* Simulate slow IO operations (e.g: DB calls, API requests) */

val e1 = Extract { Thread.sleep(100); 42 }
val e2 = Extract { Thread.sleep(100); "hello" }
val e3 = Extract { Thread.sleep(100); true }
```

Sequential run of e1, e2, and e3 **(~300ms total)**
```scala
val sequential: Extract[Unit, ((Int, String), Boolean)] =
     e1 & e2 & e3
```

Parallel run of e1, e2, e3 on their own JVM threads with Scala Futures **(~100ms total, same result, 3X faster)**
```scala
import scala.concurrent.ExecutionContext.Implicits.global

val parallel: Extract[Unit, ((Int, String), Boolean)] =
     e1 &> e2 &> e3
```
Use the built-in zip method to flatten unwieldly nested tuples:
```scala
val clean: Extract[Unit, (Int, String, Boolean)] =
     (e1 & e2 & e3).zip
```
Mix sequential and parallel execution (First two parallel (~100ms), then third (~100ms)):
```scala
val mixed = (e1 &> e2) & e3
```

Full example of a parallel pipeline:
```scala
val consoleLoad: Load[String, Unit] = Load(println(_))
val dbLoad:      Load[String, Unit] = Load(x => println(s"DB Load: ${x}"))

val merge = Transform[(Int, String, Boolean), String] { t => 
    val (i, s, b) = t
    s"$i-$s-$b"
  }

val pipeline =
  (e1 &> e2 &> e3).zip ~> merge ~> (consoleLoad &> dbLoad)
```

## Built-in Tools
**etl4s** comes with 3 extra abstractions to make your pipelines hard like iron, and flexible like bamboo.
You can use them directly or swap in your own favorites (like their better built homologues from [Cats](https://typelevel.org/cats/)).

### `Reader[R, A]`: Config-driven pipelines
Need database credentials? Start and end dates for your batch job? API keys? Environment settings?
Let your pipeline know exactly what it needs to run, and switch configs effortlessly.
```scala
import etl4s.*

case class ApiConfig(url: String, key: String)
val config = ApiConfig("https://api.com", "secret")

val fetchUser = Reader[ApiConfig, Transform[String, String]] { config =>
  Transform(id => s"Fetching user $id from ${config.url}")
}

val loadUser = Reader[ApiConfig, Load[String, String]] { config =>
  Load(msg => s"User loaded with key `${config.key}`: $msg")
}

val configuredPipeline = for {
                          userTransform <- fetchUser
                          userLoader    <- loadUser
                        } yield Extract("user123") ~> userTransform ~> userLoader

/* Run with config */
val result = configuredPipeline.run(config).unsafeRun(())
println(result)
```
Prints:
```
"User loaded with key `secret`: Fetching user user123 from https://api.com"
```

### `Writer[W, A]`: Log accumulating pipelines
Collect logs at every step of your pipeline and get them all at once with your results.
No more scattered println's - just clean, organized logging, that shows exactly how your data flowed through the pipeline.
```scala
import etl4s.*

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
```
Yields:
```
Logs: ["Fetching user 123", "Processing User 123"]
Result: "Processed: User 123"
```

### `Validated[T]`: Easy validation stacking
**etl4s** provides a lightweight validation system that lets you accumulate errors instead of failing at the first problem.
You can then report on, and take action based on specific failure lists.

#### Components

| Component | Description | Example |
|:----------|:------------|:--------|
| `Validated[T]` | Type class for validating objects | `Validated[User] validator` (validator = {require / success / failure}) |
| `ValidationResult` | Success (Valid) or failure (Invalid) | `Valid(user)` or `Invalid(errors)` (errors always `List[String]`|
| `require` | Validate a condition | `require(user, user.age >= 18, "Must be 18+")` |
| `success` | Create successful validation | `success(user)` |
| `failure` | Create failed validation | `failure("Invalid data")` |
| `&&` | Combine with AND logic | `validateName && validateEmail` |
| `OR` operator | Combine with OR logic | `isPremium OR isAdmin` |


Define your data model:
```scala
case class User(name: String, email: String, age: Int)
```

Create a simple validator:
```scala
val validateUser = Validated[User] { user =>
  require(user, user.name.nonEmpty, "Name required") &&
  require(user, user.email.contains("@"), "Valid email required") &&
  require(user, user.age >= 18, "Must be 18+")
}
```

Run validation:
```scala
val result = validateUser(User("Alice", "alice@mail.com", 25))
// Valid(User(Alice,alice@mail.com,25))

val invalid = validateUser(User("", "not-an-email", 16))
// Invalid(List("Name required", "Valid email required", "Must be 18+"))
```

#### Composing Validators

Create specialized validators:
```scala
val validateName = Validated[User] { user => 
  require(user, user.name.nonEmpty, "Name required") 
}

val validateAge = Validated[User] { user => 
  require(user, user.age >= 18, "Must be 18+")
}
```

Combine with logical operators:
```scala
val completeValidator = validateName && validateAge
// All validations must pass (AND)

val flexibleValidator = validateName || validateAge 
// At least one validation must pass (OR)
```

#### Conditional Validation

Adapt validation rules contextually:
```scala
val conditionalValidator = Validated[User] { user =>
  val baseCheck = require(user, user.name.nonEmpty, "Name required")
  
  if (user.name == "Admin") 
    baseCheck && require(user, user.age >= 21, "Admins must be 21+")
  else 
    baseCheck && require(user, user.age >= 18, "Must be 18+")
}
```


## Examples

#### Chain two pipelines
Simple UNIX-pipe style chaining of two pipelines:
```scala
import etl4s.*

val p1 = Pipeline((i: Int) => i.toString)
val p2 = Pipeline((s: String) => s + "!")

val p3: Pipeline[Int, String] = p1 ~> p2
```

#### Complex chaining
Connect the output of two pipelines to a third:
```scala
import etl4s.*

val namePipeline = Pipeline((_: Unit) => "John Doe")
val agePipeline = Pipeline((_: Unit) => 30)

val combined: Pipeline[Unit, Unit] =
  for {
    name <- namePipeline
    age <- agePipeline
    _ <- Extract(s"$name | $age") ~> Transform(_.toUpperCase) ~> Load(println)
  } yield ()
```

## Real-world examples
See the [tutorial](tutorial.md) to learn how to build an invincible, combat ready **etl4s** pipeline that use `Reader` based
dependency injection.

**etl4s** works great with anything:
- Spark / Flink / Beam
- ETL / Streaming
- Distributed Systems
- Local scripts
- Big Data workflows
- Web-server dataflows


## Inspiration
- Debasish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)
- Various Rich Hickey talks


