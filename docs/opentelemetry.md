# Telemetry

When writing ETL jobs, you often need to:

- Count records processed, track durations, measure data quality
- Ship metrics to Prometheus, DataDog, or whatever your infra uses
- Have zero overhead in dev, real metrics in prod

`Tel` gives you this. It's a thin interface over your metrics backend. No-ops by default, wired up when you provide an implementation.

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

## Quick Setup: Env-Based Telemetry

A common pattern is to wire telemetry based on environment:

```scala
object TelemetryConfig {
  implicit val telemetry: Etl4sTelemetry =
    if (sys.env.getOrElse("ENV", "dev") == "prod")
      OpenTelemetryProvider()    /* Real metrics in prod */
    else
      Etl4sConsoleTelemetry()    /* Print to stdout in dev */
}

/* In your pipeline code */
import TelemetryConfig._

val pipeline = extract ~> process ~> load
pipeline.unsafeRun()  /* Automatically uses the right backend */
```

## The Interface

```scala
trait Etl4sTelemetry {
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T
  def addCounter(name: String, value: Long): Unit
  def setGauge(name: String, value: Double): Unit
  def recordHistogram(name: String, value: Double): Unit
}
```

Your implementation connects to OpenTelemetry SDK, Prometheus, DataDog, New Relic, CloudWatch, or whatever you use.

## Why Telemetry in ETL Business Logic

In web apps, telemetry is often a cross-cutting concern. ETL is different. In batch/streaming jobs, metrics are frequently business-critical:

```scala
val processUsers = Transform[List[RawUser], List[ValidUser]] { rawUsers =>
  val validated = rawUsers.filter(isValid)
  val invalidCount = rawUsers.size - validated.size

  /* These ARE business metrics */
  Tel.addCounter("users.processed", rawUsers.size)
  Tel.addCounter("users.invalid", invalidCount)
  Tel.setGauge("data.quality.ratio", validated.size.toDouble / rawUsers.size)

  if (invalidCount > threshold) {
    Tel.addCounter("pipeline.quality.failures", 1)
    throw new DataQualityException("Too many invalid records")
  }

  validated
}
```

These aren't just "monitoring metrics" - they're business KPIs:

- Record counts determine billing and SLAs
- Data quality ratios trigger business alerts
- Throughput metrics inform capacity planning

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

## Nested Spans

Spans automatically nest:
```scala
Tel.withSpan("outer") {
  Tel.withSpan("inner") {
    computeResult()
  }
}
```

## Span Attributes

```scala
Tel.withSpan("processing",
  "input.size" -> data.size,
  "batch.id" -> batchId
) {
  /* processing logic */
}
```

## API Reference

### Tel Object
| Method | Description |
|:-------|:------------|
| `Tel.withSpan(name)(block)` | Execute block in named span |
| `Tel.addCounter(name, value)` | Increment counter |
| `Tel.setGauge(name, value)` | Set gauge value |
| `Tel.recordHistogram(name, value)` | Record histogram value |

### Built-in Implementations
| Implementation | Description |
|:---------|:------------|
| `Etl4sConsoleTelemetry()` | Prints to stdout |
| `Etl4sNoOpTelemetry` | Silent no-op (default) |
