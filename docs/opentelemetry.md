# OpenTelemetry Integration

Add distributed tracing to etl4s pipelines.

## Philosophy

**etl4s** gives you one clean, OpenTelemetry-compatible way to think about telemetry in ETL. The idea is "constraints liberate" - `etl4s.OTel` provides the simplest, most constraining interface covering 95% of telemetry needs:

- **Spans** for tracing execution flow
- **Metrics** (counters, gauges, histograms) for measurement  
- **Events and attributes** for context
- **Nothing more**

This tiny interface forces clarity and prevents bloat. You sprinkle telemetry calls throughout your ETL code - they're silent no-ops until you bring your own OpenTelemetry provider into scope. You control the complexity.

During development and testing, telemetry calls are complete no-ops with zero overhead. In production, drop in any OpenTelemetry provider for spans, metrics, and dashboards.

## Basic Usage

```scala
import etl4s._

// Console provider for development
implicit val otel: OTelProvider = ConsoleOTelProvider()

val pipeline = Transform[String, Int] { input =>
  OTel.span("processing") {
    OTel.counter("items.processed", 1)
    OTel.gauge("input.length", input.length.toDouble)
    input.length
  }
}

pipeline.unsafeRun("hello")  // Prints telemetry to console
```

## Custom Provider

Implement `OTelProvider` to connect to your observability backend:

```scala
class MyOTelProvider extends OTelProvider {
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
    // Your span implementation
    block
  }
  
  def addCounter(name: String, value: Long): Unit = {
    // Your counter implementation
  }
  
  def setGauge(name: String, value: Double): Unit = {
    // Your gauge implementation  
  }
  
  def recordHistogram(name: String, value: Double): Unit = {
    // Your histogram implementation
  }
}
```

## Real OpenTelemetry SDK

```scala
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
// ... other imports

class RealOTelProvider extends OTelProvider {
  private val sdk: OpenTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(/* your tracer config */)
    .setMeterProvider(/* your meter config */)
    .build()
    
  private val tracer = sdk.getTracer("my-app")
  private val meter = sdk.getMeter("my-app")
  
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
    val span = tracer.spanBuilder(name).startSpan()
    try block finally span.end()
  }
  
  def addCounter(name: String, value: Long): Unit = {
    meter.counterBuilder(name).build().add(value)
  }
  
  // ... implement other methods
}
```

## Nested Spans

Spans automatically nest when called within each other:

```scala
val node = Transform[String, Int] { input =>
  OTel.span("outer") {
    OTel.counter("outer.ops", 1)
    
    val length = OTel.span("inner") {
      OTel.counter("inner.ops", 1)
      input.length
    }
    
    length
  }
}
```

## Span Attributes

Add metadata to spans:

```scala
val node = Transform[String, String] { input =>
  OTel.span("processing",
    "input.length" -> input.length,
    "type" -> "uppercase"
  ) {
    input.toUpperCase
  }
}
```

## No-Op by Default

Without an `OTelProvider`, all calls are no-ops with zero overhead:

```scala
// No implicit provider - all OTel calls do nothing
val node = Transform[String, String] { input =>
  OTel.span("processing") {
    OTel.counter("processed", 1)
    input.toUpperCase
  }
}
```

## API Reference

### OTel Object

| Method | Description |
|:-------|:------------|
| `OTel.span(name)(block)` | Execute block in named span |
| `OTel.counter(name, value)` | Increment counter |
| `OTel.gauge(name, value)` | Set gauge value |
| `OTel.histogram(name, value)` | Record histogram value |
| `OTel.addEvent(name, attrs*)` | Add span event (no-op in base) |

### OTelProvider Interface

| Method | Description |
|:-------|:------------|
| `withSpan(name, attrs*)(block)` | Create span around block |
| `addCounter(name, value)` | Record counter increment |
| `setGauge(name, value)` | Set gauge to value |
| `recordHistogram(name, value)` | Record histogram measurement |

### Built-in Providers

| Provider | Description |
|:---------|:------------|
| `ConsoleOTelProvider()` | Prints to stdout |
| `NoOpOTel` | Silent no-op implementation |

The OpenTelemetry integration follows the same principle as the rest of etl4s: simple, composable, and zero overhead when not used.