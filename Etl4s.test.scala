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

  test("associative property holds") {
    val e1     = Extract(1)
    val plus1  = Transform[Int, Int](x => x + 2)
    val times5 = Transform[Int, Int](x => x * 5)

    val p1 = e1 ~> plus1 ~> times5
    val p2 = e1 ~> (plus1 ~> times5)

    assert(p1(()) == p2(()))
  }

  test("unsafeRunTraced measures execution time") {
    // Create a node that sleeps for a specific time
    val sleepDuration = 100
    val sleepNode = Node[Unit, Unit] { _ =>
      Thread.sleep(sleepDuration)
    }
    val insights    = sleepNode.unsafeRunTraced(())
    val elapsedTime = insights.timing.get
    assert(
      elapsedTime >= sleepDuration,
      s"Elapsed time ($elapsedTime ms) should be at least $sleepDuration ms"
    )
    assert(
      elapsedTime < sleepDuration + 50,
      s"Elapsed time ($elapsedTime ms) should not be much longer than $sleepDuration ms"
    )
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

    val contextNode = Context[Config, Node[String, String]] { ctx =>
      Node(input => s"${ctx.prefix}: $input")
    }

    val processNode = Context[Config, Node[String, Int]] { ctx =>
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

    val lengthProcessor = Context[Config, Node[Int, String]] { ctx =>
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

    val formatTimestamp = Context[HasExtendedDateConfig, Node[String, String]] { ctx =>
      Node { timestamp =>
        s"Formatted with ${ctx.dateFormat}: $timestamp (from ${ctx.appName})"
      }
    }

    val checkDateRange = Context[HasDateConfig, Node[String, String]] { ctx =>
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

    object TestContext extends Etl4sContext[AppConfig] {

      val extractWithContext: ExtractWithContext[String, Int] =
        Context { ctx =>
          Extract { input =>
            s"${ctx.serviceName}: $input".length * ctx.timeout
          }
        }

      val transformWithContext: TransformWithContext[Int, String] =
        Context { ctx =>
          Transform { value =>
            s"Processed by ${ctx.serviceName} with value $value"
          }
        }

      val testC = extractWithContext[Int, Int] { ctx => x =>
        println(s"Yo ${ctx.serviceName}"); x * 2
      }
    }

    import TestContext._
    val pipeline = extractWithContext ~> transformWithContext

    val config = AppConfig("DataService", 2)
    val result = pipeline.provideContext(config).unsafeRun("test")
  }

  test("reader(node) all operators compat") {
    val r1    = Context[Int, Transform[Int, Int]] { ctx => Transform(_ * 2) }
    val t1    = Transform[Int, Int](_ * 2)
    val tUnit = Transform[Unit, Unit](_ => ())
    val unitR = Context[Int, Transform[Unit, Unit]] { ctx => Transform(_ => ()) }

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

    object TestContext extends Etl4sContext[AppConfig] {
      val getData = Etl4sContext.Extract[String, Int] { config => input =>
        s"${config.serviceName}: $input".length * config.timeout
      }

      val processData = Etl4sContext.Transform[Int, String] { config => value =>
        s"Processed by ${config.serviceName} with value $value"
      }
    }

    import TestContext._
    val pipeline = getData ~> processData

    val config = AppConfig("DataService", 2)
    val result = pipeline.provideContext(config).unsafeRun("test")

    assertEquals(result, "Processed by DataService with value 34")
  }

  test("basic logging with withLog") {
    val node = Transform[String, Int](_.length)
      .withLog("Processing string")
      .withLog((input: String, output: Int) => s"$input -> $output")

    val insights = node.unsafeRunTraced("hello")
    assertEquals(insights.result, 5)
    assertEquals(insights.logs, List("Processing string", "hello -> 5"))
  }

  test("validation with Runtime") {
    val node = Transform[String, Int](_.length)
      .validate((input, output) => (output > 0, "Length must be positive"))
      .validate((input, output) => (input.nonEmpty, "Input cannot be empty"))

    val insights = node.unsafeRunTraced("test")
    assertEquals(insights.result, 4)
    assertEquals(insights.validationErrors, List.empty)
    assert(!insights.hasValidationErrors)

    val failInsights = node.unsafeRunTraced("")
    assertEquals(failInsights.result, 0)
    assertEquals(failInsights.validationErrors.size, 2)
    assert(failInsights.hasValidationErrors)
  }

  test("nodes can access current execution state") {
    val upstream = Transform[String, Int] { input =>
      Runtime.logValidation("Upstream error")
      input.length
    }

    val downstream = Transform[Int, Int] { value =>
      if (Runtime.hasValidationErrors) {
        Runtime.log("Using fallback due to errors")
        -999
      } else {
        value * 2
      }
    }

    val pipeline = upstream ~> downstream
    val insights = pipeline.unsafeRunTraced("test")

    assertEquals(insights.result, -999)
    assertEquals(insights.validationErrors, List("Upstream error"))
    assertEquals(insights.logs, List("Using fallback due to errors"))
  }

  test("any-type logging and validation") {
    case class LogEvent(message: String, timestamp: Long)
    case class ValidationError(field: String, code: Int)

    val node = Transform[String, Int](_.length)
      .withLog(LogEvent("Started processing", System.currentTimeMillis()))
      .validate((input, output) => (output > 0, ValidationError("length", 404)))

    val insights = node.unsafeRunTraced("test")
    assertEquals(insights.result, 4)
    assert(insights.logs.head.isInstanceOf[LogEvent])
    assertEquals(insights.validationErrors, List.empty)
  }

  test("unified access to current execution insights") {
    val node = Transform[String, String] { input =>
      Runtime.log("Step 1")
      val current = Runtime.current
      assertEquals(current.logs, List("Step 1"))
      assert(current.timing.isDefined)

      input.toUpperCase
    }

    val insights = node.unsafeRunTraced("hello")
    assertEquals(insights.result, "HELLO")
    assertEquals(insights.logs, List("Step 1"))
    assert(insights.timing.isDefined)
  }

}
