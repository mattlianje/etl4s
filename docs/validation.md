# Ensurers

When writing dataflows, you often want to validate inputs and outputs at runtime - and reuse those validations across nodes, collecting all errors instead of failing on the first.

`.ensure()` lets you attach validators to any Node:

```scala
val process = Node[Int, String](n => s"Value: $n")
  .ensure(
    input  = Seq(isPositive, lessThan1k),
    output = Seq(notEmpty)
  )

process.unsafeRun(42)   // "Value: 42"
process.unsafeRun(-5)   // throws ValidationException: "Must be positive"
```

Validators are just functions `A => Option[String]`. Return `None` if valid, `Some("error message")` if not:

```scala
val isPositive = (x: Int) => if (x > 0) None else Some("Must be positive")
val lessThan1k = (x: Int) => if (x < 1000) None else Some("Must be < 1000")
val notEmpty   = (s: String) => if (s.nonEmpty) None else Some("Cannot be empty")
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

dedupe.unsafeRun(List(1, 2, 2, 3))  // List(1, 2, 3) - valid, shrunk
```

## Error Accumulation

Multiple failures are collected:

```scala
val validate = Node[Int, Int](identity)
  .ensure(input = Seq(isPositive, lessThan100, isEven))

validate.unsafeRun(-5)
// ValidationException: "Input validation failed:
//   - Must be positive
//   - Must be even"
```

## Parallel Validation

Use `.ensurePar()` to run expensive checks concurrently.

## Trace Integration

Validation failures are logged to Trace:

```scala
val node = Node[Int, String](_.toString)
  .ensure(input = Seq(isPositive))

val trace = node.safeRunTrace(-5)
trace.errors.head  // "Input validation failed: Must be positive"
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

process.provide(Config(0, 100)).unsafeRun(50)   // 100
process.provide(Config(0, 100)).unsafeRun(150)  // throws ValidationException
```
