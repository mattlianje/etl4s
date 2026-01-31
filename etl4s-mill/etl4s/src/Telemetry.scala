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
