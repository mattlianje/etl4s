**etl4s** provides built-in failure handling:

## .withRetry
Retry failed operations with exponential backoff using `.withRetry`:
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
