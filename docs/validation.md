---
api:
  - sig: ".ensure(input, output, change)"
  - sig: ".ensurePar(input, output)"
  - sig: "ValidationException"
---

# Ensurers

Ensurers check a node's input and output at runtime. You can reuse them across nodes, and you get every error
back at once instead of just the first.

A validator is just a function `A => Option[String]`: None if it's fine, `Some("what's wrong")` if not:

```scala
import etl4s._

val isPositive = (x: Int) => if (x > 0) None else Some("Must be positive")
val lessThan1k = (x: Int) => if (x < 1000) None else Some("Must be < 1000")
val notEmpty   = (s: String) => if (s.nonEmpty) None else Some("Cannot be empty")
```

Attach them to any node with `.ensure`:

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

## Compare input and output

A `change` validator gets `(input, output)` as a pair, so you can check one against the other:

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
You will get (it passes, the list shrank):
```
List(1, 2, 3)
```

## Collect every error at once

All failing checks are reported together:

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

## Get validation failures as values

`.unsafeRun` throws on failure. Compile to `Try` instead and you get a
`Failure(ValidationException)` back:

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

See [Effect polymorphism](effect-polymorphism.md) for the other effects.

## Run checks in parallel

Use `.ensurePar` instead of `.ensure` to let the checks run concurrently. They only
actually run in parallel under a concurrent effect like `Future`.

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

Await.result(validate.compile[Future].unsafeRun(42), 5.seconds)
```

You will get:
```
42
```

## Recover from validation failures

A failed check throws a `ValidationException`. Recover with `.onFailure` or wrap the run in a `Try`:

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

## Validate against config

Ensurers work on config-aware nodes too. Write the validator as `Config => A => Option[String]` to read the config:

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
