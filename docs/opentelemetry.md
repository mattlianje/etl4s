# Telemetry

etl4s provides a minimal telemetry interface. This interface exists to decouple telemetry from specific backends - write once, backend anywhere.

## How It Works

The `Etl4sTelemetry` trait defines the core interface:

```scala
trait Etl4sTelemetry {
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T
  def addCounter(name: String, value: Long): Unit
  def setGauge(name: String, value: Double): Unit  
  def recordHistogram(name: String, value: Double): Unit
}
```

All etl4s pipeline run methods automatically look for `Etl4sTelemetry` in implicit scope.

The `Tel` object provides a convenient API with identical method names to the trait. By default, all `Tel` calls are no-ops with zero overhead until you provide an implementation.

**Your implementation connects to:** OpenTelemetry SDK, Prometheus, DataDog, New Relic, CloudWatch, or whatever you want.

## Usage

```scala
val process = Transform[List[String], Int] { data =>
  Tel.withSpan("processing") {
    Tel.addCounter("items", data.size)
    data.map(_.length).sum
  }
}

/* Development: no-ops (zero cost) */
process.unsafeRun(data)

/* Production: your backend */
implicit val telemetry: Etl4sTelemetry = MyPrometheusProvider()
process.unsafeRun(data)
```

**Key benefits:**

- Write business critical telemetry in business logic, not infrastructure code
- Zero performance cost until enabled  
- Works on any platform: local JVM, Spark, Kubernetes, Lambda
- No framework lock-in or context threading

## Why Telemetry (sometimes) belongs in ETL Business Logic

In many web and OLTP programs, telemetry is often a cross-cutting concern separate from business logic. **ETL is different.** In OLAP processes, observability metrics are frequently business-critical (especially at the peripheries in Extractors and Loaders):

```scala
val processUsers = Transform[List[RawUser], List[ValidUser]] { rawUsers =>
  val validated = rawUsers.filter(isValid)
  val invalidCount = rawUsers.size - validated.size
  
  /* This IS business logic - the business needs these metrics */
  Tel.addCounter("users.processed", rawUsers.size) 
  Tel.addCounter("users.invalid", invalidCount)
  Tel.setGauge("data.quality.ratio", validated.size.toDouble / rawUsers.size)
  
  /* Business decision based on data quality */
  if (invalidCount > threshold) {
    Tel.addCounter("pipeline.quality.failures", 1)
    throw new DataQualityException("Too many invalid records")
  }
  
  validated
}
```

**In the above example - these aren't just "monitoring metrics" - they're business KPIs:**

- Record counts determine billing and SLAs
- Processing times affect customer experience  
- Data quality ratios trigger business alerts
- Throughput metrics inform capacity planning

etl4s makes it safe to instrument business logic directly because `Tel` calls are zero-cost no-ops by default. You get the observability where it matters most - in the business context - without infrastructure coupling.

## Implementation Examples

### OpenTelemetry SDK
```scala
class OpenTelemetryProvider extends Etl4sTelemetry {
  private val tracer = GlobalOpenTelemetry.getTracer("my-app")
  private val meter = GlobalOpenTelemetry.getMeter("my-app")
  
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
    val span = tracer.spanBuilder(name).startSpan()
    try block finally span.end()
  }
  
  def addCounter(name: String, value: Long): Unit = {
    meter.counterBuilder(name).build().add(value)
  }
  /* ... implement setGauge, recordHistogram */
}
```

### Prometheus
```scala
class PrometheusProvider extends Etl4sTelemetry {
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
    val timer = Timer.start()
    try block finally histogram.labels(name).observe(timer.observeDuration())
  }
  
  def addCounter(name: String, value: Long): Unit = {
    Counter.build().name(name).register().inc(value)
  }
  /* ... implement setGauge, recordHistogram */
}
```

### Console (Built-in)
```scala
/* Development telemetry - prints to stdout */
implicit val telemetry: Etl4sTelemetry = Etl4sConsoleTelemetry()
```

## Advanced Features

### Span Attributes
```scala
Tel.withSpan("processing",
  "input.size" -> data.size,
  "batch.id" -> batchId
) {
  /* processing logic */
}
```

### Nested Spans
Spans automatically nest when called within each other:
```scala
Tel.withSpan("outer") {
  val result = Tel.withSpan("inner") {
    /* nested processing */
    computeResult()
  }
  result
}
```

### No-Op by Default
Without an `Etl4sTelemetry`, all calls are no-ops with zero overhead:
```scala
/* No implicit provider - all Tel calls do nothing */
Tel.withSpan("processing") { Tel.addCounter("processed", 1) }
```

## API Reference

### Tel Object
| Method | Description |
|:-------|:------------|
| `Tel.withSpan(name)(block)` | Execute block in named span |
| `Tel.addCounter(name, value)` | Increment counter |
| `Tel.setGauge(name, value)` | Set gauge value |
| `Tel.recordHistogram(name, value)` | Record histogram value |

### Etl4sTelemetry Interface
| Method | Description |
|:-------|:------------|
| `withSpan(name, attrs*)(block)` | Create span around block |
| `addCounter(name, value)` | Record counter increment |
| `setGauge(name, value)` | Set gauge to value |
| `recordHistogram(name, value)` | Record histogram measurement |

### Built-in Implementations
| Implementation | Description |
|:---------|:------------|
| `Etl4sConsoleTelemetry()` | Prints to stdout |
| `Etl4sNoOpTelemetry` | Silent no-op (default) |