package etl4s

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

class BasicSpecs extends munit.FunSuite {

  /** Using these little synthetic types in multiple tests
    */
  case class User(name: String, age: Int)
  case class Order(id: String, amount: Double)
  case class EnrichedOrder(user: User, order: Order, tax: Double)

  test("basic node composition") {
    val extract   = Extract[String, Int](str => str.length)
    val transform = Transform[Int, String](num => s"Length is $num")
    val load      = Load[String, Boolean](str => { println(str); true })

    val pipeline = extract ~> transform ~> load

    val result = pipeline.unsafeRun("Hello world!")

    assertEquals(result, true)
    assertEquals(pipeline.safeRun("Hello").get, true)
  }

  test("parallel execution with &") {
    val getName = Extract("John Doe")
    val getAge  = Extract(30)

    val userExtract = (getName & getAge).map { case (name, age) =>
      User(name, age)
    }

    val user = userExtract.unsafeRun(())
    assertEquals(user, User("John Doe", 30))
  }

  test("parallel execution with &>") {
    var started1, started2 = 0L

    val slow1 = Node[Unit, String] { _ =>
      started1 = System.currentTimeMillis()
      Thread.sleep(100)
      "result1"
    }

    val slow2 = Node[Unit, Int] { _ =>
      started2 = System.currentTimeMillis()
      Thread.sleep(100)
      42
    }

    val combined = (slow1 &> slow2).unsafeRun(())

    assertEquals(combined._1, "result1")
    assertEquals(combined._2, 42)
    assert(
      Math.abs(started1 - started2) < 50,
      "Operations should start at similar times"
    )
  }

  test("error handling with onFailure") {
    val failingNode = Node[String, Int] { s =>
      if (s.isEmpty) throw new RuntimeException("Empty input!")
      s.length
    }

    val safeNode = failingNode.onFailure(_ => -1)

    assertEquals(safeNode("hello"), 5)
    assertEquals(safeNode(""), -1)
  }

  test("retry functionality") {
    var attempts = 0

    val unstableNode = Node[String, Int] { s =>
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"Attempt $attempts failed")
      s.length
    }

    val resilientNode =
      unstableNode.withRetry(maxAttempts = 3, initialDelayMs = 10)

    val result = resilientNode.unsafeRun("test")
    assertEquals(result, 4)
    assertEquals(attempts, 3)
  }

  test("tap for debugging") {
    var intercepted = ""

    val pipeline =
      Extract("hello") ~>
        Transform[String, Int](_.length) ~>
        tap((n: Int) => intercepted = s"Length is $n") ~>
        Transform[Int, String](n => n.toString)

    val result = pipeline.unsafeRun(())

    assertEquals(result, "5")
    assertEquals(intercepted, "Length is 5")
  }

  test("sequential operations with >>") {
    var sequence = List.empty[String]

    val step1 = Node[Unit, Int] { _ =>
      sequence = sequence :+ "step1"
      10
    }

    val step2 = Node[Unit, String] { _ =>
      sequence = sequence :+ "step2"
      "done"
    }

    val pipeline = step1 >> step2

    val result = pipeline.unsafeRun(())
    assertEquals(result, "done")
    assertEquals(sequence, List("step1", "step2"))
  }

  test("type flattening with zip") {
    val e1 = Extract(1)
    val e2 = Extract("two")
    val e3 = Extract(3.0)

    val combined = (e1 &> e2 &> e3).zip

    val result = combined.unsafeRun(())
    assertEquals(result, (1, "two", 3.0))
  }

  test("etl pattern with multiple sources") {
    val userSource = Extract("user123") ~>
      Transform[String, User](id => User(s"User $id", 30))

    val orderSource = Extract("order456") ~>
      Transform[String, Order](id => Order(id, 99.99))

    val enrichOrders = Transform[(User, Order), EnrichedOrder] { case (user, order) =>
      EnrichedOrder(user, order, order.amount * 0.1)
    }

    val saveToDB = Load[EnrichedOrder, String] { enrichedOrder =>
      s"Saved: User=${enrichedOrder.user.name}, Order=${enrichedOrder.order.id}, Total=${enrichedOrder.order.amount + enrichedOrder.tax}"
    }

    val etlPipeline = (userSource & orderSource) ~> enrichOrders ~> saveToDB
    val result      = etlPipeline.unsafeRun(())

    assert(result.contains("Saved:"))
    assert(result.contains("User=User user123"))
    assert(result.contains("Order=order456"))
  }

  test("pretty pipeline 1") {

    val s1 = Extract("foo") ~> Transform[String, String](_.toUpperCase)
    val s2 = Extract(10) ~> Transform[Int, String](n => s"num:$n")
    val s3 = Extract(2.5) ~> Transform[Double, String](d => f"double:$d%.2f")

    val D: Transform[(String, String), String] = Transform { case (a, b) =>
      s"$a + $b"
    }
    val E: Transform[String, String] = Transform(c => s"$c processed")
    val F: Transform[(String, String), String] = Transform { case (d, e) =>
      s"[$d | $e]"
    }

    val w1: Load[String, String] = Load(s => { println(s"W1: $s"); s })
    val w2: Load[String, Unit]   = Load(s => println(s"W2: $s"))
    val w3: Load[String, Unit]   = Load(s => println(s"W3: $s"))

    val R = Load[(String, Unit, Unit), String](_._1)

    val pipeline: Pipeline[Unit, String] =
      (
        (s1 & s2) ~> D &
          (s3 ~> E)
      ) ~> F ~>
        (w1 & w2 & w3).zip ~> R

    val result = pipeline.unsafeRun(())
    assert(result == "[FOO + num:10 | double:2.50 processed]")
  }

  test("can zip max nodes") {
    /* Max is currently 10 */
    val e1   = Extract(1)
    val bigE = (e1 & e1 & e1 & e1 & e1 & e1 & e1 & e1 & e1 & e1).zip
  }

  test("can create and chain effect") {
    val e           = Node.effect { println("yo"); println("dawg") }
    val effectChain = e >> e >> e >> e >> e >> e
  }

  test("tap preserves data flow while performing side effects") {
    var processedData: Option[String] = None

    val getData = Extract((unit: Unit) => "test data")
    val logData = tap[String] { data =>
      processedData = Some(data)
      println(s"Processing: $data")
    }
    val transform = Transform[String, Int](_.length)

    val pipeline = getData ~> logData ~> transform

    val result = pipeline.unsafeRun(())

    assertEquals(result, 9)
    assertEquals(processedData, Some("test data"))
  }

  test("tap can be used for logging in pipeline composition") {
    var tempFilesCleaned = false

    val fetchData = Extract((unit: Unit) => List("file1.txt", "file2.txt"))
    val flushTempFiles = tap[List[String]] { files =>
      tempFilesCleaned = true
      println(s"Cleaning up ${files.size} temporary files...")
    }
    val processFiles = Transform[List[String], Int](_.size)

    val p = fetchData ~> flushTempFiles ~> processFiles

    val result = p.unsafeRun(())

    assertEquals(result, 2)
    assert(tempFilesCleaned, "Temp files should have been cleaned")
  }

  test("associative property holds") {
    val e1     = Extract(1)
    val plus1  = Transform[Int, Int](x => x + 2)
    val times5 = Transform[Int, Int](x => x * 5)

    val p1 = e1 ~> plus1 ~> times5
    val p2 = e1 ~> (plus1 ~> times5)

    assert(p1(()) == p2(()))
  }

  test("unsafeRunTrace measures execution time") {
    // Create a node that sleeps for a specific time
    val sleepDuration = 100
    val sleepNode = Node[Unit, Unit] { _ =>
      Thread.sleep(sleepDuration)
    }
    val insights    = sleepNode.unsafeRunTrace(())
    val elapsedTime = insights.timeElapsedMillis
    assert(
      elapsedTime >= sleepDuration,
      s"Elapsed time ($elapsedTime ms) should be at least $sleepDuration ms"
    )
    assert(
      elapsedTime < sleepDuration + 50,
      s"Elapsed time ($elapsedTime ms) should not be much longer than $sleepDuration ms"
    )
  }

  test("safeRunTrace handles failures gracefully") {
    val failingNode = Node[String, Int] { s =>
      if (s.isEmpty) throw new RuntimeException("Empty input!")
      s.length
    }

    val successTrace = failingNode.safeRunTrace("hello")
    assert(successTrace.result.isSuccess)
    assertEquals(successTrace.result.get, 5)
    assert(successTrace.timeElapsedMillis >= 0)

    val failureTrace = failingNode.safeRunTrace("")
    assert(failureTrace.result.isFailure)
    assert(failureTrace.result.failed.get.getMessage.contains("Empty input!"))
    assert(failureTrace.timeElapsedMillis >= 0) // Still get timing even on failure
  }

  test("metadata works") {
    case class Info(source: String)
    val node = Extract("data").withMetadata(Info("db"))

    assertEquals(node.metadata.asInstanceOf[Info].source, "db")
    assertEquals(node.unsafeRun(()), "data")
  }
}

class ReaderSpecs extends munit.FunSuite {
  test("reader/context functionality") {
    case class Config(prefix: String, multiplier: Int)

    val contextNode = Reader[Config, Node[String, String]] { ctx =>
      Node(input => s"${ctx.prefix}: $input")
    }

    val processNode = Reader[Config, Node[String, Int]] { ctx =>
      Node(str => str.length * ctx.multiplier)
    }

    val pipeline = contextNode ~> processNode

    val config = Config("MSG", 2)
    val result = pipeline.provideContext(config).unsafeRun("hello")

    assertEquals(result, 20)
  }

  test("regular node to reader-wrapped node composition") {
    case class Config(multiplier: Int)

    val stringToLength = Node[String, Int](str => str.length)

    val lengthProcessor = Reader[Config, Node[Int, String]] { ctx =>
      Node(length => s"Length after processing: ${length * ctx.multiplier}")
    }

    val pipeline = stringToLength ~> lengthProcessor

    val config = Config(3)
    val result = pipeline.provideContext(config).unsafeRun("hello")

    assertEquals(
      result,
      "Length after processing: 15"
    )

    val anotherConfig = Config(10)
    val anotherResult = pipeline.provideContext(anotherConfig).unsafeRun("hi")

    assertEquals(
      anotherResult,
      "Length after processing: 20"
    )
  }

  test("can chain Reader to supertype Reader with simple types") {
    trait HasBaseConfig { def appName: String }

    trait HasDateConfig extends HasBaseConfig {
      def startDate: String
      def endDate: String
    }

    trait HasExtendedDateConfig extends HasDateConfig {
      def dateFormat: String
    }

    case class FullConfig(
      appName: String,
      startDate: String,
      endDate: String,
      dateFormat: String
    ) extends HasExtendedDateConfig

    val formatTimestamp = Reader[HasExtendedDateConfig, Node[String, String]] { ctx =>
      Node { timestamp =>
        s"Formatted with ${ctx.dateFormat}: $timestamp (from ${ctx.appName})"
      }
    }

    val checkDateRange = Reader[HasDateConfig, Node[String, String]] { ctx =>
      Node { formatted =>
        s"$formatted - Range check: ${ctx.startDate} to ${ctx.endDate}"
      }
    }

    val pipeline = formatTimestamp ~> checkDateRange

    val config = FullConfig(
      appName = "DateProcessor",
      startDate = "2023-01-01",
      endDate = "2023-01-31",
      dateFormat = "yyyy-MM-dd"
    )

    val result = pipeline.provideContext(config).unsafeRun("2023-01-15")

    assert(result.contains("Formatted with yyyy-MM-dd"))
    assert(result.contains("from DateProcessor"))
    assert(result.contains("Range check: 2023-01-01 to 2023-01-31"))
  }

  test("etl4sContext and WithContext aliases") {
    case class AppConfig(serviceName: String, timeout: Int)

    object TestContext extends Context[AppConfig] {

      val extractWithContext: Reader[AppConfig, Extract[String, Int]] =
        Reader { ctx =>
          Extract { input =>
            s"${ctx.serviceName}: $input".length * ctx.timeout
          }
        }

      val transformWithContext: Reader[AppConfig, Transform[Int, String]] =
        Reader { ctx =>
          Transform { value =>
            s"Processed by ${ctx.serviceName} with value $value"
          }
        }

      val testC = Context.Extract[Int, Int] { ctx => x =>
        println(s"Yo ${ctx.serviceName}"); x * 2
      }
    }

    import TestContext._
    val pipeline = extractWithContext ~> transformWithContext

    val config = AppConfig("DataService", 2)
    val result = pipeline.provideContext(config).unsafeRun("test")
  }

  test("reader(node) all operators compat") {
    val r1    = Reader[Int, Transform[Int, Int]] { ctx => Transform(_ * 2) }
    val t1    = Transform[Int, Int](_ * 2)
    val tUnit = Transform[Unit, Unit](_ => ())
    val unitR = Reader[Int, Transform[Unit, Unit]] { ctx => Transform(_ => ()) }

    val test1 = r1 & r1 & r1 & t1
    val test2 = r1 &> r1 &> r1 &> t1
    val test3 = unitR >> Extract(1)
    val test4 = unitR >> unitR
    val test5 = t1 & r1
    val test6 = tUnit >> unitR
  }

  test("requires and provide") {
    case class Config(factor: Int)

    val node1 = Transform[Int, Int](identity)
      .requires[Config] { cfg => x => x * cfg.factor }

    val node2 = Transform.requires[Config, Int, Int] { cfg => x =>
      x * cfg.factor
    }
  }

  test("Etl4sContext companion object methods") {
    case class AppConfig(serviceName: String, timeout: Int)

    object TestContext extends Context[AppConfig] {
      val getData = Context.Extract[String, Int] { config => input =>
        s"${config.serviceName}: $input".length * config.timeout
      }

      val processData = Context.Transform[Int, String] { config => value =>
        s"Processed by ${config.serviceName} with value $value"
      }
    }

    import TestContext._
    val pipeline = getData ~> processData

    val config = AppConfig("DataService", 2)
    val result = pipeline.provideContext(config).unsafeRun("test")

    assertEquals(result, "Processed by DataService with value 34")
  }

  test("basic logging with Trace.log") {
    val node = Transform[String, Int] { input =>
      Trace.log("Processing string")
      val result = input.length
      Trace.log(s"$input -> $result")
      result
    }

    val insights = node.unsafeRunTrace("hello")
    assertEquals(insights.result, 5)
    assertEquals(insights.logs, List("Processing string", "hello -> 5"))
  }

  test("validation with Trace") {
    val node = Transform[String, Int] { input =>
      val result = input.length
      if (result <= 0) Trace.error("Length must be positive")
      if (input.isEmpty) Trace.error("Input cannot be empty")
      result
    }

    val insights = node.unsafeRunTrace("test")
    assertEquals(insights.result, 4)
    assertEquals(insights.errors, List.empty)
    assert(!insights.hasErrors)

    val failInsights = node.unsafeRunTrace("")
    assertEquals(failInsights.result, 0)
    assertEquals(failInsights.errors.size, 2)
    assert(failInsights.hasErrors)
  }

  test("nodes can access current execution state") {
    val upstream = Transform[String, Int] { input =>
      Trace.error("Upstream error")
      input.length
    }

    val downstream = Transform[Int, Int] { value =>
      if (Trace.hasErrors) {
        Trace.log("Using fallback due to errors")
        -999
      } else {
        value * 2
      }
    }

    val pipeline = upstream ~> downstream
    val insights = pipeline.unsafeRunTrace("test")

    assertEquals(insights.result, -999)
    assertEquals(insights.errors, List("Upstream error"))
    assertEquals(insights.logs, List("Using fallback due to errors"))
  }

  test("any-type logging and validation") {
    case class LogEvent(message: String, timestamp: Long)
    case class ValidationError(field: String, code: Int)

    val node = Transform[String, Int] { input =>
      Trace.log(LogEvent("Started processing", System.currentTimeMillis()))
      val result = input.length
      if (result <= 0) Trace.error(ValidationError("length", 404))
      result
    }

    val insights = node.unsafeRunTrace("test")
    assertEquals(insights.result, 4)
    assert(insights.logs.head.isInstanceOf[LogEvent])
    assertEquals(insights.errors, List.empty)
  }

  test("unified access to current execution insights") {
    val node = Transform[String, String] { input =>
      Trace.log("Step 1")
      val current = Trace.current
      assertEquals(current.logs, List("Step 1"))
      assert(current.timeElapsedMillis >= 0)

      input.toUpperCase
    }

    val insights = node.unsafeRunTrace("hello")
    assertEquals(insights.result, "HELLO")
    assertEquals(insights.logs, List("Step 1"))
    assert(insights.timeElapsedMillis >= 0)
  }

  test("unsafeRun collects trace internally") {
    val node = Transform[String, String] { input =>
      Trace.log("Processing in unsafeRun")
      if (input.isEmpty) Trace.error("Empty input")
      input.toUpperCase
    }

    // Regular run should work and trace should be available during execution
    val result = node.unsafeRun("hello")
    assertEquals(result, "HELLO")

    // Verify trace was accessible during execution by testing a node that uses trace info
    val reactivePipeline = Transform[String, String] { input =>
      Trace.log("Starting processing")
      if (input.isEmpty) Trace.error("Empty input")
      input.toUpperCase
    } ~> Transform[String, String] { input =>
      // This node reacts to trace state
      if (Trace.hasErrors) "ERROR_DETECTED" else input + "!"
    }

    assertEquals(reactivePipeline.unsafeRun("hello"), "HELLO!")
    assertEquals(reactivePipeline.unsafeRun(""), "ERROR_DETECTED")
  }

  test("safeRun collects trace internally") {
    val node = Transform[String, String] { input =>
      Trace.log("Processing in safeRun")
      if (input.isEmpty) Trace.error("Empty input")
      input.toUpperCase
    }

    // Regular safe run should work and trace should be available during execution
    val result = node.safeRun("hello")
    assert(result.isSuccess)
    assertEquals(result.get, "HELLO")

    // Verify trace was accessible during execution with reactive pipeline
    val reactivePipeline = Transform[String, String] { input =>
      Trace.log("Starting processing")
      if (input.isEmpty) Trace.error("Empty input")
      input.toUpperCase
    } ~> Transform[String, String] { input =>
      // This node reacts to trace state
      if (Trace.hasErrors) "ERROR_DETECTED" else input + "!"
    }

    val successResult = reactivePipeline.safeRun("hello")
    assert(successResult.isSuccess)
    assertEquals(successResult.get, "HELLO!")

    val errorResult = reactivePipeline.safeRun("")
    assert(errorResult.isSuccess)
    assertEquals(errorResult.get, "ERROR_DETECTED")
  }

}

class TelSpecs extends munit.FunSuite {

  test("Tel calls are no-ops without provider") {
    val node = Transform[String, String] { input =>
      Tel.span("test-span") {
        Trace.log("Processing")
        Tel.counter("test.counter", 1)
        Tel.gauge("test.gauge", 42.0)
        Tel.histogram("test.histogram", 100.0)
        input.toUpperCase
      }
    }

    // Should work fine without any Tel provider
    val result = node.unsafeRun("hello")
    assertEquals(result, "HELLO")
  }

  test("Etl4sConsoleTelemetry prints telemetry") {
    implicit val otel: Etl4sTelemetry = Etl4sConsoleTelemetry("[TEST]")

    val node = Transform[String, String] { input =>
      Tel.span("string-processing") {
        Tel.counter("strings.processed", 1)
        Tel.gauge("string.length", input.length.toDouble)
        Tel.histogram("processing.time", 50.0)
        input.toUpperCase
      }
    }

    val result = node.unsafeRun("hello")
    assertEquals(result, "HELLO")
  }

  test("nested spans") {
    implicit val otel: Etl4sTelemetry = Etl4sConsoleTelemetry()

    val node = Transform[String, Int] { input =>
      Tel.span("outer") {
        Tel.counter("outer.ops", 1)

        val length = Tel.span("inner") {
          Tel.counter("inner.ops", 1)
          input.length
        }

        Tel.histogram("result.value", length.toDouble)
        length
      }
    }

    val result = node.unsafeRun("test")
    assertEquals(result, 4)
  }

  test("Tel integrates with Trace system") {
    implicit val otel: Etl4sTelemetry = Etl4sConsoleTelemetry("[INTEGRATION]")

    val pipeline = Transform[String, String] { input =>
      Tel.span("validation") {
        if (input.isEmpty) {
          Trace.error("Empty input detected")
          Tel.counter("validation.errors", 1)
        } else {
          Trace.log("Input validated successfully")
          Tel.counter("validation.success", 1)
        }
        input
      }
    } ~> Transform[String, String] { input =>
      Tel.span("processing") {
        if (Trace.hasErrors) {
          Tel.counter("processing.skipped", 1)
          "FALLBACK"
        } else {
          Tel.counter("processing.completed", 1)
          Tel.histogram("input.length", input.length.toDouble)
          input.toUpperCase
        }
      }
    }

    assertEquals(pipeline.unsafeRun("hello"), "HELLO")
    assertEquals(pipeline.unsafeRun(""), "FALLBACK")
  }

  test("Custom Etl4sTelemetry can be implemented") {
    // In-memory provider for testing
    class TestEtl4sTelemetry extends Etl4sTelemetry {
      var spans: List[(String, Long)]        = List.empty
      var counters: Map[String, Long]        = Map.empty
      var gauges: Map[String, Double]        = Map.empty
      var histograms: List[(String, Double)] = List.empty

      def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
        val start    = System.currentTimeMillis()
        val result   = block
        val duration = System.currentTimeMillis() - start
        spans = (name, duration) :: spans
        result
      }

      def addCounter(name: String, value: Long): Unit = {
        counters = counters.updated(name, counters.getOrElse(name, 0L) + value)
      }

      def setGauge(name: String, value: Double): Unit = {
        gauges = gauges.updated(name, value)
      }

      def recordHistogram(name: String, value: Double): Unit = {
        histograms = (name, value) :: histograms
      }
    }

    val testProvider                  = new TestEtl4sTelemetry()
    implicit val otel: Etl4sTelemetry = testProvider

    val node = Transform[List[String], Int] { strings =>
      Tel.span("list-processing") {
        Tel.counter("items.received", strings.size.toLong)
        Tel.gauge("batch.size", strings.size.toDouble)

        val totalLength = strings.map(_.length).sum
        Tel.histogram("total.length", totalLength.toDouble)
        totalLength
      }
    }

    val result = node.unsafeRun(List("hello", "world"))
    assertEquals(result, 10)

    // Verify telemetry was collected
    assertEquals(testProvider.spans.size, 1)
    assertEquals(testProvider.spans.head._1, "list-processing")
    assertEquals(testProvider.counters("items.received"), 2L)
    assertEquals(testProvider.gauges("batch.size"), 2.0)
    assertEquals(testProvider.histograms.head, ("total.length", 10.0))
  }

  test("span attributes are no-ops without provider") {
    val node = Transform[String, String] { input =>
      Tel.span("processing", "input.length" -> input.length, "type" -> "uppercase") {
        Tel.addEvent("started")
        val result = input.toUpperCase
        Tel.addEvent("completed")
        result
      }
    }

    val result = node.unsafeRun("hello world")
    assertEquals(result, "HELLO WORLD")
  }

  test("span attributes work with console provider") {
    implicit val otel: Etl4sTelemetry = Etl4sConsoleTelemetry()

    val node = Transform[String, String] { input =>
      Tel.span("processing", "input.length" -> input.length, "type" -> "uppercase") {
        Tel.addEvent("started")
        val result = input.toUpperCase
        Tel.addEvent("completed", "output.length" -> result.length)
        result
      }
    }

    val result = node.unsafeRun("hello world")
    assertEquals(result, "HELLO WORLD")
  }

  test("Real OpenTelemetry SDK integration example") {
    // Demonstrates how to integrate with actual Tel SDK

    import io.opentelemetry.api.OpenTelemetry
    import io.opentelemetry.api.common.{Attributes, AttributeKey}
    import io.opentelemetry.api.metrics.Meter
    import io.opentelemetry.api.trace.{Tracer, StatusCode}
    import io.opentelemetry.sdk.OpenTelemetrySdk
    import io.opentelemetry.sdk.metrics.SdkMeterProvider
    import io.opentelemetry.sdk.trace.SdkTracerProvider
    import io.opentelemetry.exporter.logging.{LoggingSpanExporter, LoggingMetricExporter}
    import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
    import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
    import java.time.Duration

    class RealEtl4sTelemetry extends Etl4sTelemetry {
      private val sdk: OpenTelemetry = OpenTelemetrySdk
        .builder()
        .setTracerProvider(
          SdkTracerProvider
            .builder()
            .addSpanProcessor(
              BatchSpanProcessor.builder(LoggingSpanExporter.create()).build()
            )
            .build()
        )
        .setMeterProvider(
          SdkMeterProvider
            .builder()
            .registerMetricReader(
              PeriodicMetricReader
                .builder(LoggingMetricExporter.create())
                .setInterval(Duration.ofSeconds(1))
                .build()
            )
            .build()
        )
        .build()

      private val tracer: Tracer = sdk.getTracer("etl4s-test")
      private val meter: Meter   = sdk.getMeter("etl4s-test")

      def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
        val spanBuilder = tracer
          .spanBuilder(name)
          .setAttribute("service.name", "etl4s-test")
          .setAttribute("service.version", "1.0.0")

        // Add user-provided attributes
        attributes.foreach { case (key, value) =>
          value match {
            case s: String  => spanBuilder.setAttribute(key, s)
            case l: Long    => spanBuilder.setAttribute(key, l)
            case i: Int     => spanBuilder.setAttribute(key, i.toLong)
            case d: Double  => spanBuilder.setAttribute(key, d)
            case b: Boolean => spanBuilder.setAttribute(key, b)
            case other      => spanBuilder.setAttribute(key, other.toString)
          }
        }

        val span = spanBuilder.startSpan()

        try {
          span.addEvent("span.started")
          val result = block
          span.setStatus(StatusCode.OK)
          result
        } catch {
          case e: Throwable =>
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.getMessage)
            throw e
        } finally {
          span.end()
        }
      }

      def addCounter(name: String, value: Long): Unit = {
        val otelCounter = meter
          .counterBuilder(name)
          .setDescription(s"Counter for $name")
          .build()

        otelCounter.add(
          value,
          Attributes.of(
            AttributeKey.stringKey("component"),
            "etl4s"
          )
        )
      }

      def setGauge(name: String, value: Double): Unit = {
        // TODO implement as callbacks
        val otelGauge = meter
          .upDownCounterBuilder(name)
          .setDescription(s"Gauge for $name")
          .build()

        otelGauge.add(
          value.toLong,
          Attributes.of(
            AttributeKey.stringKey("component"),
            "etl4s"
          )
        )
      }

      def recordHistogram(name: String, value: Double): Unit = {
        val otelHistogram = meter
          .histogramBuilder(name)
          .setDescription(s"Histogram for $name")
          .setUnit("ms")
          .build()

        otelHistogram.record(
          value,
          Attributes.of(
            AttributeKey.stringKey("component"),
            "etl4s"
          )
        )
      }
    }

    // Usage example with real Tel SDK:
    implicit val otel: Etl4sTelemetry = new RealEtl4sTelemetry()

    val pipeline = Extract[String, List[String]](_.split(",").toList) ~>
      Transform[List[String], List[String]] { items =>
        Tel.span("data-transformation") {
          Trace.log(s"Transforming ${items.size} items")
          Tel.counter("items.received", items.size.toLong)
          val transformed = items.map(_.trim.toUpperCase)
          Tel.histogram("transformation.time", 25.0)
          transformed
        }
      } ~>
      Load[List[String], Int] { items =>
        Tel.span("data-persistence") {
          Trace.log(s"Persisting ${items.size} items")
          Tel.counter("items.saved", items.size.toLong)
          Tel.histogram("batch.processing.time", 150.0)
          Tel.gauge("current.batch.size", items.size.toDouble)
          items.size
        }
      }

    val result = pipeline.unsafeRun("hello,world,test")
    assertEquals(result, 3)

    Thread.sleep(100)
  }

  test("counter accumulation") {
    class TestEtl4sTelemetry extends Etl4sTelemetry {
      var spans: List[(String, Long)]        = List.empty
      var counters: Map[String, Long]        = Map.empty
      var gauges: Map[String, Double]        = Map.empty
      var histograms: List[(String, Double)] = List.empty

      def withSpan[T](name: String, attributes: (String, Any)*)(block: => T): T = {
        val start    = System.currentTimeMillis()
        val result   = block
        val duration = System.currentTimeMillis() - start
        spans = (name, duration) :: spans
        result
      }

      def addCounter(name: String, value: Long): Unit = {
        counters = counters.updated(name, counters.getOrElse(name, 0L) + value)
      }

      def setGauge(name: String, value: Double): Unit = {
        gauges = gauges.updated(name, value)
      }

      def recordHistogram(name: String, value: Double): Unit = {
        histograms = (name, value) :: histograms
      }
    }

    val testProvider                  = new TestEtl4sTelemetry()
    implicit val otel: Etl4sTelemetry = testProvider

    val node = Transform[List[String], Int] { strings =>
      Tel.span("processing") {
        Tel.counter("items.processed", 5L)
        Tel.counter("items.processed", 3L)
        Tel.counter("items.processed", 2L)

        Tel.counter("batches.completed", 1L)
        Tel.counter("batches.completed", 1L)

        strings.size
      }
    }

    val result = node.unsafeRun(List("a", "b", "c"))
    assertEquals(result, 3)

    assertEquals(testProvider.counters("items.processed"), 10L)
    assertEquals(testProvider.counters("batches.completed"), 2L)
    assertEquals(testProvider.spans.size, 1)
  }

}
