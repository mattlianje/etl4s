---
api:
  - sig: ".ensure(input, output, change)"
  - sig: ".ensurePar(input, output)"
  - sig: "ValidationException"
---

# Ensurers

When writing dataflows, you often want to validate inputs and outputs at runtime - and reuse those validations across nodes, collecting all errors instead of failing on the first.

Validators are just functions `A => Option[String]`. Return `None` if valid, `Some("error message")` if not:

```scala
import etl4s._

val isPositive = (x: Int) => if (x > 0) None else Some("Must be positive")
val lessThan1k = (x: Int) => if (x < 1000) None else Some("Must be < 1000")
val notEmpty   = (s: String) => if (s.nonEmpty) None else Some("Cannot be empty")
```

`.ensure()` lets you attach validators to any Node:

```scala
val process = Node[Int, String](n => s"Value: $n")
  .ensure(
    input  = Seq(isPositive, lessThan1k),
    output = Seq(notEmpty)
  )

process.unsafeRun(42)
process.unsafeRun(-5)
```
You will get:
```
42  -> "Value: 42"
-5  -> throws ValidationException: "Must be positive"
```

## Change Validation

Validate by examining both input and output together. The `change` validator receives a tuple `(input, output)`:

```scala
/* Ensure deduplication never grows the list */
val noGrowth: ((List[Int], List[Int])) => Option[String] = {
  case (in, out) =>
    if (out.size <= in.size) None
    else Some(s"Output grew: ${in.size} -> ${out.size}")
}

val dedupe = Node[List[Int], List[Int]](_.distinct)
  .ensure(change = Seq(noGrowth))

dedupe.unsafeRun(List(1, 2, 2, 3))
```
You will get (valid - the list shrunk):
```
List(1, 2, 3)
```

## Error Accumulation

Multiple failures are collected:

```scala
val lessThan100 = (x: Int) => if (x < 100) None else Some("Must be < 100")
val isEven      = (x: Int) => if (x % 2 == 0) None else Some("Must be even")

val validate = Node[Int, Int](identity)
  .ensure(input = Seq(isPositive, lessThan100, isEven))

validate.unsafeRun(-5)
```

You will get:
```
ValidationException: "Input validation failed:
  - Must be positive
  - Must be even"
```

## Validation under effects

The default `.unsafeRun` throws on failure. To capture the failure as a value,
run through an effect. Under `.compile[Try]` a validation failure surfaces as a
`Failure(ValidationException)`:

```scala
import etl4s._
import scala.util.{Try, Failure}

val node = Node[Int, String](_.toString)
  .ensure(input = Seq(isPositive))

node.compile[Try].unsafeRun(42)
node.compile[Try].unsafeRun(-5)
```
You will get:
```
Success("42")
Failure(ValidationException(...))
```

See [Effect polymorphism](effect-polymorphism.md) for the full list of effects.

## Parallel Validation

Use `.ensurePar()` in place of `.ensure()` to mark the checks within each stage
as eligible to run concurrently. This only actually runs them in parallel under a
concurrent effect (e.g. `.compile[Future]` or a cats-effect `IO`) - under the
default `.unsafeRun` (the sequential `Id` interpreter) the checks still run one
after another, just as with `.ensure()`.

```scala
import etl4s._
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/* Two independent, potentially expensive checks */
val isPositive  = (x: Int) => if (x > 0) None else Some("Must be positive")
val lessThan100 = (x: Int) => if (x < 100) None else Some("Must be < 100")

val validate = Node[Int, Int](identity)
  .ensurePar(input = Seq(isPositive, lessThan100))

// Runs the checks concurrently under a Future effect
Await.result(validate.compile[Future].unsafeRun(42), 5.seconds)
```

You will get:
```
42
```

## Handling Failures

Validation failures throw a `ValidationException`. Recover with `.onFailure()`
or wrap the run in your own `Try`:

```scala
import scala.util.Try

val node = Node[Int, String](_.toString)
  .ensure(input = Seq(isPositive))

Try(node.unsafeRun(-5))
```

You will get:
```
Failure(ValidationException("Input validation failed:\n  - Must be positive"))
```

## Config-Aware Validation

Ensurers work on config nodes too. Validators are curried `Config => A => Option[String]` so they can access config:

```scala
case class Config(minValue: Int, maxValue: Int)

val inRange: Config => Int => Option[String] = cfg => n =>
  if (n >= cfg.minValue && n <= cfg.maxValue) None
  else Some(s"Must be between ${cfg.minValue} and ${cfg.maxValue}")

val process = Transform[Int, Int].requires[Config] { cfg => n => n * 2 }
  .ensure(input = Seq(inRange))

process.provide(Config(0, 100)).unsafeRun(50)
process.provide(Config(0, 100)).unsafeRun(150)
```
You will get:
```
50   -> 100
150  -> throws ValidationException
```
