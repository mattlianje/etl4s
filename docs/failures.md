
**etl4s** comes with 2 methods you can use (on a `Node` or `Pipeline`) to handle failures out of the box:

### `withRetry`
Give retry capability using the built-in `withRetry`:
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
This prints:
```
Success after 3 attempts
```

### `onFailure`
Catch exceptions and perform some action:
```scala
import etl4s._

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
