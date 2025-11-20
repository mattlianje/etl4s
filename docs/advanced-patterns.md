# Advanced Patterns

Sophisticated patterns for complex pipelines.

## Dynamic branching based on data

```scala
case class Data(value: Int, category: String)

val extract = Extract[Unit, List[Data]](...)

val routeByCategory = Transform[List[Data], Map[String, List[Data]]] { data =>
  data.groupBy(_.category)
}

val processA = Transform[List[Data], List[Data]](/* specific logic for A */)
val processB = Transform[List[Data], List[Data]](/* specific logic for B */)

// Route to different processors based on category
val processRouted = Transform[Map[String, List[Data]], List[Data]] { grouped =>
  val resultA = grouped.get("A").map(processA.apply).getOrElse(List.empty)
  val resultB = grouped.get("B").map(processB.apply).getOrElse(List.empty)
  resultA ++ resultB
}

val pipeline = extract ~> routeByCategory ~> processRouted ~> load
```

## Pipeline composition - building blocks

```scala
// Define reusable pipeline segments
def validationStage[T](validator: T => Boolean): Node[T, T] = {
  Transform[T, T] { data =>
    if (validator(data)) {
      data
    } else {
      Trace.error(s"Validation failed: $data")
      data
    }
  }
}

def loggingStage[T](label: String): Node[T, T] = {
  Transform[T, T] { data =>
    Trace.log(s"$label: $data")
    data
  }
}

// Compose reusable stages
val pipeline = 
  extract ~>
  loggingStage("After extract") ~>
  validationStage(_ => true) ~>
  transform ~>
  loggingStage("After transform") ~>
  load
```

## Fan-out, fan-in pattern

```scala
// One input, multiple parallel processors, combine results
val extract = Extract[Unit, List[Int]](List(1, 2, 3, 4, 5))

val sumProcessor = Transform[List[Int], Int](_.sum)
val maxProcessor = Transform[List[Int], Int](_.max)
val countProcessor = Transform[List[Int], Int](_.size)

val combine = Transform[(Int, Int, Int), String] { case (sum, max, count) =>
  s"sum=$sum, max=$max, count=$count"
}

val pipeline = extract ~> (sumProcessor & maxProcessor & countProcessor) ~> combine
```

## Conditional execution with error handling

```scala
case class Result(data: List[String], hasErrors: Boolean)

val riskyTransform = Transform[List[String], Result] { data =>
  try {
    val processed = data.map(_.toInt)
    Result(processed.map(_.toString), hasErrors = false)
  } catch {
    case e: NumberFormatException =>
      Trace.error(s"Parse error: ${e.getMessage}")
      Result(data, hasErrors = true)
  }
}

val conditionalLoad = Load[Result, Unit] { result =>
  if (result.hasErrors) {
    // Send to dead letter queue
    println(s"Quarantine: ${result.data}")
  } else {
    // Normal processing
    println(s"Success: ${result.data}")
  }
}

val pipeline = extract ~> riskyTransform ~> conditionalLoad
```

## Multi-stage config injection

```scala
case class ExtractConfig(source: String)
case class TransformConfig(rules: Map[String, String])
case class LoadConfig(destination: String)

// Each stage requires different config
val extract = Extract[Unit, Data]
  .requires[ExtractConfig] { cfg => _ =>
    // Use cfg.source
    Data(...)
  }

val transform = Transform[Data, Data]
  .requires[TransformConfig] { cfg => data =>
    // Use cfg.rules
    data.copy(...)
  }

val load = Load[Data, Unit]
  .requires[LoadConfig] { cfg => data =>
    // Use cfg.destination
    save(cfg.destination, data)
  }

val pipeline = extract ~> transform ~> load

// Provide all configs at once
val allConfigs = (
  ExtractConfig("s3://input"),
  TransformConfig(Map("a" -> "b")),
  LoadConfig("s3://output")
)

pipeline.provide(allConfigs).unsafeRun(())
```

## Nested parallelism

```scala
// Parallel stages, each with internal parallelism
val extractA = Extract(dataA)
val extractB = Extract(dataB)

val processA = Transform[DataA, ResultA] { data =>
  // Internal parallel operations
  val (part1, part2) = (processA1(data), processA2(data))
  combine(part1, part2)
}

val processB = Transform[DataB, ResultB] { data =>
  val (part1, part2) = (processB1(data), processB2(data))
  combine(part1, part2)
}

val merge = Transform[(ResultA, ResultB), Final] { case (a, b) =>
  mergeResults(a, b)
}

// Outer parallelism: A and B in parallel
// Inner parallelism: within processA and processB
val pipeline = (extractA ~> processA) & (extractB ~> processB) ~> merge
```

## Retry with exponential backoff

```scala
import scala.concurrent.duration._

val unstableExtract = Extract[Unit, Data](/* might fail */)
  .withRetry(
    attempts = 5,
    delay = 1.second,
    backoff = 2.0  // exponential: 1s, 2s, 4s, 8s, 16s
  )

val pipeline = unstableExtract ~> transform ~> load
```

## Streaming pattern with batching

```scala
case class Batch(items: List[Item], batchNumber: Int)

def batchProcessor(batchSize: Int): Node[List[Item], List[Result]] = {
  Transform[List[Item], List[Result]] { items =>
    items
      .grouped(batchSize)
      .zipWithIndex
      .flatMap { case (batch, idx) =>
        Trace.log(s"Processing batch $idx")
        Tel.addCounter("batches_processed", 1)
        Tel.addCounter("items_in_batch", batch.size)
        processBatch(batch)
      }
      .toList
  }
}

val pipeline = extract ~> batchProcessor(100) ~> load
```

## Circuit breaker pattern

```scala
class CircuitBreaker(threshold: Int) {
  private var failures = 0
  private var isOpen = false
  
  def call[T](f: => T): Option[T] = {
    if (isOpen) {
      Trace.error("Circuit breaker is OPEN")
      None
    } else {
      try {
        val result = f
        failures = 0  // reset on success
        Some(result)
      } catch {
        case e: Exception =>
          failures += 1
          if (failures >= threshold) {
            isOpen = true
            Trace.error(s"Circuit breaker OPENED after $failures failures")
          }
          None
      }
    }
  }
}

val breaker = new CircuitBreaker(threshold = 3)

val protectedLoad = Load[Data, Unit] { data =>
  breaker.call {
    // risky operation
    saveToExternalSystem(data)
  } match {
    case Some(_) => 
      Trace.log("Write successful")
    case None => 
      Trace.error("Write failed or circuit open")
  }
}
```

## Pipeline versioning and migration

```scala
// V1 pipeline
val pipelineV1 = extractV1 ~> transformV1 ~> loadV1

// V2 pipeline with new logic
val pipelineV2 = extractV2 ~> transformV2 ~> loadV2

// Run both in parallel for migration/comparison
val migrationPipeline = Extract[Unit, Data](...) ~> 
  (pipelineV1 & pipelineV2) ~>
  Transform[(ResultV1, ResultV2), Unit] { case (r1, r2) =>
    compareResults(r1, r2)
    ()
  }
```

## Caching intermediate results

```scala
val cache = scala.collection.mutable.Map[String, Data]()

val cachedExtract = Extract[String, Data]
  .requires[String] { key => _ =>
    cache.get(key) match {
      case Some(data) =>
        Trace.log(s"Cache HIT for $key")
        Tel.addCounter("cache_hits", 1)
        data
      case None =>
        Trace.log(s"Cache MISS for $key")
        Tel.addCounter("cache_misses", 1)
        val data = expensiveFetch(key)
        cache(key) = data
        data
    }
  }

val pipeline = cachedExtract ~> transform ~> load
```

## Key takeaways

- Pipelines are values - compose them freely
- Use `&` for parallel execution at any level
- `.requires` supports multiple config types
- `Trace` and `Tel` work seamlessly in complex patterns
- Types guide you - if it compiles, structure is sound


