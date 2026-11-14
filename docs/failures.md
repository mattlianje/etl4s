---
api:
  - sig: ".withRetry"
  - sig: ".onFailure(handler)"
  - sig: ".compile[Try]"
  - sig: ".compile[Future]"
---

# Error Handling

## Retry with `.withRetry`
Retry a failing node with exponential backoff. Every parameter has a default:

```scala
def withRetry(
  maxAttempts: Int = 3,
  initialDelayMs: Long = 100,
  backoffFactor: Double = 2.0
): Node[A, B]
```

```scala
import etl4s._

var attempts = 0

val riskyTransformWithRetry = Transform[Int, String] {
    n =>
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"Attempt $attempts failed")
      else s"Success after $attempts attempts"
}.withRetry(maxAttempts = 3, initialDelayMs = 10)

val p = 
     Extract(42) ~> riskyTransformWithRetry

p.unsafeRun()
```
Output:
```
Success after 3 attempts
```

## Recover with `.onFailure`
Catch an exception and return a fallback value instead:
```scala
import etl4s._

val riskyExtract = Node[Unit, String](_ => throw new RuntimeException("Boom!"))
val safeExtract = riskyExtract
                     .onFailure(e => s"Failed: ${e.getMessage}")
val consoleLoad = Node[String, Unit](println(_))

val p = 
     safeExtract ~> consoleLoad

p.unsafeRun()
```
Output:
```
Failed: Boom!
```

## Get failures as values

`.unsafeRun` throws when a node fails. To get the failure back as a value, compile to an effect:

- `.compile[Try].unsafeRun(...)` returns a `Failure(e)` instead of throwing.
- `.compile[Future].unsafeRun(...)` returns a failed `Future`.

```scala
import etl4s._
import scala.util.Try

val risky = Extract[Unit, String](_ => throw new RuntimeException("Boom!"))

risky.compile[Try].unsafeRun()
```
You will get:
```
Failure(RuntimeException("Boom!"))
```

See [Effect polymorphism](effect-polymorphism.md) for the other effects and how to add your own.
