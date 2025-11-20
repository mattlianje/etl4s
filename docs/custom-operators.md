# Custom Operators

Build your own pipeline operators for domain-specific patterns.

## Creating a custom operator

Operators are just methods on `Node`. You can add your own via extension methods:

```scala
import etl4s._

extension [A, B](node: Node[A, B]) {
  
  // Custom operator: log execution time
  def timed(label: String): Node[A, B] = {
    Transform[A, B] { input =>
      val start = System.currentTimeMillis()
      val result = node(input)
      val duration = System.currentTimeMillis() - start
      Trace.log(s"$label took ${duration}ms")
      result
    }
  }
  
  // Custom operator: retry with custom logic
  def retryWhen(predicate: Throwable => Boolean, attempts: Int): Node[A, B] = {
    Transform[A, B] { input =>
      var lastError: Throwable = null
      var remaining = attempts
      
      while (remaining > 0) {
        try {
          return node(input)
        } catch {
          case e: Throwable if predicate(e) =>
            lastError = e
            remaining -= 1
            Trace.log(s"Retry attempt ${attempts - remaining}")
        }
      }
      throw lastError
    }
  }
}

// Usage
val pipeline = extract ~> 
  transform.timed("Transform stage") ~> 
  load.retryWhen(_.getMessage.contains("timeout"), 3)
```

## Domain-specific operators

```scala
// For data quality pipelines
extension [A](node: Node[A, List[Record]]) {
  def withQualityChecks(rules: List[QualityRule]): Node[A, (List[Record], List[QualityIssue])] = {
    Transform[A, (List[Record], List[QualityIssue])] { input =>
      val records = node(input)
      val issues = rules.flatMap { rule =>
        records.flatMap(rule.check)
      }
      
      Tel.addCounter("quality_issues", issues.size)
      issues.foreach(issue => Trace.error(issue.toString))
      
      (records, issues)
    }
  }
}

// Usage
val pipeline = extract ~> 
  transform.withQualityChecks(dataQualityRules) ~>
  load
```

## Composing operators

```scala
// Combine multiple operators into one
extension [A, B](node: Node[A, B]) {
  def withFullObservability(label: String): Node[A, B] = {
    node
      .timed(label)
      .tap(result => Tel.addCounter(s"${label}_executions", 1))
      .tap(_ => Trace.log(s"$label completed"))
  }
}

val pipeline = extract ~> 
  transform.withFullObservability("main_transform") ~> 
  load
```

## Operator for conditional execution

```scala
extension [A, B](node: Node[A, B]) {
  def runIf(condition: A => Boolean, fallback: B): Node[A, B] = {
    Transform[A, B] { input =>
      if (condition(input)) {
        node(input)
      } else {
        Trace.log("Condition not met, using fallback")
        fallback
      }
    }
  }
}

val pipeline = extract ~>
  transform.runIf(_.size > 0, List.empty) ~>
  load
```

## Operator for rate limiting

```scala
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

class RateLimiter(requestsPerSecond: Int) {
  private val lastRequest = new AtomicLong(0)
  private val minInterval = 1000 / requestsPerSecond
  
  def throttle(): Unit = {
    val now = System.currentTimeMillis()
    val last = lastRequest.get()
    val elapsed = now - last
    
    if (elapsed < minInterval) {
      Thread.sleep(minInterval - elapsed)
    }
    
    lastRequest.set(System.currentTimeMillis())
  }
}

extension [A, B](node: Node[A, B]) {
  def rateLimit(requestsPerSecond: Int): Node[A, B] = {
    val limiter = new RateLimiter(requestsPerSecond)
    
    Transform[A, B] { input =>
      limiter.throttle()
      node(input)
    }
  }
}

// Usage: limit to 10 requests per second
val pipeline = extract ~>
  apiCall.rateLimit(10) ~>
  load
```

## Operator for batching

```scala
extension [A](node: Node[List[A], List[B]]) {
  def batched(batchSize: Int): Node[List[A], List[B]] = {
    Transform[List[A], List[B]] { input =>
      input
        .grouped(batchSize)
        .zipWithIndex
        .flatMap { case (batch, idx) =>
          Tel.addCounter("batches_processed", 1)
          Trace.log(s"Processing batch $idx of size ${batch.size}")
          node(batch)
        }
        .toList
    }
  }
}

val pipeline = extract ~>
  transform.batched(100) ~>
  load
```

## Operator for caching

```scala
class Cache[A, B] {
  private val cache = scala.collection.mutable.Map[A, B]()
  
  def getOrCompute(key: A)(compute: => B): B = {
    cache.get(key) match {
      case Some(value) =>
        Tel.addCounter("cache_hits", 1)
        value
      case None =>
        Tel.addCounter("cache_misses", 1)
        val value = compute
        cache(key) = value
        value
    }
  }
}

extension [A, B](node: Node[A, B]) {
  def cached(cache: Cache[A, B]): Node[A, B] = {
    Transform[A, B] { input =>
      cache.getOrCompute(input) {
        node(input)
      }
    }
  }
}

val cache = new Cache[String, Data]()
val pipeline = extract.cached(cache) ~> transform ~> load
```

## Operator for deadlines

```scala
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

extension [A, B](node: Node[A, B]) {
  def withDeadline(timeout: Duration): Node[A, B] = {
    Transform[A, B] { input =>
      val future = Future { node(input) }
      
      try {
        Await.result(future, timeout)
      } catch {
        case _: TimeoutException =>
          Trace.error(s"Operation exceeded deadline of $timeout")
          throw new RuntimeException(s"Deadline exceeded: $timeout")
      }
    }
  }
}

val pipeline = extract ~>
  slowTransform.withDeadline(30.seconds) ~>
  load
```

## Key points

- Operators are extension methods on `Node`
- Build domain-specific abstractions for your team
- Compose operators freely
- Use `Tel` and `Trace` for observability in custom operators
- Keep operators generic and reusable


