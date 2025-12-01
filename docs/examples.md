# Common Patterns

## Chain two pipelines

```scala
import etl4s._

val A = Pipeline((i: Int) => i.toString)
val B = Pipeline((s: String) => s + "!")

val C = A ~> B  // Int => String
```

## Complex chaining

```scala
import etl4s._

val A = Pipeline("data")
val B = Pipeline(42)
val C = Transform[String, String](_.toUpperCase)
val D = Load[String, Unit](println)

val pipeline =
  for {
    str <- A
    num <- B
    _ <- Extract(s"$str-$num") ~> C ~> D
  } yield ()
```

## Parallel extraction with `.zip`

Flatten nested tuples from parallel operations:

```scala
val e1 = Extract(1)
val e2 = Extract("two")
val e3 = Extract(3.0)

val combined = (e1 & e2 & e3).zip  // (Int, String, Double) not ((Int, String), Double)
```

## Debugging with `.tap`

Inspect values mid-pipeline without affecting the flow:

```scala
val pipeline = extract
  .tap(data => println(s"Extracted: $data"))
  ~> transform
  .tap(result => println(s"Transformed: $result"))
  ~> load
```

## Sequential side-effects with `>>`

Run multiple effects with the same input:

```scala
val logToFile: Node[Data, Unit] = ...
val logToConsole: Node[Data, Unit] = ...
val sendAlert: Node[Data, Unit] = ...

val logEverywhere = logToFile >> logToConsole >> sendAlert
```

## Conditional branching

Route data based on conditions:

```scala
val classify = Node[Int, Int](identity)
  .when(_ < 0)(Node(_ => "negative"))
  .elseIf(_ == 0)(Node(_ => "zero"))
  .otherwise(Node(_ => "positive"))

classify.unsafeRun(-5)  // "negative"
classify.unsafeRun(0)   // "zero"
classify.unsafeRun(10)  // "positive"
```

## Error handling with `.onFailure`

Provide fallback values:

```scala
val risky = Node[String, Int](_.toInt)
  .onFailure(_ => -1)

risky.unsafeRun("42")   // 42
risky.unsafeRun("bad")  // -1
```

## Retry with backoff

```scala
val flaky = Node[String, Response](callExternalApi)
  .withRetry(maxAttempts = 3, initialDelayMs = 100, backoffMultiplier = 2.0)
```

## Reactive pipelines with Trace

Branch on upstream errors:

```scala
val upstream = Transform[String, Int] { input =>
  if (input.isEmpty) Trace.error("Empty input")
  input.length
}

val downstream = Transform[Int, String] { value =>
  if (Trace.hasErrors) "FALLBACK"
  else s"Length: $value"
}

val pipeline = upstream ~> downstream

pipeline.unsafeRun("")      // "FALLBACK"
pipeline.unsafeRun("hello") // "Length: 5"
```

## Multi-source ETL

```scala
case class User(name: String, age: Int)
case class Order(id: String, amount: Double)
case class Enriched(user: User, order: Order, tax: Double)

val users = Extract("user123") ~> Transform(id => User(s"User $id", 30))
val orders = Extract("order456") ~> Transform(id => Order(id, 99.99))

val enrich = Transform[(User, Order), Enriched] { case (u, o) =>
  Enriched(u, o, o.amount * 0.1)
}

val save = Load[Enriched, String](e => s"Saved: ${e.user.name}")

val pipeline = (users & orders) ~> enrich ~> save
```
