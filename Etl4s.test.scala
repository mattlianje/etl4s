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
        Transform[String, Int](_.length)
          .tap(n => intercepted = s"Length is $n") ~>
        Transform[Int, String](n => n.toString)

    val result = pipeline.unsafeRun(())

    assertEquals(result, "5")
    assertEquals(intercepted, "Length is 5")
  }

  test("sequential operations with >>") {
    var sequence = List.empty[String]

    val step1 = Node[Int, Unit] { n =>
      sequence = sequence :+ s"step1:$n"
    }

    val step2 = Node[Int, String] { n =>
      sequence = sequence :+ s"step2:$n"
      "done"
    }

    val pipeline = step1 >> step2

    val result = pipeline.unsafeRun(42)
    assertEquals(result, "done")
    assertEquals(sequence, List("step1:42", "step2:42"))
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

    val pipeline: Pipeline[Any, String] =
      (
        (s1 & s2) ~> D &
          (s3 ~> E)
      ) ~> F ~>
        (w1 & w2 & w3).zip ~> R

    val result = pipeline.unsafeRun()
    assert(result == "[FOO + num:10 | double:2.50 processed]")
  }

  test("can zip max nodes") {
    /* Max is currently 10 */
    val e1   = Extract(1)
    val bigE = (e1 & e1 & e1 & e1 & e1 & e1 & e1 & e1 & e1 & e1).zip
  }

  test("can create and chain effect") {
    val e           = Node { println("yo"); println("dawg") }
    val effectChain = e >> e >> e >> e >> e >> e
    effectChain.unsafeRun()
  }

  test("tap preserves data flow while performing side effects") {
    var processedData: Option[String] = None

    val getData   = Extract((unit: Unit) => "test data")
    val transform = Transform[String, Int](_.length)

    val pipeline = getData.tap { data =>
      processedData = Some(data)
      println(s"Processing: $data")
    } ~> transform

    val result = pipeline.unsafeRun(())

    assertEquals(result, 9)
    assertEquals(processedData, Some("test data"))
  }

  test("tap can be used for logging in pipeline composition") {
    var tempFilesCleaned = false

    val fetchData    = Extract((unit: Unit) => List("file1.txt", "file2.txt"))
    val processFiles = Transform[List[String], Int](_.size)

    val p = fetchData.tap { files =>
      tempFilesCleaned = true
      println(s"Cleaning up ${files.size} temporary files...")
    } ~> processFiles

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
    val reader = Reader[String, String](ctx => s"$ctx-data").withMetadata("source-info")
    assertEquals(reader.metadata, "source-info")
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
    val r1   = Reader[Int, Transform[Int, Int]] { ctx => Transform(_ * 2) }
    val t1   = Transform[Int, Int](_ * 2)
    val tAny = Transform[Any, Unit](_ => ())
    val anyR = Reader[Int, Transform[Any, Unit]] { ctx => Transform(_ => ()) }

    val test1 = r1 & r1 & r1 & t1
    val test2 = r1 &> r1 &> r1 &> t1
    val test3 = r1 >> r1
    val test4 = anyR >> anyR
    val test5 = t1 & r1
    val test6 = tAny >> anyR
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

  test("Reader metadata works") {
    val reader = Reader[String, String](ctx => s"$ctx-result").withMetadata("reader-info")
    assertEquals(reader.metadata, "reader-info")
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
      Tel.withSpan("test-span") {
        Trace.log("Processing")
        Tel.addCounter("test.counter", 1)
        Tel.setGauge("test.gauge", 42.0)
        Tel.recordHistogram("test.histogram", 100.0)
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
      Tel.withSpan("string-processing") {
        Tel.addCounter("strings.processed", 1)
        Tel.setGauge("string.length", input.length.toDouble)
        Tel.recordHistogram("processing.time", 50.0)
        input.toUpperCase
      }
    }

    val result = node.unsafeRun("hello")
    assertEquals(result, "HELLO")
  }

  test("nested spans") {
    implicit val otel: Etl4sTelemetry = Etl4sConsoleTelemetry()

    val node = Transform[String, Int] { input =>
      Tel.withSpan("outer") {
        Tel.addCounter("outer.ops", 1)

        val length = Tel.withSpan("inner") {
          Tel.addCounter("inner.ops", 1)
          input.length
        }

        Tel.recordHistogram("result.value", length.toDouble)
        length
      }
    }

    val result = node.unsafeRun("test")
    assertEquals(result, 4)
  }

  test("Tel integrates with Trace system") {
    implicit val otel: Etl4sTelemetry = Etl4sConsoleTelemetry("[INTEGRATION]")

    val pipeline = Transform[String, String] { input =>
      Tel.withSpan("validation") {
        if (input.isEmpty) {
          Trace.error("Empty input detected")
          Tel.addCounter("validation.errors", 1)
        } else {
          Trace.log("Input validated successfully")
          Tel.addCounter("validation.success", 1)
        }
        input
      }
    } ~> Transform[String, String] { input =>
      Tel.withSpan("processing") {
        if (Trace.hasErrors) {
          Tel.addCounter("processing.skipped", 1)
          "FALLBACK"
        } else {
          Tel.addCounter("processing.completed", 1)
          Tel.recordHistogram("input.length", input.length.toDouble)
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
      Tel.withSpan("list-processing") {
        Tel.addCounter("items.received", strings.size.toLong)
        Tel.setGauge("batch.size", strings.size.toDouble)

        val totalLength = strings.map(_.length).sum
        Tel.recordHistogram("total.length", totalLength.toDouble)
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
      Tel.withSpan("processing", "input.length" -> input.length, "type" -> "uppercase") {
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
      Tel.withSpan("processing", "input.length" -> input.length, "type" -> "uppercase") {
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
        Tel.withSpan("data-transformation") {
          Trace.log(s"Transforming ${items.size} items")
          Tel.addCounter("items.received", items.size.toLong)
          val transformed = items.map(_.trim.toUpperCase)
          Tel.recordHistogram("transformation.time", 25.0)
          transformed
        }
      } ~>
      Load[List[String], Int] { items =>
        Tel.withSpan("data-persistence") {
          Trace.log(s"Persisting ${items.size} items")
          Tel.addCounter("items.saved", items.size.toLong)
          Tel.recordHistogram("batch.processing.time", 150.0)
          Tel.setGauge("current.batch.size", items.size.toDouble)
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
      Tel.withSpan("processing") {
        Tel.addCounter("items.processed", 5L)
        Tel.addCounter("items.processed", 3L)
        Tel.addCounter("items.processed", 2L)

        Tel.addCounter("batches.completed", 1L)
        Tel.addCounter("batches.completed", 1L)

        strings.size
      }
    }

    val result = node.unsafeRun(List("a", "b", "c"))
    assertEquals(result, 3)

    assertEquals(testProvider.counters("items.processed"), 10L)
    assertEquals(testProvider.counters("batches.completed"), 2L)
    assertEquals(testProvider.spans.size, 1)
  }

  test("lineage - attach to Node") {
    val node = Node[String, Int](_.length)
      .lineage("string-length", inputs = List("text_input"), outputs = List("length_output"))

    assertEquals(node.unsafeRun("hello"), 5)
    assert(node.getLineage.exists(_.name == "string-length"))
  }

  test("lineage - schedule and cluster") {
    val node = Node[Int, Int](_ * 2)
      .lineage(
        "doubler",
        inputs = List("numbers"),
        outputs = List("doubled"),
        schedule = "Every 5 min",
        cluster = "math"
      )

    val lin = node.getLineage.get
    assertEquals(lin.schedule, "Every 5 min")
    assertEquals(lin.cluster, "math")
  }

  test("lineage - Reader") {
    case class Config(mult: Int)
    val reader = Reader[Config, Node[Int, Int]](c => Node(_ * c.mult))
      .lineage("mult", inputs = List("nums"), outputs = List("scaled"), cluster = "cfg")

    assertEquals(reader.getLineage.get.name, "mult")
    assertEquals(reader.provide(Config(3)).unsafeRun(10), 30)
  }

  test("lineage - toJson") {
    val p = Node[String, String](identity)
      .lineage(
        "pipe",
        inputs = List("in"),
        outputs = List("out"),
        schedule = "Daily",
        cluster = "test"
      )

    val json = Seq(p).toJson
    assert(json.contains("\"name\":\"pipe\""))
    assert(json.contains("\"schedule\":\"Daily\""))
  }

  test("lineage - auto-infer upstream") {
    val p1 =
      Node[String, String](identity).lineage("s1", inputs = List("raw"), outputs = List("proc"))
    val p2 =
      Node[String, String](identity).lineage("s2", inputs = List("proc"), outputs = List("final"))

    val json = Seq(p1, p2).toJson
    assert(json.contains("\"isDependency\":true"))
  }

  test("lineage - toDot") {
    val p = Node[String, String](identity)
      .lineage(
        "enrich",
        inputs = List("raw"),
        outputs = List("clean"),
        schedule = "2h",
        cluster = "users"
      )

    val dot = Seq(p).toDot
    assert(dot.contains("digraph G"))
    assert(dot.contains("enrich"))
    assert(dot.contains("cluster_users"))
  }

  test("lineage - mixed with/without") {
    val w =
      Node[String, String](_.toUpperCase).lineage("up", inputs = List("t"), outputs = List("T"))
    val wo = Node[String, Int](_.length)

    val json = Seq(w, wo).toJson
    assert(json.contains("up"))
  }

  test("lineage - empty") {
    assertEquals(
      Seq.empty[Node[String, String]].toJson,
      """{"pipelines":[],"dataSources":[],"edges":[]}"""
    )
    assert(Seq.empty[Node[String, String]].toDot.contains("No lineage"))
  }

  test("lineage - preserves behavior") {
    val n1 = Node[Int, Int](_ * 2).lineage("x2", inputs = List("in"), outputs = List("out"))
    val n2 = Node[Int, Int](_ + 10).lineage("+10", inputs = List("in2"), outputs = List("out2"))
    assertEquals((n1 ~> n2).unsafeRun(5), 20)
  }

  test("lineage - explicit upstreams") {
    val p1 = Node[String, String](identity).lineage("u1", inputs = List("a"), outputs = List("b"))
    val p2 = Node[String, String](identity).lineage("u2", inputs = List("c"), outputs = List("d"))
    val p3 = Node[String, String](identity).lineage(
      "agg",
      inputs = List("x"),
      outputs = List("y"),
      upstreams = List(p1, p2)
    )

    assertEquals(p3.getLineage.get.upstreams.size, 2)
    assert(Seq(p1, p2, p3).toJson.contains("\"isDependency\":true"))
  }

  test("lineage - upstream strings") {
    val p = Node[String, String](identity)
      .lineage(
        "report",
        inputs = List("agg"),
        outputs = List("out"),
        upstreams = List("analytics", "validation")
      )

    assertEquals(p.getLineage.get.upstreams, List("analytics", "validation"))
    assert(Seq(p).toJson.contains("\"analytics\""))
  }

  test("lineage - upstream Reader") {
    val up =
      Node[String, String](identity).lineage("up", inputs = List("in"), outputs = List("out"))
    val r = Reader[String, Node[String, String]](_ => Node(x => x))
      .lineage("r", inputs = List("in"), outputs = List("out"), upstreams = List(up))

    assertEquals(r.getLineage.get.upstreams.size, 1)
  }

  test("lineage - toMermaid") {
    val p1 = Node[String, String](identity)
      .lineage(
        "enrich",
        inputs = List("raw"),
        outputs = List("clean"),
        schedule = "2h",
        cluster = "users"
      )
    val p2 = Node[String, String](identity)
      .lineage("proc", inputs = List("orders"), outputs = List("done"), upstreams = List(p1))

    val m = Seq(p1, p2).toMermaid
    assert(m.contains("graph LR"))
    assert(m.contains("enrich<br/>(2h)"))
    assert(m.contains("subgraph users"))
    assert(m.contains("-.->"))
  }

  test("lineage - single item render") {
    val n = Node[String, String](identity).lineage("n", inputs = List("i"), outputs = List("o"))
    val r = Reader[String, String](identity).lineage("r", inputs = List("i2"), outputs = List("o2"))

    assert(n.toJson.contains("n"))
    assert(n.toDot.contains("n"))
    assert(r.toMermaid.contains("r"))
  }

}

class ValidationSpecs extends munit.FunSuite {

  test("ensure validates input and passes") {
    val node = Node[Int, String](n => s"Value: $n")
      .ensure(input = List(x => if (x > 0) None else Some("positive")))
    assertEquals(node.unsafeRun(5), "Value: 5")
  }

  test("ensure collects multiple input errors") {
    val isPositive = (x: Int) => if (x > 0) None else Some("positive")
    val isEven     = (x: Int) => if (x % 2 == 0) None else Some("even")
    val node = Node[Int, String](n => s"Value: $n")
      .ensure(input = isPositive :: isEven :: Nil)
    val ex = intercept[ValidationException](node.unsafeRun(-5))
    assert(ex.getMessage.contains("positive") && ex.getMessage.contains("even"))
  }

  test("ensure validates output") {
    val node = Node[String, Int](_.length)
      .ensure(output = List(n => if (n > 0) None else Some("non-zero")))
    assertEquals(node.unsafeRun("hello"), 5)
    intercept[ValidationException](node.unsafeRun(""))
  }

  test("ensure validates transformation") {
    val node = Node[List[Int], List[Int]](_.map(_ * 2))
      .ensure(
        change = List { case (in, out) =>
          if (in.size == out.size) None else Some("size")
        }
      )
    assertEquals(node.unsafeRun(List(1, 2, 3)), List(2, 4, 6))
  }

  test("ensure detects change violations") {
    val node = Node[List[Int], List[Int]](_.filter(_ > 5))
      .ensure(
        change = List { case (in, out) =>
          if (in.size == out.size) None else Some("size")
        }
      )
    intercept[ValidationException](node.unsafeRun(List(1, 2, 3)))
  }

  test("validation errors logged to Trace") {
    val node = Node[Int, String](n => s"$n")
      .ensure(input = List(x => if (x > 0) None else Some("err")))
    val trace = node.safeRunTrace(-5)
    assert(trace.result.isFailure && trace.errors.nonEmpty)
  }

  test("Reader ensure with context") {
    case class Config(min: Int, max: Int)
    val checkMin = (cfg: Config) => (x: Int) => if (x >= cfg.min) None else Some("min")
    val checkMax = (cfg: Config) => (x: Int) => if (x <= cfg.max) None else Some("max")
    val node = Reader[Config, Node[Int, Int]] { cfg => Node((x: Int) => x) }
      .ensure(input = Seq(checkMin, checkMax))
    val config = Config(10, 100)
    assertEquals(node.provide(config).unsafeRun(50), 50)
    intercept[ValidationException](node.provide(config).unsafeRun(5))
    intercept[ValidationException](node.provide(config).unsafeRun(150))
  }

  test("Reader ensure output with context") {
    case class Config(allowed: Set[String])
    case class User(name: String, domain: String)
    val node = Reader[Config, Node[String, User]] { cfg =>
      Node { s =>
        val p = s.split("@"); User(p(0), p(1))
      }
    }.ensure(
      output = List((cfg: Config) =>
        (u: User) => if (cfg.allowed.contains(u.domain)) None else Some("domain")
      )
    )
    val config = Config(Set("ok.com"))
    assertEquals(node.provide(config).unsafeRun("x@ok.com").name, "x")
    intercept[ValidationException](node.provide(config).unsafeRun("x@bad.com"))
  }

  test("Reader ensure change with context") {
    case class Config(preserveSize: Boolean)
    val node = Reader[Config, Node[List[Int], List[Int]]] { cfg =>
      Node(_.distinct)
    }.ensure(
      change = Seq((cfg: Config) =>
        (pair: (List[Int], List[Int])) =>
          pair match {
            case (in, out) => if (!cfg.preserveSize || in.size == out.size) None else Some("size")
          }
      )
    )
    assertEquals(node.provide(Config(false)).unsafeRun(List(1, 1, 2)), List(1, 2))
    intercept[ValidationException](node.provide(Config(true)).unsafeRun(List(1, 1, 2)))
  }

  test("curried validators are reusable") {
    case class Config(max: Int)
    case class User(age: Int)
    val checkAge = (cfg: Config) => (u: User) => if (u.age <= cfg.max) None else Some("age")

    val node = Reader[Config, Node[User, User]] { _ => Node((u: User) => u) }
      .ensure(output = Seq(checkAge))

    assertEquals(node.provide(Config(100)).unsafeRun(User(50)).age, 50)
    intercept[ValidationException](node.provide(Config(100)).unsafeRun(User(150)))
  }

  test("validation composes with pipeline") {
    val n1 = Node[String, Int](_.length)
      .ensure(input = Seq(s => if (s.nonEmpty) None else Some("empty")))
    val n2 = Node[Int, String](n => s"$n")
      .ensure(input = Seq(n => if (n > 0) None else Some("pos")))
    val p = n1 ~> n2
    assertEquals(p.unsafeRun("hi"), "2")
    intercept[ValidationException](p.unsafeRun(""))
  }

  test("validation composes with &") {
    val n1 =
      Node[String, Int](_.length).ensure(input = Seq(s => if (s.nonEmpty) None else Some("e")))
    val n2 = Node[String, String](_.toUpperCase)
      .ensure(input = Seq(s => if (s.nonEmpty) None else Some("e")))
    assertEquals((n1 & n2).unsafeRun("hi"), (2, "HI"))
    intercept[ValidationException]((n1 & n2).unsafeRun(""))
  }

  test("Reader ensure accepts plain-style validators") {
    case class Config(dummy: String)
    case class User(age: Int)

    val node = Reader[Config, Node[User, User]] { _ => Node((u: User) => u) }
      .ensure(
        output = Seq((_: Config) =>
          (user: User) => if (user.age > 0 && user.age < 150) None else Some("invalid age")
        )
      )

    assertEquals(node.provide(Config("x")).unsafeRun(User(50)).age, 50)
    intercept[ValidationException](node.provide(Config("x")).unsafeRun(User(200)))
  }

  test("can mix curried validators in ensure") {
    case class Config(max: Int)
    case class User(age: Int)

    val node = Reader[Config, Node[User, User]] { _ => Node((u: User) => u) }
      .ensure(
        output = Seq(
          (_: Config) => (user: User) => if (user.age > 0) None else Some("must be positive"),
          (cfg: Config) => (u: User) => if (u.age <= cfg.max) None else Some("too old")
        )
      )

    assertEquals(node.provide(Config(100)).unsafeRun(User(50)).age, 50)
    intercept[ValidationException](node.provide(Config(100)).unsafeRun(User(-5)))
    intercept[ValidationException](node.provide(Config(100)).unsafeRun(User(150)))
  }

  test("Function1 implicitly converts to Node") {
    val length: String => Int   = _.length
    val double: Int => Int      = _ * 2
    val toString: Int => String = _.toString

    val pipeline = length ~> double ~> toString
    assertEquals(pipeline.unsafeRun("hello"), "10")
  }

  test("Function1 works in pipelines with validation") {
    val length: String => Int = _.length
    val double: Int => Int    = _ * 2

    val pipeline =
      length ~> Node(double).ensure(input = Seq(x => if (x > 0) None else Some("positive")))
    assertEquals(pipeline.unsafeRun("hi"), 4)
  }

  test("Function1 works with & operator") {
    val length: String => Int   = _.length
    val upper: String => String = _.toUpperCase

    val parallel = length & upper
    assertEquals(parallel.unsafeRun("hello"), (5, "HELLO"))
  }

  test("Function1 works with >> operator") {
    var sideEffect1 = ""
    var sideEffect2 = ""

    val effect1: String => Unit   = s => sideEffect1 = s"first:$s"
    val effect2: String => String = s => { sideEffect2 = s"second:$s"; s.toUpperCase }

    val pipeline = effect1 >> effect2
    assertEquals(pipeline.unsafeRun("hi"), "HI")
    assertEquals(sideEffect1, "first:hi")
    assertEquals(sideEffect2, "second:hi")
  }

  test("ensure combines multiple validation types") {
    val node = Node[Int, String](n => s"Value: $n")
      .ensure(
        input = Seq(
          x => if (x > 0) None else Some("positive"),
          x => if (x < 1000) None else Some("too large")
        ),
        output = Seq(s => if (s.nonEmpty) None else Some("empty"))
      )
    assertEquals(node.unsafeRun(5), "Value: 5")
    intercept[ValidationException](node.unsafeRun(-5))
    intercept[ValidationException](node.unsafeRun(2000))
  }

  test("ensure with change validation") {
    val node = Node[List[Int], List[Int]](_.distinct)
      .ensure(
        change = Seq(
          { case (in, out) => if (out.size <= in.size) None else Some("grew") }
        )
      )
    assertEquals(node.unsafeRun(List(1, 1, 2)), List(1, 2))
  }

  test("ensure with empty checks is no-op") {
    val node = Node[Int, String](n => s"$n")
      .ensure()
    assertEquals(node.unsafeRun(5), "5")
  }

  test("ensurePar runs checks in parallel") {
    import scala.concurrent.ExecutionContext.Implicits.global
    import java.util.concurrent.atomic.AtomicInteger
    val count = new AtomicInteger(0)

    val node = Node[Int, String](n => s"Value: $n")
      .ensurePar(
        input = Seq(
          x => { Thread.sleep(10); count.incrementAndGet(); if (x > 0) None else Some("positive") },
          x => {
            Thread.sleep(10); count.incrementAndGet(); if (x < 1000) None else Some("too large")
          }
        )
      )

    assertEquals(node.unsafeRun(5), "Value: 5")
    assertEquals(count.get(), 2)
  }

  test("ensureWarn logs warnings instead of throwing") {
    val isPositive = (x: Int) => if (x > 0) None else Some("Must be positive")
    val node       = Node[Int, String](n => s"Value: $n").ensureWarn(input = isPositive :: Nil)
    val trace      = node.safeRunTrace(-5)

    assert(trace.result.isSuccess)
    assert(trace.logs.exists(_.toString.contains("Input validation warning")))
    assert(trace.logs.exists(_.toString.contains("Must be positive")))
  }

  test("ensureParWarn runs checks in parallel and logs warnings") {
    import scala.concurrent.ExecutionContext.Implicits.global
    val isPositive  = (x: Int) => if (x > 0) None else Some("Must be positive")
    val lessThan100 = (x: Int) => if (x < 100) None else Some("Must be less than 100")
    val node =
      Node[Int, String](n => s"Value: $n").ensureParWarn(input = isPositive :: lessThan100 :: Nil)
    val trace = node.safeRunTrace(-5)

    assert(trace.result.isSuccess)
    assert(trace.logs.exists(_.toString.contains("Input validation warning")))
    assert(trace.logs.exists(_.toString.contains("Must be positive")))
  }

  test("Reader ensureWarn logs warnings with context") {
    case class Config(min: Int)
    val checkMin = (cfg: Config) => (x: Int) => if (x >= cfg.min) None else Some("too small")
    val node = Reader[Config, Node[Int, String]] { _ => Node(n => s"Value: $n") }
      .ensureWarn(input = checkMin :: Nil)
    val trace = node.provide(Config(10)).safeRunTrace(5)

    assert(trace.result.isSuccess)
    assert(trace.logs.exists(_.toString.contains("Input validation warning")))
    assert(trace.logs.exists(_.toString.contains("too small")))
  }

  test("Reader ensure combines multiple validation types") {
    case class Config(min: Int, max: Int)

    val node = Reader[Config, Node[Int, String]] { _ => Node(n => s"Value: $n") }
      .ensure(
        input = Seq(
          (cfg: Config) => (x: Int) => if (x >= cfg.min) None else Some("too small"),
          (_: Config) => (x: Int) => if (x > 0) None else Some("positive")
        ),
        output = Seq((_: Config) => (s: String) => if (s.nonEmpty) None else Some("empty"))
      )

    val config = Config(10, 100)
    assertEquals(node.provide(config).unsafeRun(50), "Value: 50")
    intercept[ValidationException](node.provide(config).unsafeRun(5))
    intercept[ValidationException](node.provide(config).unsafeRun(-5))
  }

  test("Reader ensure with change validation") {
    case class Config(preserveSize: Boolean)
    val node = Reader[Config, Node[List[Int], List[Int]]] { _ => Node(_.distinct) }
      .ensure(
        change = Seq((cfg: Config) =>
          (pair: (List[Int], List[Int])) =>
            pair match {
              case (in, out) => if (!cfg.preserveSize || in.size == out.size) None else Some("size")
            }
        )
      )

    assertEquals(node.provide(Config(false)).unsafeRun(List(1, 1, 2)), List(1, 2))
    intercept[ValidationException](node.provide(Config(true)).unsafeRun(List(1, 1, 2)))
  }

  test("Reader ensure automatically lifts plain functions") {
    case class Config(min: Int)

    val checkPositive = (x: Int) => if (x > 0) None else Some("must be positive")
    val checkNotEmpty = (s: String) => if (s.nonEmpty) None else Some("empty")

    val node = Reader[Config, Node[Int, String]] { _ => Node(_.toString) }
      .ensure(
        input = Seq(
          (cfg: Config) => (x: Int) => if (x >= cfg.min) None else Some("too small"),
          checkPositive
        ),
        output = Seq(
          checkNotEmpty
        )
      )

    val config = Config(10)
    assertEquals(node.provide(config).unsafeRun(50), "50")
    intercept[ValidationException](node.provide(config).unsafeRun(5))
    intercept[ValidationException](node.provide(config).unsafeRun(-5))
  }

}

class ConditionalBranchingSpecs extends munit.FunSuite {

  test("basic when-elseIf-else branching") {
    val toNegative = Node[Int, String](_ => "negative")
    val toZero     = Node[Int, String](_ => "zero")
    val toPositive = Node[Int, String](_ => "positive")

    val classify = Node[Int, Int](identity)
      .when(_ < 0)(toNegative)
      .elseIf(_ == 0)(toZero)
      .otherwise(toPositive)

    assertEquals(classify.unsafeRun(-5), "negative")
    assertEquals(classify.unsafeRun(0), "zero")
    assertEquals(classify.unsafeRun(10), "positive")
  }

  test("when-elseIf-otherwise branching") {
    val toShort  = Node[String, String](_ => "short")
    val toMedium = Node[String, String](_ => "medium")
    val toLong   = Node[String, String](_ => "long")

    val sizeClassifier = Node[String, String](identity)
      .when(_.length < 5)(toShort)
      .elseIf(_.length < 10)(toMedium)
      .otherwise(toLong)

    assertEquals(sizeClassifier.unsafeRun("hi"), "short")
    assertEquals(sizeClassifier.unsafeRun("hello"), "medium")
    assertEquals(sizeClassifier.unsafeRun("hello world!"), "long")
  }

  test("conditional branching with complex nodes") {
    val formatNegative =
      Node[Int, String](n => s"Negative: ${n.abs}") ~> Node[String, String](_.toUpperCase)
    val formatZero     = Node[Int, String](_ => "Zero value")
    val formatPositive = Node[Int, String](n => s"Positive: $n") ~> Node[String, String](_ + "!")

    val processNumber = Node[Int, Int](identity)
      .when(_ < 0)(formatNegative)
      .elseIf(_ == 0)(formatZero)
      .otherwise(formatPositive)

    assertEquals(processNumber.unsafeRun(-5), "NEGATIVE: 5")
    assertEquals(processNumber.unsafeRun(0), "Zero value")
    assertEquals(processNumber.unsafeRun(10), "Positive: 10!")
  }

  test("conditional branching in ETL pipeline") {
    case class User(name: String, age: Int)
    case class ProcessedUser(name: String, category: String)

    val extract = Extract[String, User] { input =>
      val parts = input.split(",")
      User(parts(0), parts(1).toInt)
    }

    val toMinor  = Transform[User, ProcessedUser](u => ProcessedUser(u.name, "minor"))
    val toAdult  = Transform[User, ProcessedUser](u => ProcessedUser(u.name, "adult"))
    val toSenior = Transform[User, ProcessedUser](u => ProcessedUser(u.name, "senior"))

    val categorize = Transform[User, User](identity)
      .when(_.age < 18)(toMinor)
      .elseIf(_.age < 65)(toAdult)
      .otherwise(toSenior)

    val pipeline = extract ~> categorize

    assertEquals(pipeline.unsafeRun("Alice,15"), ProcessedUser("Alice", "minor"))
    assertEquals(pipeline.unsafeRun("Bob,30"), ProcessedUser("Bob", "adult"))
    assertEquals(pipeline.unsafeRun("Charlie,70"), ProcessedUser("Charlie", "senior"))
  }

  test("conditional branching with multiple elseIf clauses") {
    val gradeA = Node[Int, String](_ => "A")
    val gradeB = Node[Int, String](_ => "B")
    val gradeC = Node[Int, String](_ => "C")
    val gradeD = Node[Int, String](_ => "D")
    val gradeF = Node[Int, String](_ => "F")

    val gradeClassifier = Node[Int, Int](identity)
      .when(_ >= 90)(gradeA)
      .elseIf(_ >= 80)(gradeB)
      .elseIf(_ >= 70)(gradeC)
      .elseIf(_ >= 60)(gradeD)
      .otherwise(gradeF)

    assertEquals(gradeClassifier.unsafeRun(95), "A")
    assertEquals(gradeClassifier.unsafeRun(85), "B")
    assertEquals(gradeClassifier.unsafeRun(75), "C")
    assertEquals(gradeClassifier.unsafeRun(65), "D")
    assertEquals(gradeClassifier.unsafeRun(55), "F")
  }

  test("conditional branching with side effects") {
    var logMessages = List.empty[String]

    val logger = Node[Int, Int](identity)
      .when(_ < 0)(
        Node[Int, String] { n =>
          logMessages = logMessages :+ "negative"
          s"neg:$n"
        }
      )
      .otherwise(
        Node[Int, String] { n =>
          logMessages = logMessages :+ "positive"
          s"pos:$n"
        }
      )

    logger.unsafeRun(-5)
    logger.unsafeRun(10)

    assertEquals(logMessages, List("negative", "positive"))
  }

  test("conditional branching evaluates first matching condition") {
    var evaluations = List.empty[String]

    val node = Node[Int, Int](identity)
      .when { n =>
        evaluations = evaluations :+ "cond1"
        n > 5
      }(Node[Int, String](_ => "first"))
      .elseIf { n =>
        evaluations = evaluations :+ "cond2"
        n > 3
      }(Node[Int, String](_ => "second"))
      .otherwise(Node[Int, String](_ => "third"))

    val result = node.unsafeRun(10)
    assertEquals(result, "first")
    // Only first condition should be evaluated
    assertEquals(evaluations, List("cond1"))

    evaluations = List.empty
    val result2 = node.unsafeRun(4)
    assertEquals(result2, "second")
    // First condition false, second condition true
    assertEquals(evaluations, List("cond1", "cond2"))
  }

  test("conditional branching with parallel composition") {
    val branch1 = Node[Int, Int](identity)
      .when(_ % 2 == 0)(Node[Int, String](n => s"even:$n"))
      .otherwise(Node[Int, String](n => s"odd:$n"))

    val branch2 = Node[Int, Int](identity)
      .when(_ > 0)(Node[Int, String](_ => "positive"))
      .otherwise(Node[Int, String](_ => "non-positive"))

    val combined = branch1 & branch2

    assertEquals(combined.unsafeRun(4), ("even:4", "positive"))
    assertEquals(combined.unsafeRun(-3), ("odd:-3", "non-positive"))
  }

  test("non-exhaustive conditional throws on build") {
    val partial = Node[Int, Int](identity)
      .when(_ < 0)(Node[Int, String](_ => "negative"))
      .elseIf(_ > 0)(Node[Int, String](_ => "positive"))

    // Should throw when trying to use it without else
    intercept[IllegalStateException] {
      partial.build.unsafeRun(0)
    }
  }

  test("conditional branching with tap for debugging") {
    var tappedValue: Option[Int] = None

    val pipeline = Node[Int, Int](identity)
      .tap(n => tappedValue = Some(n))
      .when(_ < 10)(Node[Int, String](n => s"small:$n"))
      .otherwise(Node[Int, String](n => s"large:$n"))

    val result = pipeline.unsafeRun(5)
    assertEquals(result, "small:5")
    assertEquals(tappedValue, Some(5))
  }

  test("conditional branching with error handling") {
    val safeProcessor = Node[String, String](identity)
      .when(_.isEmpty)(
        Node[String, String](_ => throw new RuntimeException("Empty!"))
          .onFailure(_ => "error:empty")
      )
      .otherwise(
        Node[String, String](s => s.toUpperCase)
      )

    assertEquals(safeProcessor.unsafeRun(""), "error:empty")
    assertEquals(safeProcessor.unsafeRun("hello"), "HELLO")
  }

  test("conditional branching with different output types") {
    sealed trait Result
    case class Success(value: Int)    extends Result
    case class Failure(error: String) extends Result

    val processor = Node[Int, Int](identity)
      .when(_ < 0)(Node[Int, Result](n => Failure(s"Negative: $n")))
      .otherwise(Node[Int, Result](n => Success(n * 2)))

    val result1 = processor.unsafeRun(-5)
    assert(result1.isInstanceOf[Failure])
    assertEquals(result1.asInstanceOf[Failure].error, "Negative: -5")

    val result2 = processor.unsafeRun(10)
    assert(result2.isInstanceOf[Success])
    assertEquals(result2.asInstanceOf[Success].value, 20)
  }

  test("conditional branching composition with ~>") {
    val classify = Node[Int, Int](identity)
      .when(_ < 0)(Node[Int, String](_ => "negative"))
      .elseIf(_ == 0)(Node[Int, String](_ => "zero"))
      .otherwise(Node[Int, String](_ => "positive"))

    val format = Node[String, String](s => s"Result: $s")

    val pipeline = classify ~> format

    assertEquals(pipeline.unsafeRun(-5), "Result: negative")
    assertEquals(pipeline.unsafeRun(0), "Result: zero")
    assertEquals(pipeline.unsafeRun(10), "Result: positive")
  }

  test("nested conditional branching") {
    val outerClassifier = Node[Int, Int](identity)
      .when(_ < 0)(
        Node[Int, Int](n => n.abs)
          .when(_ < 10)(Node[Int, String](n => s"small negative: $n"))
          .otherwise(Node[Int, String](n => s"large negative: $n"))
      )
      .otherwise(
        Node[Int, Int](identity)
          .when(_ < 10)(Node[Int, String](n => s"small positive: $n"))
          .otherwise(Node[Int, String](n => s"large positive: $n"))
      )

    assertEquals(outerClassifier.unsafeRun(-5), "small negative: 5")
    assertEquals(outerClassifier.unsafeRun(-15), "large negative: 15")
    assertEquals(outerClassifier.unsafeRun(5), "small positive: 5")
    assertEquals(outerClassifier.unsafeRun(15), "large positive: 15")
  }

  test("if syntax with backticks") {
    // The `if` method is also available using backticks for those who prefer it
    val classify = Node[Int, Int](identity)
      .`if`(_ < 0)(Node[Int, String](_ => "negative"))
      .elseIf(_ == 0)(Node[Int, String](_ => "zero"))
      .`else`(Node[Int, String](_ => "positive"))

    assertEquals(classify.unsafeRun(-5), "negative")
    assertEquals(classify.unsafeRun(0), "zero")
    assertEquals(classify.unsafeRun(10), "positive")
  }

  test("Reader conditional with context-aware condition") {
    case class Config(threshold: Int)

    val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatBelow = Reader[Config, Node[Int, String]] { _ => Node(n => s"below:$n") }
    val formatAbove = Reader[Config, Node[Int, String]] { _ => Node(n => s"above:$n") }

    val pipeline = source
      .when((cfg: Config) => (n: Int) => n < cfg.threshold)(formatBelow)
      .otherwise(formatAbove)

    val config = Config(10)
    assertEquals(pipeline.provide(config).unsafeRun(5), "below:5")
    assertEquals(pipeline.provide(config).unsafeRun(15), "above:15")
  }

  test("Reader conditional ignoring context") {
    case class Config(multiplier: Int)

    val source = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatNegative = Reader[Config, Node[Int, String]] { cfg =>
      Node(n => s"negative:${n * cfg.multiplier}")
    }
    val formatPositive = Reader[Config, Node[Int, String]] { cfg =>
      Node(n => s"positive:${n * cfg.multiplier}")
    }

    val pipeline = source
      .when(_ => (n: Int) => n < 0)(formatNegative)
      .otherwise(formatPositive)

    val config = Config(2)
    assertEquals(pipeline.provide(config).unsafeRun(-5), "negative:-10")
    assertEquals(pipeline.provide(config).unsafeRun(10), "positive:20")
  }

  test("Reader conditional with multiple elseIf and context") {
    case class Config(min: Int, max: Int)

    val source     = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatLow  = Reader[Config, Node[Int, String]] { _ => Node(n => s"too-low:$n") }
    val formatHigh = Reader[Config, Node[Int, String]] { _ => Node(n => s"too-high:$n") }
    val formatOk   = Reader[Config, Node[Int, String]] { _ => Node(n => s"ok:$n") }

    val classifier = source
      .when((cfg: Config) => (n: Int) => n < cfg.min)(formatLow)
      .elseIf((cfg: Config) => (n: Int) => n > cfg.max)(formatHigh)
      .otherwise(formatOk)

    val config = Config(10, 100)
    assertEquals(classifier.provide(config).unsafeRun(5), "too-low:5")
    assertEquals(classifier.provide(config).unsafeRun(50), "ok:50")
    assertEquals(classifier.provide(config).unsafeRun(150), "too-high:150")
  }

  test("Reader conditional mixing context-aware and context-ignoring conditions") {
    case class Config(threshold: Int)

    val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatZero  = Reader[Config, Node[Int, String]] { _ => Node[Int, String](_ => "zero") }
    val formatBelow = Reader[Config, Node[Int, String]] { _ => Node(n => s"below:$n") }
    val formatAbove = Reader[Config, Node[Int, String]] { _ => Node(n => s"above:$n") }

    val pipeline = source
      .when(_ => (n: Int) => n == 0)(formatZero)
      .elseIf((cfg: Config) => (n: Int) => n < cfg.threshold)(formatBelow)
      .otherwise(formatAbove)

    val config = Config(10)
    assertEquals(pipeline.provide(config).unsafeRun(0), "zero")
    assertEquals(pipeline.provide(config).unsafeRun(5), "below:5")
    assertEquals(pipeline.provide(config).unsafeRun(15), "above:15")
  }

  test("Reader conditional in ETL pipeline") {
    case class Config(adultAge: Int, seniorAge: Int)
    case class User(name: String, age: Int)
    case class ProcessedUser(name: String, category: String)

    val extract = Reader[Config, Node[String, User]] { _ =>
      Node { input =>
        val parts = input.split(",")
        User(parts(0), parts(1).toInt)
      }
    }

    val source = Reader[Config, Node[User, User]] { _ => Node[User, User](identity) }
    val toMinor = Reader[Config, Node[User, ProcessedUser]] { _ =>
      Node(u => ProcessedUser(u.name, "minor"))
    }
    val toAdult = Reader[Config, Node[User, ProcessedUser]] { _ =>
      Node(u => ProcessedUser(u.name, "adult"))
    }
    val toSenior = Reader[Config, Node[User, ProcessedUser]] { _ =>
      Node(u => ProcessedUser(u.name, "senior"))
    }

    val categorize = source
      .when((cfg: Config) => (u: User) => u.age < cfg.adultAge)(toMinor)
      .elseIf((cfg: Config) => (u: User) => u.age < cfg.seniorAge)(toAdult)
      .otherwise(toSenior)

    val pipeline = extract ~> categorize
    val config   = Config(18, 65)

    assertEquals(pipeline.provide(config).unsafeRun("Alice,15"), ProcessedUser("Alice", "minor"))
    assertEquals(pipeline.provide(config).unsafeRun("Bob,30"), ProcessedUser("Bob", "adult"))
    assertEquals(
      pipeline.provide(config).unsafeRun("Charlie,70"),
      ProcessedUser("Charlie", "senior")
    )
  }

  test("Reader conditional with nested branching") {
    case class Config(threshold: Int)

    val outer = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
      .when(_ => (n: Int) => n < 0)(
        Reader[Config, Node[Int, Int]] { _ => Node(n => n.abs) }
          .when((cfg: Config) => (n: Int) => n < cfg.threshold)(
            Reader[Config, Node[Int, String]] { _ => Node(n => s"small-neg:$n") }
          )
          .otherwise(
            Reader[Config, Node[Int, String]] { _ => Node(n => s"large-neg:$n") }
          )
      )
      .otherwise(
        Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
          .when((cfg: Config) => (n: Int) => n < cfg.threshold)(
            Reader[Config, Node[Int, String]] { _ => Node(n => s"small-pos:$n") }
          )
          .otherwise(
            Reader[Config, Node[Int, String]] { _ => Node(n => s"large-pos:$n") }
          )
      )

    val config = Config(10)
    assertEquals(outer.provide(config).unsafeRun(-5), "small-neg:5")
    assertEquals(outer.provide(config).unsafeRun(-15), "large-neg:15")
    assertEquals(outer.provide(config).unsafeRun(5), "small-pos:5")
    assertEquals(outer.provide(config).unsafeRun(15), "large-pos:15")
  }

  test("Reader conditional with parallel composition") {
    case class Config(threshold: Int)

    val source1 = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val toEven  = Reader[Config, Node[Int, String]] { _ => Node(n => s"even:$n") }
    val toOdd   = Reader[Config, Node[Int, String]] { _ => Node(n => s"odd:$n") }

    val branch1: Reader[Config, Node[Int, String]] = source1
      .when(_ => (n: Int) => n % 2 == 0)(toEven)
      .otherwise(toOdd)

    val source2 = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val toBelow = Reader[Config, Node[Int, String]] { _ => Node[Int, String](_ => "below") }
    val toAbove = Reader[Config, Node[Int, String]] { _ => Node[Int, String](_ => "above") }

    val branch2: Reader[Config, Node[Int, String]] = source2
      .when((cfg: Config) => (n: Int) => n < cfg.threshold)(toBelow)
      .otherwise(toAbove)

    val combined = branch1 & branch2
    val config   = Config(10)

    assertEquals(combined.provide(config).unsafeRun(4), ("even:4", "below"))
    assertEquals(combined.provide(config).unsafeRun(15), ("odd:15", "above"))
  }

  test("Reader conditional with plain Node branches") {
    case class Config(threshold: Int)

    val source     = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val toNegative = Node[Int, String](n => s"negative:$n")
    val toZero     = Node[Int, String](_ => "zero")
    val toPositive = Node[Int, String](n => s"positive:$n")

    val pipeline = source
      .when(_ => (n: Int) => n < 0)(toNegative)
      .elseIf(_ => (n: Int) => n == 0)(toZero)
      .otherwise(toPositive)

    val config = Config(10)
    assertEquals(pipeline.provide(config).unsafeRun(-5), "negative:-5")
    assertEquals(pipeline.provide(config).unsafeRun(0), "zero")
    assertEquals(pipeline.provide(config).unsafeRun(10), "positive:10")
  }

  test("Reader conditional mixing plain Node and Reader branches") {
    case class Config(multiplier: Int)

    val source     = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val toNegative = Node[Int, String](n => s"negative:$n")
    val toZero     = Reader[Config, Node[Int, String]] { _ => Node[Int, String](_ => "zero") }
    val toPositive = Reader[Config, Node[Int, String]] { cfg =>
      Node(n => s"positive:${n * cfg.multiplier}")
    }

    val pipeline = source
      .when(_ => (n: Int) => n < 0)(toNegative)
      .elseIf(_ => (n: Int) => n == 0)(toZero)
      .otherwise(toPositive)

    val config = Config(2)
    assertEquals(pipeline.provide(config).unsafeRun(-5), "negative:-5")
    assertEquals(pipeline.provide(config).unsafeRun(0), "zero")
    assertEquals(pipeline.provide(config).unsafeRun(10), "positive:20")
  }

  test("Reader conditional with plain Node branches and context-aware conditions") {
    case class Config(threshold: Int)

    val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatBelow = Node[Int, String](n => s"below:$n")
    val formatAbove = Node[Int, String](n => s"above:$n")

    val pipeline = source
      .when((cfg: Config) => (n: Int) => n < cfg.threshold)(formatBelow)
      .otherwise(formatAbove)

    val config = Config(10)
    assertEquals(pipeline.provide(config).unsafeRun(5), "below:5")
    assertEquals(pipeline.provide(config).unsafeRun(15), "above:15")
  }

  test("Reader conditional with nested plain Node branches") {
    case class Config(threshold: Int)

    val source = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }

    val toAbs      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](n => n.abs) }
    val toSmallNeg = Node[Int, String](n => s"small-neg:$n")
    val toLargeNeg = Node[Int, String](n => s"large-neg:$n")

    val negBranch = toAbs
      .when((cfg: Config) => (n: Int) => n < cfg.threshold)(toSmallNeg)
      .otherwise(toLargeNeg)

    val toIdentity = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val toSmallPos = Node[Int, String](n => s"small-pos:$n")
    val toLargePos = Node[Int, String](n => s"large-pos:$n")

    val posBranch = toIdentity
      .when((cfg: Config) => (n: Int) => n < cfg.threshold)(toSmallPos)
      .otherwise(toLargePos)

    val outer = source
      .when(_ => (n: Int) => n < 0)(negBranch)
      .otherwise(posBranch)

    val config = Config(10)
    assertEquals(outer.provide(config).unsafeRun(-5), "small-neg:5")
    assertEquals(outer.provide(config).unsafeRun(-15), "large-neg:15")
    assertEquals(outer.provide(config).unsafeRun(5), "small-pos:5")
    assertEquals(outer.provide(config).unsafeRun(15), "large-pos:15")
  }

}
