---
api:
  - sig: ".withRetry"
  - sig: ".onFailure(handler)"
  - sig: ".compile[Try]"
  - sig: ".compile[Future]"
---

# Error Handling

**etl4s** provides built-in failure handling:

## .withRetry
Retry failed operations with exponential backoff using `.withRetry`. The full
signature has sensible defaults:

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

val pipeline = Extract(42) ~> riskyTransformWithRetry
pipeline.unsafeRun(())
```
Output:
```
Success after 3 attempts
```

## .onFailure
Catch exceptions and provide fallback values using `.onFailure`:
```scala
import etl4s._

val riskyExtract =
    Extract[Unit, String](_ => throw new RuntimeException("Boom!"))

val safeExtract = riskyExtract.onFailure(e => s"Failed: ${e.getMessage}")
val consoleLoad = Load[String, Unit](println(_))

val pipeline = safeExtract ~> consoleLoad
pipeline.unsafeRun(())
```
Output:
```
Failed: Boom!
```

## Failures under effects

`.unsafeRun` throws when a node fails. To capture the failure as a value, run
the pipeline through an effect with `.compile[F]`:

- `.compile[Try].unsafeRun(...)` returns a `Failure(e)` instead of throwing.
- `.compile[Future].unsafeRun(...)` returns a failed `Future`.

```scala
import etl4s._
import scala.util.Try

val risky = Extract[Unit, String](_ => throw new RuntimeException("Boom!"))

risky.compile[Try].unsafeRun(())
```
You will get:
```
Failure(RuntimeException("Boom!"))
```

See [Effect polymorphism](effect-polymorphism.md) for the full list of effects
and how to add your own.
