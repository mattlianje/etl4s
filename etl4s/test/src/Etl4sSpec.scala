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
    val load      = Load[String, Boolean](_ => true)

    val pipeline = extract ~> transform ~> load

    val result = pipeline.unsafeRun("Hello world!")

    assertEquals(result, true)
    assertEquals(pipeline.unsafeRun("Hello"), true)
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
    val getStr = Node[Unit, String](_ => "result1")
    val getInt = Node[Unit, Int](_ => 42)

    val combined = (getStr &> getInt).unsafeRun(())

    assertEquals(combined._1, "result1")
    assertEquals(combined._2, 42)
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

    val D: Transform[(String, String), String] = Transform[(String, String), String] {
      case (a, b) => s"$a + $b"
    }
    val E: Transform[String, String]           = Transform(c => s"$c processed")
    val F: Transform[(String, String), String] = Transform[(String, String), String] {
      case (d, e) => s"[$d | $e]"
    }

    val w1: Load[String, String] = Load(s => s)
    val w2: Load[String, Unit]   = Load(_ => ())
    val w3: Load[String, Unit]   = Load(_ => ())

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
    var count       = 0
    val e           = Node { count += 1 }
    val effectChain = e >> e >> e >> e >> e >> e
    effectChain.unsafeRun()
    assertEquals(count, 6)
  }

  test("tap preserves data flow while performing side effects") {
    var processedData: Option[String] = None

    val getData   = Extract((unit: Unit) => "test data")
    val transform = Transform[String, Int](_.length)

    val pipeline = getData.tap { data =>
      processedData = Some(data)
    } ~> transform

    val result = pipeline.unsafeRun(())

    assertEquals(result, 9)
    assertEquals(processedData, Some("test data"))
  }

  test("tap can be used for logging in pipeline composition") {
    var tempFilesCleaned = false

    val fetchData    = Extract((unit: Unit) => List("file1.txt", "file2.txt"))
    val processFiles = Transform[List[String], Int](_.size)

    val p = fetchData.tap { _ =>
      tempFilesCleaned = true
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
    // Create a node that does some work
    val workNode = Node[Unit, Int] { _ =>
      var sum = 0
      for (i <- 1 to 1000) sum += i
      sum
    }
    val insights    = workNode.unsafeRunTrace(())
    val elapsedTime = insights.timeElapsedMillis
    // Verify time tracking returns valid non-negative values
    assert(
      elapsedTime >= 0,
      s"Elapsed time ($elapsedTime ms) should be non-negative"
    )
  }

  test("unsafeRunTrace propagates failures") {
    val failingNode = Node[String, Int] { s =>
      if (s.isEmpty) throw new RuntimeException("Empty input!")
      s.length
    }

    val successTrace = failingNode.unsafeRunTrace("hello")
    assertEquals(successTrace.result, 5)
    assert(successTrace.timeElapsedMillis >= 0)

    val ex = intercept[RuntimeException](failingNode.unsafeRunTrace(""))
    assert(ex.getMessage.contains("Empty input!"))
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
        x * 2
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

  test("Context.Node alias") {
    case class Config(multiplier: Int)

    object TestContext extends Context[Config] {
      val multiply = Context.Node[Int, Int] { cfg => x =>
        x * cfg.multiplier
      }
    }

    import TestContext._
    val result = multiply.provide(Config(3)).unsafeRun(7)
    assertEquals(result, 21)
  }

  test("Reader metadata works") {
    val reader = Reader[String, String](ctx => s"$ctx-result").withMetadata("reader-info")
    assertEquals(reader.metadata, "reader-info")
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
    val node       = Node[Int, String](n => s"Value: $n")
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

  test("validation failure throws a ValidationException") {
    val node = Node[Int, String](n => s"$n")
      .ensure(input = List(x => if (x > 0) None else Some("err")))
    val ex = intercept[ValidationException](node.unsafeRun(-5))
    assert(ex.getMessage.contains("err"))
  }

  test("Reader ensure with context") {
    case class Config(min: Int, max: Int)
    val checkMin = (cfg: Config) => (x: Int) => if (x >= cfg.min) None else Some("min")
    val checkMax = (cfg: Config) => (x: Int) => if (x <= cfg.max) None else Some("max")
    val node     = Reader[Config, Node[Int, Int]] { cfg => Node((x: Int) => x) }
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
          x => { count.incrementAndGet(); if (x > 0) None else Some("positive") },
          x => { count.incrementAndGet(); if (x < 1000) None else Some("too large") }
        )
      )

    assertEquals(node.unsafeRun(5), "Value: 5")
    assertEquals(count.get(), 2)
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

  test("basic If-ElseIf-Else branching") {
    val toNegative = Node[Int, String](_ => "negative")
    val toZero     = Node[Int, String](_ => "zero")
    val toPositive = Node[Int, String](_ => "positive")

    val classify = Node[Int, Int](identity)
      .If(_ < 0)(toNegative)
      .ElseIf(_ == 0)(toZero)
      .Else(toPositive)

    assertEquals(classify.unsafeRun(-5), "negative")
    assertEquals(classify.unsafeRun(0), "zero")
    assertEquals(classify.unsafeRun(10), "positive")
  }

  test("If-ElseIf-Else branching with strings") {
    val toShort  = Node[String, String](_ => "short")
    val toMedium = Node[String, String](_ => "medium")
    val toLong   = Node[String, String](_ => "long")

    val sizeClassifier = Node[String, String](identity)
      .If(_.length < 5)(toShort)
      .ElseIf(_.length < 10)(toMedium)
      .Else(toLong)

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
      .If(_ < 0)(formatNegative)
      .ElseIf(_ == 0)(formatZero)
      .Else(formatPositive)

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
      .If(_.age < 18)(toMinor)
      .ElseIf(_.age < 65)(toAdult)
      .Else(toSenior)

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
      .If(_ >= 90)(gradeA)
      .ElseIf(_ >= 80)(gradeB)
      .ElseIf(_ >= 70)(gradeC)
      .ElseIf(_ >= 60)(gradeD)
      .Else(gradeF)

    assertEquals(gradeClassifier.unsafeRun(95), "A")
    assertEquals(gradeClassifier.unsafeRun(85), "B")
    assertEquals(gradeClassifier.unsafeRun(75), "C")
    assertEquals(gradeClassifier.unsafeRun(65), "D")
    assertEquals(gradeClassifier.unsafeRun(55), "F")
  }

  test("conditional branching with side effects") {
    var logMessages = List.empty[String]

    val logger = Node[Int, Int](identity)
      .If(_ < 0)(
        Node[Int, String] { n =>
          logMessages = logMessages :+ "negative"
          s"neg:$n"
        }
      )
      .Else(
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
      .If { n =>
        evaluations = evaluations :+ "cond1"
        n > 5
      }(Node[Int, String](_ => "first"))
      .ElseIf { n =>
        evaluations = evaluations :+ "cond2"
        n > 3
      }(Node[Int, String](_ => "second"))
      .Else(Node[Int, String](_ => "third"))

    val result = node.unsafeRun(10)
    assertEquals(result, "first")
    assertEquals(evaluations, List("cond1"))

    evaluations = List.empty
    val result2 = node.unsafeRun(4)
    assertEquals(result2, "second")
    assertEquals(evaluations, List("cond1", "cond2"))
  }

  test("conditional branching with parallel composition") {
    val branch1 = Node[Int, Int](identity)
      .If(_ % 2 == 0)(Node[Int, String](n => s"even:$n"))
      .Else(Node[Int, String](n => s"odd:$n"))

    val branch2 = Node[Int, Int](identity)
      .If(_ > 0)(Node[Int, String](_ => "positive"))
      .Else(Node[Int, String](_ => "non-positive"))

    val combined = branch1 & branch2

    assertEquals(combined.unsafeRun(4), ("even:4", "positive"))
    assertEquals(combined.unsafeRun(-3), ("odd:-3", "non-positive"))
  }

  test("partial conditional requires Else to become a Node") {
    val partial = Node[Int, Int](identity)
      .If(_ < 0)(Node[Int, String](_ => "negative"))
      .ElseIf(_ > 0)(Node[Int, String](_ => "positive"))

    assert(partial.isInstanceOf[PartialConditionalBuilder[?, ?, ?]])

    val complete = partial.Else(Node[Int, String](_ => "zero"))
    assertEquals(complete.unsafeRun(-5), "negative")
    assertEquals(complete.unsafeRun(5), "positive")
    assertEquals(complete.unsafeRun(0), "zero")
  }

  test("conditional branching with tap for debugging") {
    var tappedValue: Option[Int] = None

    val pipeline = Node[Int, Int](identity)
      .tap(n => tappedValue = Some(n))
      .If(_ < 10)(Node[Int, String](n => s"small:$n"))
      .Else(Node[Int, String](n => s"large:$n"))

    val result = pipeline.unsafeRun(5)
    assertEquals(result, "small:5")
    assertEquals(tappedValue, Some(5))
  }

  test("conditional branching with error handling") {
    val safeProcessor = Node[String, String](identity)
      .If(_.isEmpty)(
        Node[String, String](_ => throw new RuntimeException("Empty!"))
          .onFailure(_ => "error:empty")
      )
      .Else(
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
      .If(_ < 0)(Node[Int, Result](n => Failure(s"Negative: $n")))
      .Else(Node[Int, Result](n => Success(n * 2)))

    val result1 = processor.unsafeRun(-5)
    assert(result1.isInstanceOf[Failure])
    assertEquals(result1.asInstanceOf[Failure].error, "Negative: -5")

    val result2 = processor.unsafeRun(10)
    assert(result2.isInstanceOf[Success])
    assertEquals(result2.asInstanceOf[Success].value, 20)
  }

  test("conditional branching composition with ~>") {
    val classify = Node[Int, Int](identity)
      .If(_ < 0)(Node[Int, String](_ => "negative"))
      .ElseIf(_ == 0)(Node[Int, String](_ => "zero"))
      .Else(Node[Int, String](_ => "positive"))

    val format = Node[String, String](s => s"Result: $s")

    val pipeline = classify ~> format

    assertEquals(pipeline.unsafeRun(-5), "Result: negative")
    assertEquals(pipeline.unsafeRun(0), "Result: zero")
    assertEquals(pipeline.unsafeRun(10), "Result: positive")
  }

  test("nested conditional branching") {
    val outerClassifier = Node[Int, Int](identity)
      .If(_ < 0)(
        Node[Int, Int](n => n.abs)
          .If(_ < 10)(Node[Int, String](n => s"small negative: $n"))
          .Else(Node[Int, String](n => s"large negative: $n"))
      )
      .Else(
        Node[Int, Int](identity)
          .If(_ < 10)(Node[Int, String](n => s"small positive: $n"))
          .Else(Node[Int, String](n => s"large positive: $n"))
      )

    assertEquals(outerClassifier.unsafeRun(-5), "small negative: 5")
    assertEquals(outerClassifier.unsafeRun(-15), "large negative: 15")
    assertEquals(outerClassifier.unsafeRun(5), "small positive: 5")
    assertEquals(outerClassifier.unsafeRun(15), "large positive: 15")
  }

  test("Reader conditional with context-aware condition") {
    case class Config(threshold: Int)

    val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatBelow = Reader[Config, Node[Int, String]] { _ => Node(n => s"below:$n") }
    val formatAbove = Reader[Config, Node[Int, String]] { _ => Node(n => s"above:$n") }

    val pipeline = source
      .If((cfg: Config) => (n: Int) => n < cfg.threshold)(formatBelow)
      .Else(formatAbove)

    val config = Config(10)
    assertEquals(pipeline.provide(config).unsafeRun(5), "below:5")
    assertEquals(pipeline.provide(config).unsafeRun(15), "above:15")
  }

  test("Reader conditional ignoring context") {
    case class Config(multiplier: Int)

    val source         = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatNegative = Reader[Config, Node[Int, String]] { cfg =>
      Node(n => s"negative:${n * cfg.multiplier}")
    }
    val formatPositive = Reader[Config, Node[Int, String]] { cfg =>
      Node(n => s"positive:${n * cfg.multiplier}")
    }

    val pipeline = source
      .If((n: Int) => n < 0)(formatNegative)
      .Else(formatPositive)

    val config = Config(2)
    assertEquals(pipeline.provide(config).unsafeRun(-5), "negative:-10")
    assertEquals(pipeline.provide(config).unsafeRun(10), "positive:20")
  }

  test("Reader conditional with nested branching") {
    case class Config(threshold: Int)

    val outer = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
      .If((_: Int) < 0)(
        Reader[Config, Node[Int, Int]] { _ => Node(n => n.abs) }
          .If((cfg: Config) => (n: Int) => n < cfg.threshold)(
            Reader[Config, Node[Int, String]] { _ => Node(n => s"small-neg:$n") }
          )
          .Else(
            Reader[Config, Node[Int, String]] { _ => Node(n => s"large-neg:$n") }
          )
      )
      .Else(
        Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
          .If((cfg: Config) => (n: Int) => n < cfg.threshold)(
            Reader[Config, Node[Int, String]] { _ => Node(n => s"small-pos:$n") }
          )
          .Else(
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

    // Plain condition - doesn't need config
    val branch1: Reader[Config, Node[Int, String]] = source1
      .If((_: Int) % 2 == 0)(toEven)
      .Else(toOdd)

    val source2 = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val toBelow = Reader[Config, Node[Int, String]] { _ => Node[Int, String](_ => "below") }
    val toAbove = Reader[Config, Node[Int, String]] { _ => Node[Int, String](_ => "above") }

    val branch2: Reader[Config, Node[Int, String]] = source2
      .If((cfg: Config) => (n: Int) => n < cfg.threshold)(toBelow)
      .Else(toAbove)

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
      .If((n: Int) => n < 0)(toNegative)
      .ElseIf((n: Int) => n == 0)(toZero)
      .Else(toPositive)

    val config = Config(10)
    assertEquals(pipeline.provide(config).unsafeRun(-5), "negative:-5")
    assertEquals(pipeline.provide(config).unsafeRun(0), "zero")
    assertEquals(pipeline.provide(config).unsafeRun(10), "positive:10")
  }

  test("Reader conditional with plain Node branches and context-aware conditions") {
    case class Config(threshold: Int)

    val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatBelow = Node[Int, String](n => s"below:$n")
    val formatAbove = Node[Int, String](n => s"above:$n")

    val pipeline = source
      .If((cfg: Config) => (n: Int) => n < cfg.threshold)(formatBelow)
      .Else(formatAbove)

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
      .If((cfg: Config) => (n: Int) => n < cfg.threshold)(toSmallNeg)
      .Else(toLargeNeg)

    val toIdentity = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val toSmallPos = Node[Int, String](n => s"small-pos:$n")
    val toLargePos = Node[Int, String](n => s"large-pos:$n")

    val posBranch = toIdentity
      .If((cfg: Config) => (n: Int) => n < cfg.threshold)(toSmallPos)
      .Else(toLargePos)

    val outer = source
      .If((_: Int) < 0)(negBranch)
      .Else(posBranch)

    val config = Config(10)
    assertEquals(outer.provide(config).unsafeRun(-5), "small-neg:5")
    assertEquals(outer.provide(config).unsafeRun(-15), "large-neg:15")
    assertEquals(outer.provide(config).unsafeRun(5), "small-pos:5")
    assertEquals(outer.provide(config).unsafeRun(15), "large-pos:15")
  }

  test("Reader conditional with mixed plain and curried conditions") {
    case class Config(threshold: Int)

    val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](n => n) }
    val belowThresh = Reader[Config, Node[Int, String]] { cfg =>
      Node(n => s"below-${cfg.threshold}:$n")
    }
    val aboveThresh = Node[Int, String](n => s"above:$n")
    val negative    = Node[Int, String](n => s"negative:$n")

    val pipeline = source
      .If((_: Int) < 0)(negative)
      .ElseIf((cfg: Config) => (n: Int) => n < cfg.threshold)(belowThresh)
      .Else(aboveThresh)

    val config = Config(10)
    assertEquals(pipeline.provide(config).unsafeRun(-5), "negative:-5")
    assertEquals(pipeline.provide(config).unsafeRun(5), "below-10:5")
    assertEquals(pipeline.provide(config).unsafeRun(15), "above:15")
  }

  test("Reader conditional with underscore syntax for plain conditions") {
    case class User(name: String, age: Int)
    case class Config(minAge: Int)

    val source = Reader[Config, Node[User, User]] { _ => Node[User, User](u => u) }
    val adult  = Node[User, String](u => s"${u.name} is adult")
    val minor  = Node[User, String](u => s"${u.name} is minor")

    val pipeline = source
      .If((_: User).age >= 18)(adult)
      .Else(minor)

    val config = Config(18)
    assertEquals(pipeline.provide(config).unsafeRun(User("Alice", 22)), "Alice is adult")
    assertEquals(pipeline.provide(config).unsafeRun(User("Bob", 16)), "Bob is minor")
  }

  test("Reader conditional with curried config-aware condition") {
    case class User(name: String, age: Int)
    case class Config(minAge: Int)

    val source = Reader[Config, Node[User, User]] { _ => Node[User, User](u => u) }
    val adult  = Node[User, String](u => s"${u.name} is adult")
    val minor  = Node[User, String](u => s"${u.name} is minor")

    val pipeline = source
      .If((cfg: Config) => (u: User) => u.age >= cfg.minAge)(adult)
      .Else(minor)

    assertEquals(pipeline.provide(Config(18)).unsafeRun(User("Alice", 22)), "Alice is adult")
    assertEquals(pipeline.provide(Config(18)).unsafeRun(User("Bob", 16)), "Bob is minor")

    assertEquals(pipeline.provide(Config(25)).unsafeRun(User("Alice", 22)), "Alice is minor")
    assertEquals(pipeline.provide(Config(25)).unsafeRun(User("Bob", 16)), "Bob is minor")
  }

  test("Reader conditional with IfCtx/ElseIfCtx (condition on config only)") {
    case class Config(isBackfill: Boolean, isDryRun: Boolean)

    val source   = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](n => n) }
    val backfill = Node[Int, String](n => s"backfill:$n")
    val dryRun   = Node[Int, String](n => s"dryrun:$n")
    val normal   = Node[Int, String](n => s"normal:$n")

    val pipeline = source
      .IfCtx(_.isBackfill)(backfill)
      .ElseIfCtx(_.isDryRun)(dryRun)
      .Else(normal)

    assertEquals(
      pipeline.provide(Config(isBackfill = true, isDryRun = false)).unsafeRun(42),
      "backfill:42"
    )
    assertEquals(
      pipeline.provide(Config(isBackfill = false, isDryRun = true)).unsafeRun(42),
      "dryrun:42"
    )
    assertEquals(
      pipeline.provide(Config(isBackfill = false, isDryRun = false)).unsafeRun(42),
      "normal:42"
    )
  }

}

class StandaloneConditionalSpecs extends munit.FunSuite {

  test("standalone If starts a value pipeline with Else") {
    val expedite = Node[String, String](s => s"EXPEDITED:$s")
    val standard = Node[String, String](s => s"standard:$s")

    val ship = If[String](_.startsWith("rush"))(expedite).Else(standard)

    assertEquals(ship.unsafeRun("rush-order"), "EXPEDITED:rush-order")
    assertEquals(ship.unsafeRun("slow-order"), "standard:slow-order")
  }

  test("standalone If composes with ~> at the head of a pipeline") {
    val double   = Node[Int, Int](_ * 2)
    val negate   = Node[Int, Int](-_)
    val describe = Node[Int, String](n => s"= $n")

    val pipeline = If[Int](_ > 0)(double).Else(negate) ~> describe

    assertEquals(pipeline.unsafeRun(5), "= 10")
    assertEquals(pipeline.unsafeRun(-3), "= 3")
  }

  test("standalone If without Else passes unmatched input through") {
    val expedite = Node[Int, Int](_ + 100)

    val maybe: Node[Int, Int] = If[Int](_ > 10)(expedite)

    assertEquals(maybe.unsafeRun(20), 120)
    assertEquals(maybe.unsafeRun(5), 5)
  }

  test("standalone If with ElseIf chain") {
    val a = Node[Int, String](_ => "A")
    val b = Node[Int, String](_ => "B")
    val c = Node[Int, String](_ => "C")

    val grade = If[Int](_ >= 90)(a).ElseIf(_ >= 80)(b).Else(c)

    assertEquals(grade.unsafeRun(95), "A")
    assertEquals(grade.unsafeRun(85), "B")
    assertEquals(grade.unsafeRun(50), "C")
  }
}

class StandaloneContextConditionalSpecs extends munit.FunSuite {

  case class Cfg(isBackfill: Boolean, isDryRun: Boolean)

  object Jobs extends Context[Cfg] {

    val backfillFlow = Context.Load[Int, String] { _ => n => s"backfill:$n" }
    val deltaFlow    = Context.Load[Int, String] { _ => n => s"delta:$n" }
    val dryRunFlow   = Context.Load[Int, String] { _ => n => s"dry-run:$n" }

    /** Branch on context, starting the pipeline. */
    val ingest: Reader[Cfg, Node[Int, String]] =
      Context.If(_.isBackfill)(backfillFlow).Else(deltaFlow)

    /** Chain further context branches with ElseIfCtx. */
    val ingestChained: Reader[Cfg, Node[Int, String]] =
      Context
        .If(_.isBackfill)(backfillFlow)
        .ElseIfCtx(_.isDryRun)(dryRunFlow)
        .Else(deltaFlow)

    /** No Else: unmatched input passes through unchanged. */
    val maybeBump =
      Context.If(_.isBackfill)(Context.Transform[Int, Int] { _ => n => n + 1 })
  }

  test("Context.If starts a context pipeline on context") {
    assertEquals(Jobs.ingest.provide(Cfg(isBackfill = true, false)).unsafeRun(7), "backfill:7")
    assertEquals(Jobs.ingest.provide(Cfg(isBackfill = false, false)).unsafeRun(7), "delta:7")
  }

  test("Context.If chains further context branches with ElseIfCtx") {
    assertEquals(
      Jobs.ingestChained.provide(Cfg(isBackfill = true, false)).unsafeRun(7),
      "backfill:7"
    )
    assertEquals(
      Jobs.ingestChained.provide(Cfg(isBackfill = false, true)).unsafeRun(7),
      "dry-run:7"
    )
    assertEquals(
      Jobs.ingestChained.provide(Cfg(isBackfill = false, false)).unsafeRun(7),
      "delta:7"
    )
  }

  test("Context conditional without Else passes unmatched input through") {
    assertEquals(Jobs.maybeBump.provide(Cfg(isBackfill = true, false)).unsafeRun(41), 42)
    assertEquals(Jobs.maybeBump.provide(Cfg(isBackfill = false, false)).unsafeRun(41), 41)
  }
}

class BatchCombinatorSpecs extends munit.FunSuite {
  import scala.concurrent.ExecutionContext.Implicits.global

  val clean: Node[Int, Int]     = Node(_ + 1)
  val enrich: Node[Int, String] = Node(n => s"v$n")

  test("each maps a node over a batch inside a pipeline") {
    val extractBatch: Node[Unit, List[Int]] = Node(_ => List(1, 2, 3))
    val load: Node[List[String], String]    = Node(_.mkString(","))
    val pipeline                            = extractBatch ~> each(clean ~> enrich) ~> load
    assertEquals(pipeline.unsafeRun(()), "v2,v3,v4")
  }

  test("each preserves the concrete collection type") {
    val src: Node[Unit, Vector[Int]] = Node(_ => Vector(1, 2, 3))
    val out: Vector[Int]             = (src ~> each(clean)).unsafeRun(())
    assertEquals(out, Vector(2, 3, 4))
  }

  test("eachPar runs elements concurrently, preserving order") {
    val src: Node[Unit, List[Int]] = Node(_ => (1 to 10).toList)
    val out: List[Int]             = (src ~> eachPar(4)(clean)).unsafeRun(())
    assertEquals(out, (2 to 11).toList)
  }

  test("eachSlice chunks the batch into windows") {
    val src: Node[Unit, List[Int]]      = Node(_ => (1 to 10).toList)
    val loadBatch: Node[List[Int], Int] = Node(_.sum)
    val out: List[Int]                  = (src ~> eachSlice(3)(loadBatch)).unsafeRun(())
    assertEquals(out, List(6, 15, 24, 10))
  }
}

class VarianceSpecs extends munit.FunSuite {
  // Node[-In, +Out]: contravariant input, covariant output.
  class Animal
  class Dog extends Animal { override def toString = "dog" }

  class Request(val body: String)
  class Response(val body: String)

  test("~> composes across subtypes via declaration-site variance") {
    // a produces a Dog; b accepts any Animal -> a Dog IS an Animal, so a ~> b type-checks.
    val a: Node[Request, Dog]             = Node(_ => new Dog)
    val b: Node[Animal, Response]         = Node(animal => new Response(animal.toString))
    val pipeline: Node[Request, Response] = a ~> b
    assertEquals(pipeline.unsafeRun(new Request("x")).body, "dog")
  }

  test("covariant output widens") {
    val makeDog: Node[Unit, Dog]     = Node(_ => new Dog)
    val asAnimal: Node[Unit, Animal] = makeDog // +Out allows widening
    assert(asAnimal.unsafeRun(()).isInstanceOf[Animal])
  }

  test("contravariant input narrows") {
    val onAnimal: Node[Animal, String] = Node(_.toString)
    val onDog: Node[Dog, String]       = onAnimal // -In allows narrowing
    assertEquals(onDog.unsafeRun(new Dog), "dog")
  }
}
