# Ensurers

When writing dataflows, you often want to validate inputs and outputs at runtime - and reuse those validations across nodes, collecting all errors instead of failing on the first.

`.ensure()` lets you attach validators to any Node:

```scala
val process = Node[Int, String](n => s"Value: $n")
  .ensure(
    input = isPositive :: lessThan1k :: Nil,
    output = notEmpty :: Nil
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

Validate by examining both input and output together:

```scala
val noGrowth = (t: (List[Int], List[Int])) =>
  if (t._2.size <= t._1.size) None else Some("Output larger than input")

val dedupe = Node[List[Int], List[Int]](_.distinct)
  .ensure(change = noGrowth :: Nil)
```

## Error Accumulation

Multiple failures are collected:

```scala
val validate = Node[Int, Int](identity)
  .ensure(input = isPositive :: lessThan100 :: isEven :: Nil)

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
  .ensure(input = isPositive :: Nil)

val trace = node.safeRunTrace(-5)
trace.errors.head  // "Input validation failed: Must be positive"
```
