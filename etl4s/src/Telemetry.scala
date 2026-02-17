/*
 * +==========================================================================+
 * |                                 etl4s                                    |
 * |                     Powerful, whiteboard-style ETL                       |
 * |                 Compatible with Scala 2.12, 2.13, and 3                  |
 * |                                                                          |
 * | Copyright 2025 Matthieu Court (matthieu.court@protonmail.com)            |
 * | Apache License 2.0                                                       |
 * +==========================================================================+
 */

package etl4s

/**
 * Minimal interface for OpenTelemetry integration.
 * Direct method calls avoid intermediate object creation.
 */
trait Etl4sTelemetry {
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T
  def addCounter(name: String, value: Long): Unit
  def setGauge(name: String, value: Double): Unit
  def recordHistogram(name: String, value: Double): Unit
}

/**
 * No-op implementation for when observability is not needed.
 * This is the default when no implicit Etl4sTelemetry is provided.
 */
object Etl4sNoOpTelemetry extends Etl4sTelemetry {
  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = block
  def addCounter(name: String, value: Long): Unit                           = ()
  def setGauge(name: String, value: Double): Unit                           = ()
  def recordHistogram(name: String, value: Double): Unit                    = ()
}

/**
 * Console-based observability provider for development and testing.
 * Prints telemetry data to stdout with timestamps.
 */
case class Etl4sConsoleTelemetry(prefix: String = "[ETL4S]") extends Etl4sTelemetry {

  def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
    val startTime = System.currentTimeMillis()
    println(s"$prefix [SPAN START] $name")

    if (attributes.nonEmpty) {
      println(
        s"$prefix [ATTRIBUTES] ${attributes.map { case (k, v) => s"$k=$v" }.mkString(", ")}"
      )
    }

    try {
      val result   = block
      val duration = System.currentTimeMillis() - startTime
      println(s"$prefix [SPAN END] $name (${duration}ms)")
      result
    } catch {
      case e: Throwable =>
        val duration = System.currentTimeMillis() - startTime
        println(s"$prefix [SPAN ERROR] $name (${duration}ms): ${e.getMessage}")
        throw e
    }
  }

  def addCounter(name: String, value: Long): Unit = {
    println(s"$prefix [COUNTER] $name: +$value")
  }

  def setGauge(name: String, value: Double): Unit = {
    println(s"$prefix [GAUGE] $name: $value")
  }

  def recordHistogram(name: String, value: Double): Unit = {
    println(s"$prefix [HISTOGRAM] $name: $value")
  }
}

/**
 * Exception thrown when validation fails.
 */
class ValidationException(message: String) extends RuntimeException(message)

/**
 * Represents a span in the telemetry trace.
 * Follows OTLP span conventions for compatibility with OpenTelemetry exporters.
 */
case class TelSpan(
  name: String,
  traceId: String,
  spanId: String,
  parentSpanId: Option[String] = None,
  startTimeNanos: Long = 0L,
  endTimeNanos: Long = 0L,
  durationNanos: Long = 0L,
  attributes: Map[String, Any] = Map.empty,
  status: String = "ok"
)

/** Counter metric recorded at a specific point in time */
case class TelCounter(name: String, value: Long, timestampNanos: Long)

/** Gauge metric recorded at a specific point in time */
case class TelGauge(name: String, value: Double, timestampNanos: Long)

/** Histogram metric recorded at a specific point in time */
case class TelHistogram(name: String, value: Double, timestampNanos: Long)

/**
 * Aggregated telemetry data collected during pipeline execution.
 * Contains all spans and metrics recorded via Tel calls.
 */
case class TelemetryData(
  spans: List[TelSpan] = List.empty,
  counters: List[TelCounter] = List.empty,
  gauges: List[TelGauge] = List.empty,
  histograms: List[TelHistogram] = List.empty
) {

  /** Get total value for each counter */
  def counterTotals: Map[String, Long] =
    counters.groupBy(_.name).map { case (n, cs) => n -> cs.map(_.value).sum }

  /** Get latest value for each gauge */
  def latestGauges: Map[String, Double] =
    gauges.groupBy(_.name).map { case (n, gs) => n -> gs.maxBy(_.timestampNanos).value }

  /** Get all histogram values grouped by name */
  def histogramValues: Map[String, List[Double]] =
    histograms.groupBy(_.name).map { case (n, hs) => n -> hs.reverse.map(_.value) }
}

/**
 * JSON helpers for producing OTEL-compatible trace output.
 */
private[etl4s] object TraceJsonHelpers {
  import LineageJsonHelpers._

  def anyToJson(v: Any): String = v match {
    case s: String  => quote(s)
    case n: Int     => n.toString
    case n: Long    => n.toString
    case n: Double  => n.toString
    case n: Float   => n.toString
    case b: Boolean => b.toString
    case other      => quote(other.toString)
  }

  def spanToJson(s: TelSpan): String = {
    val fields = List(
      jsonField("name", quote(s.name)),
      jsonField("traceId", quote(s.traceId)),
      jsonField("spanId", quote(s.spanId)),
      jsonField("startTimeUnixNano", s.startTimeNanos.toString),
      jsonField("endTimeUnixNano", s.endTimeNanos.toString),
      jsonField("status", jsonObject(jsonField("code", "1")))
    ) ++ s.parentSpanId.map(p => jsonField("parentSpanId", quote(p))).toList ++
      (if (s.attributes.nonEmpty)
         List(
           jsonField(
             "attributes",
             jsonArray(s.attributes.toSeq) { case (k, v) =>
               jsonObject(jsonField("key", quote(k)), jsonField("value", anyToJson(v)))
             }
           )
         )
       else Nil)
    jsonObject(fields: _*)
  }

  def counterToJson(name: String, total: Long): String =
    jsonObject(
      jsonField("name", quote(name)),
      jsonField(
        "sum",
        jsonObject(
          jsonField("dataPoints", s"""[{"asInt":"$total"}]"""),
          jsonField("isMonotonic", "true")
        )
      )
    )

  def gaugeToJson(name: String, value: Double): String =
    jsonObject(
      jsonField("name", quote(name)),
      jsonField("gauge", jsonObject(jsonField("dataPoints", s"""[{"asDouble":$value}]""")))
    )

  def histogramToJson(name: String, values: List[Double]): String = {
    val count = values.size
    val sum   = values.sum
    jsonObject(
      jsonField("name", quote(name)),
      jsonField(
        "histogram",
        jsonObject(
          jsonField("dataPoints", s"""[{"count":"$count","sum":$sum}]""")
        )
      )
    )
  }

  def toOtelJson(telemetry: TelemetryData): String = {
    val spansJson  = jsonArray(telemetry.spans)(spanToJson)
    val scopeSpans =
      s"""{"scope":{"name":"etl4s"},"spans":$spansJson}"""

    val metricsJson = {
      val counters   = telemetry.counterTotals.map { case (n, v) => counterToJson(n, v) }
      val gauges     = telemetry.latestGauges.map { case (n, v) => gaugeToJson(n, v) }
      val histograms = telemetry.histogramValues.map { case (n, vs) => histogramToJson(n, vs) }
      (counters ++ gauges ++ histograms).mkString("[", ",", "]")
    }
    val scopeMetrics =
      s"""{"scope":{"name":"etl4s"},"metrics":$metricsJson}"""

    s"""{"resourceSpans":[{"resource":{},"scopeSpans":[$scopeSpans]}],"resourceMetrics":[{"resource":{},"scopeMetrics":[$scopeMetrics]}]}"""
  }
}
