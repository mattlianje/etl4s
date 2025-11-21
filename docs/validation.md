# Ensurers

Often when writing dataflows, we want to perform dynamic validations on Node inputs and outputs at runtime.

We **_could_** put these validations inside the nodes themselves - and this is often our first instinct:
```scala
val plusFive = Node[Int, Int]{x =>
    val res = x + 5
    if (res < x) {
        throw Exception("Something is off")
    }
    res
}
```

But suppose we want to re-use our validations on multiple nodes, or string together sequences of validations and run through-all of them, then
collect all the errors instead of failing on the first one.

This is why **etl4s** offers `.ensure()` on all Nodes and Readers.
```scala
val process = Node[Int, String](n => s"Value: $n")
  .ensure(
    input = isPositive :: lessThan1k :: Nil,
    output = notEmpty :: Nil
  )
```

```scala
process.unsafeRun(42)   // "Value: 42"
process.unsafeRun(-5)   // throws ValidationException: "Input validation failed: Must be positive"
```

where:
```scala
val isPositive = (x: Int) => if (x > 0) None else Some("Must be positive")
val lessThan1k = (x: Int) => if (x < 1000) None else Some("Must be less than 1000")
val notEmpty = (s: String) => if (s.nonEmpty) None else Some("Output cannot be empty")
```

**etl4s** lets you splice together any raw validator functions provided they are of type `[A] => Option[String]`. `None` means no problem, and if
your validation fails use `Some("<my_error_message>")`

Multiple failures are collected and reported together.


## Change Validation

Validate the transformation by examining both input and output together.

```scala
val noGrowth: ((List[Int], List[Int])) => Option[String] = {
  case (in, out) => 
    if (out.size <= in.size) None 
    else Some("Output cannot be larger than input")
}

val preserveCount: ((List[String], List[String])) => Option[String] = {
  case (in, out) => 
    if (in.size == out.size) None 
    else Some(s"Count mismatch: ${in.size} -> ${out.size}")
}

val deduplicate = Node[List[Int], List[Int]](_.distinct)
  .ensure(change = noGrowth :: Nil)

val toUpper = Node[List[String], List[String]](_.map(_.toUpperCase))
  .ensure(change = preserveCount :: Nil)
```


## Error Accumulation

When multiple checks fail, all errors are collected and reported together.

```scala
val isPositive = (x: Int) => if (x > 0) None else Some("Must be positive")
val lessThan100 = (x: Int) => if (x < 100) None else Some("Must be less than 100")
val isEven = (x: Int) => if (x % 2 == 0) None else Some("Must be even")

val validate = Node[Int, Int](x => x)
  .ensure(input = isPositive :: lessThan100 :: isEven :: Nil)

validate.unsafeRun(-5)
// ValidationException: "Input validation failed:
//   - Must be positive
//   - Must be even"
```

## Parallel Validation

Use `.ensurePar()` to run expensive checks concurrently.


## Context-Aware Validation

With Readers, validation functions can access configuration using curried form.

## Trace Integration

Validation failures are automatically logged to the trace system.

```scala
val isPositive = (x: Int) => if (x > 0) None else Some("Must be positive")

val node = Node[Int, String](n => s"$n")
  .ensure(input = isPositive :: Nil)

val trace = node.safeRunTrace(-5)

trace.result.isFailure  // true
trace.errors.nonEmpty   // true
trace.errors.head       // "Input validation failed: Must be positive"
```
