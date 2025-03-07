package etl4s

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import java.time.LocalDate
import java.time.temporal.ChronoUnit

class Etl4sSpec extends munit.FunSuite {

  test("sequential pipeline should combine extracts and loads correctly") {
    val e1 = Extract(42)
    val e2 = Extract("hello")
    val e3 = Extract("world")

    val l1 = Load[String, List[String]](_.split("-").toList)
    val l2 = Load[String, Map[String, Int]](s => Map("length" -> s.length))

    val multiESync = e1 &> e2 &> e3
    val flattened = Extract[Unit, (Int, String, String)] { x =>
      val ((i, s1), s2) = multiESync.runSync(x)
      (i, s1, s2)
    }

    val pipeline = flattened ~>
      Transform[(Int, String, String), String]({ case (num, str, str2) =>
        s"$str-$str2-$num"
      }) ~>
      (l1 & l2)

    val (list, map) = pipeline.unsafeRun(())
    assertEquals(list, List("hello", "world", "42"))
    assertEquals(map, Map("length" -> 14))
  }

  test("parallel pipeline should execute extracts and loads concurrently") {
    var e1Started, e2Started, e3Started = 0L
    var l1Started, l2Started = 0L

    val e1 = Extract[Unit, Int] { _ =>
      e1Started = System.currentTimeMillis()
      Thread.sleep(100)
      42
    }

    val e2 = Extract[Unit, String] { _ =>
      e2Started = System.currentTimeMillis()
      Thread.sleep(100)
      "hello"
    }

    val e3 = Extract[Unit, String] { _ =>
      e3Started = System.currentTimeMillis()
      Thread.sleep(100)
      "world"
    }

    val l1 = Load[String, List[String]] { s =>
      l1Started = System.currentTimeMillis()
      Thread.sleep(100)
      s.split("-").toList
    }

    val l2 = Load[String, Map[String, Int]] { s =>
      l2Started = System.currentTimeMillis()
      Thread.sleep(100)
      Map("length" -> s.length)
    }

    val pipeline = (e1 &> e2 &> e3) ~>
      Transform[((Int, String), String), String]({ case ((num, str), str2) =>
        s"$str-$str2-$num"
      }) ~>
      (l1 &> l2)

    val pipeline2 = (e1 &> e2 &> e3) ~>
      Transform[((Int, String), String), String]({ case ((num, str), str2) =>
        s"$str-$str2-$num"
      }) ~>
      (l1 &> l2)

    val (list, map) = pipeline.unsafeRun(())

    assertEquals(list, List("hello", "world", "42"))
    assertEquals(map, Map("length" -> 14))

    assert(
      Math.abs(e1Started - e2Started) < 50,
      "e1 and e2 should start around same time"
    )
    assert(
      Math.abs(e2Started - e3Started) < 50,
      "e2 and e3 should start around same time"
    )
    assert(
      Math.abs(l1Started - l2Started) < 50,
      "l1 and l2 should start around same time"
    )
  }

  test("pipeline should support monadic composition") {
    val fetchUser: Transform[String, String] =
      Transform(id => s"Fetching user $id")
    val loadUser: Load[String, String] = Load(msg => s"User loaded: $msg")

    val fetchOrder: Transform[Int, String] =
      Transform(id => s"Fetching order $id")
    val loadOrder: Load[String, String] = Load(msg => s"Order loaded: $msg")

    val userPipeline = Extract("user123") ~> fetchUser ~> loadUser
    val ordersPipeline = Extract(42) ~> fetchOrder ~> loadOrder

    val combinedPipeline = (for {
      userData <- userPipeline
      orderData <- ordersPipeline
    } yield Extract(s"$userData | $orderData") ~>
      Transform { _.toUpperCase } ~>
      Load { x => s"Final result: $x" }).flatten

    val result = combinedPipeline.unsafeRun(())

    assertEquals(
      result,
      "Final result: USER LOADED: FETCHING USER USER123 | ORDER LOADED: FETCHING ORDER 42"
    )
  }

  test("pipeline should run in config context") {
    case class ApiConfig(url: String, key: String)
    val config = ApiConfig("https://api.com", "secret")

    val fetchUser = Reader[ApiConfig, Transform[String, String]] { config =>
      Transform(id => s"Fetching user $id from ${config.url}")
    }

    val loadUser = Reader[ApiConfig, Load[String, String]] { config =>
      Load(msg => s"User loaded with key ${config.key}: $msg")
    }

    val fetchOrder = Reader[ApiConfig, Transform[Int, String]] { config =>
      Transform(id => s"Fetching order $id from ${config.url}")
    }

    val loadOrder = Reader[ApiConfig, Load[String, String]] { config =>
      Load(msg => s"Order loaded with key ${config.key}: $msg")
    }

    val configuredPipeline = for {
      userTransform <- fetchUser
      userLoader <- loadUser
      orderTransform <- fetchOrder
      orderLoader <- loadOrder
      userPipeline = Extract("user123") ~> userTransform ~> userLoader
      orderPipeline = Extract(42) ~> orderTransform ~> orderLoader
      combined = for {
        userData <- userPipeline
        orderData <- orderPipeline
      } yield Extract(s"$userData | $orderData") ~>
        Transform[String, String](_.toUpperCase) ~>
        Load[String, String] { x => s"Final result: $x" }
    } yield combined.flatten

    val result = configuredPipeline.run(config).unsafeRun(())

    assertEquals(
      result,
      "Final result: USER LOADED WITH KEY SECRET: FETCHING USER USER123 FROM HTTPS://API.COM | ORDER LOADED WITH KEY SECRET: FETCHING ORDER 42 FROM HTTPS://API.COM"
    )
  }

  test("flatten should work with sequential transformations") {
    val pipeline = for {
      n <- Pipeline(Extract(5))
    } yield Pipeline(Extract(n * n)) ~>
      Transform[Int, String](x => s"Result: $x") ~>
      Load[String, String](identity)

    val result = pipeline.flatten.unsafeRun(())
    assertEquals(result, "Result: 25")
  }

  test("flatten should handle conditional paths") {
    val pipeline = for {
      n <- Pipeline(Extract(7))
    } yield
      if (n > 5) {
        Pipeline(Extract("big")) ~> Transform(_.toUpperCase) ~> Load(identity)
      } else {
        Pipeline(Extract("small")) ~> Transform(_.toLowerCase) ~> Load(identity)
      }

    val result = pipeline.flatten.unsafeRun(())
    assertEquals(result, "BIG")
  }

  test("flatten should work with chained operations") {
    val pipeline = for {
      initial <- Pipeline(Extract("test"))
      withPrefix = s"prefix_$initial"
      withSuffix = s"${withPrefix}_suffix"
    } yield Pipeline(Extract(withSuffix)) ~> Load(identity)

    val result = pipeline.flatten.unsafeRun(())
    assertEquals(result, "prefix_test_suffix")
  }

  test("flatten should handle validation scenarios") {
    case class ValidationError(msg: String)

    val pipeline = for {
      n <- Pipeline(Extract(-5))
      validated =
        if (n > 0) Right(n) else Left(ValidationError("Must be positive"))
    } yield validated match {
      case Right(value) => Pipeline(Extract(s"Valid: $value")) ~> Load(identity)
      case Left(error) =>
        Pipeline(Extract(s"Error: ${error.msg}")) ~> Load(identity)
    }

    val result = pipeline.flatten.unsafeRun(())
    assertEquals(result, "Error: Must be positive")
  }

  test("flatten should work with dependent data") {
    case class User(id: Int, name: String)
    case class Order(userId: Int, item: String)

    val pipeline = for {
      user <- Pipeline(Extract(User(1, "Alice")))
      order <- Pipeline(Extract(Order(1, "Book")))
    } yield Pipeline(
      Extract(s"User ${user.name} ordered ${order.item}")
    ) ~> Load(identity)

    val result = pipeline.flatten.unsafeRun(())
    assertEquals(result, "User Alice ordered Book")
  }

  test("pipeline should handle retries using Try") {
    var attempts = 0
    val failingTransform = Transform[Int, String] { n =>
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"Attempt $attempts failed")
      else s"Success after $attempts attempts"
    }

    val pipeline = Pipeline(Extract(42)) ~> failingTransform.withRetry(
      RetryConfig(maxAttempts = 3, initialDelay = 10.millis)
    )

    val result = pipeline.safeRun(())
    assert(result.isSuccess)
    assertEquals(result.get, "Success after 3 attempts")
    assertEquals(attempts, 3)
  }

  test("pipeline should return Failure after max retries") {
    var attempts = 0
    val alwaysFailingTransform = Transform[Int, String] { n =>
      attempts += 1
      throw new RuntimeException(s"Failed attempt $attempts")
    }

    val pipeline = Pipeline(Extract(42)) ~> alwaysFailingTransform.withRetry(
      RetryConfig(maxAttempts = 2)
    )

    val result = pipeline.safeRun(())
    assert(result.isFailure)
    assert(result.failed.get.getMessage == "Failed attempt 2")
    assertEquals(attempts, 2)
  }

  test("pipeline should handle successful first attempt") {
    var attempts = 0
    val successfulTransform = Transform[Int, String] { n =>
      attempts += 1
      "First try success!"
    }

    val pipeline = Pipeline(Extract(42)) ~> successfulTransform.withRetry(
      RetryConfig(maxAttempts = 3)
    )

    val result = pipeline.safeRun(())
    assert(result.isSuccess)
    assertEquals(result.get, "First try success!")
    assertEquals(attempts, 1)
  }

  test("should flatten nested tuples") {
    val e1 = Extract(1)
    val e2 = Extract("two")
    val e3 = Extract("three")

    val result = (e1 &> e2 &> e3).zip.runSync(())
    assertEquals(result, (1, "two", "three"))

    val e4 = Extract[Unit, Double](_ => 4.0)
    val result2 = (e1 &> e2 &> e3 &> e4).zip.runSync(())
    assertEquals(result2, (1, "two", "three", 4.0))
  }

  test("should create pipeline with flattened tuples") {
    val e1 = Extract(1)
    val e2 = Extract("two")
    val e3 = Extract("three")

    val transform = Transform[(Int, String, String), String] {
      case (num, s1, s2) =>
        s"Number: $num, First: $s1, Second: $s2"
    }

    val load = Load[String, String](s => s"Final: $s")

    val pipeline =
      (e1 &> e2 &> e3).zip ~> transform ~> load

    val result = pipeline.unsafeRun(())
    assertEquals(result, "Final: Number: 1, First: two, Second: three")

    val e4 = Extract[Unit, Double](_ => 4.0)
    val transform2 = Transform[(Int, String, String, Double), String] {
      case (num, s1, s2, d) =>
        s"Number: $num, First: $s1, Second: $s2, Double: $d"
    }

    val pipeline2 = (e1 &> e2 &> e3 &> e4).zip ~> transform2 ~> load
    val result2 = pipeline2.unsafeRun(())
    assertEquals(
      result2,
      "Final: Number: 1, First: two, Second: three, Double: 4.0"
    )
  }

  test(
    "Transform should handle Map input while maintaining sequential operations"
  ) {
    case class Person(name: String, age: Int, scores: List[Int])

    val input = Map(
      "alice" -> Person("Alice", 25, List(95, 88, 92)),
      "bob" -> Person("Bob", 23, List(88, 85, 90))
    )

    val process: Transform[Map[String, Person], Map[String, String]] = for {
      avgScores <- Transform[Map[String, Person], Map[String, Double]](people =>
        people.map { case (id, p) =>
          id -> (p.scores.sum.toDouble / p.scores.length)
        }
      )
      grades <- Transform[Map[String, Person], Map[String, String]](_ =>
        avgScores.map { case (id, score) =>
          id -> (if (score > 90) "A"
                 else if (score > 80) "B"
                 else "C")
        }
      )
    } yield grades

    val expected = Map(
      "alice" -> "A",
      "bob" -> "B"
    )

    assertEquals(process.runSync(input), expected)
  }

  test(
    "should handle Map input as ETL pipeline with sequential operations and for-comprehension"
  ) {
    case class Person(name: String, age: Int, scores: List[Int])

    val e1 = Extract[Unit, Map[String, Person]]((u: Unit) =>
      Map(
        "alice" -> Person("Alice", 25, List(95, 88, 92)),
        "bob" -> Person("Bob", 23, List(88, 85, 90))
      )
    )

    val process: Transform[Map[String, Person], Map[String, String]] = for {
      avgScores <- Transform[Map[String, Person], Map[String, Double]](people =>
        people.map { case (id, p) =>
          id -> (p.scores.sum.toDouble / p.scores.length)
        }
      )
      gradesByAge <- Transform[Map[String, Person], Map[String, String]](
        people =>
          people.map { case (id, p) =>
            id -> (if (p.age > 24) "Old " else "Young ")
          }
      )
      finalGrades <- Transform[Map[String, Person], Map[String, String]](_ =>
        avgScores.map { case (id, score) =>
          val agePrefix = gradesByAge(id)
          id -> s"$agePrefix${
              if (score > 90) "A"
              else if (score > 80) "B"
              else "C"
            }"
        }
      )
    } yield finalGrades

    val load = Load[Map[String, String], String](grades =>
      grades
        .map { case (id, grade) =>
          s"Student $id received grade: $grade"
        }
        .mkString("\n")
    )

    val pipeline = e1 ~> process ~> load

    val expected =
      """Student alice received grade: Old A
      |Student bob received grade: Young B""".stripMargin

    assertEquals(pipeline.unsafeRun(()), expected)
  }

  test(
    "should demonstrate simple ETL pipeline with multiple dataframes and config"
  ) {
    case class DataConfig(threshold: Double)

    val inputDfs = Extract[Unit, Map[String, Map[String, Double]]](_ =>
      Map(
        "sales" -> Map(
          "product1" -> 100.0,
          "product2" -> 400.0,
          "product3" -> 150.0
        ),
        "costs" -> Map(
          "product1" -> 80.0,
          "product2" -> 150.0,
          "product3" -> 90.0
        )
      )
    )

    val process: Reader[DataConfig, Transform[
      Map[String, Map[String, Double]],
      Map[String, String]
    ]] =
      Reader { config =>
        for {
          dfs <- Transform.pure[Map[String, Map[String, Double]]]

          margins: Map[String, Double] = dfs("sales").map {
            case (product, revenue) =>
              val cost = dfs("costs").getOrElse(product, 0.0)
              val margin = revenue - cost
              product -> margin
          }

          enriched <- Transform[Map[String, Map[String, Double]], Map[
            String,
            String
          ]](_ =>
            margins.map { case (product, margin) =>
              product -> {
                if (margin > config.threshold) s"High margin: $margin"
                else s"Low margin: $margin"
              }
            }
          )
        } yield enriched
      }

    val load = Load[Map[String, String], String](results =>
      results
        .map { case (product, status) =>
          s"$product -> $status"
        }
        .mkString("\n")
    )

    val config = DataConfig(threshold = 100.0)

    val pipeline = inputDfs ~> process.run(config) ~> load
    val result = pipeline.unsafeRun(())

    assert(result.contains("product2 -> High margin"))
    assert(result.contains("product1 -> Low margin"))
  }

  test("should handle errors with onFailure") {
    case class MyError(msg: String)

    val riskyExtract =
      Extract[Unit, String](_ => throw new RuntimeException("Boom!"))

    val riskyTransform = Transform[String, Int](str =>
      if (str.contains("safe")) str.length
      else throw new IllegalArgumentException("Unsafe input")
    )

    val safeExtract = riskyExtract.onFailure(e => s"Failed: ${e.getMessage}")
    assertEquals(safeExtract.runSync(()), "Failed: Boom!")

    val p1 = Extract("bad input") ~>
      riskyTransform.onFailure(e => -1) ~>
      Transform[Int, String](n => if (n < 0) "Error occurred" else "Success")

    val p2 =
      (Extract("bad input") ~> riskyTransform).onFailure(_ => "Error occurred")

    assertEquals(p1.unsafeRun(()), "Error occurred")
    assertEquals(p2.unsafeRun(()), "Error occurred")
  }

  test("transform should accumulate multiple validation errors") {
    case class User(name: String, age: Int, email: String)

    def validateName(name: String): Validated[String, String] =
      if (name.isEmpty) Validated.invalid("Name cannot be empty")
      else if (name.length < 2) Validated.invalid("Name too short")
      else if (!name.matches("[A-Za-z ]+"))
        Validated.invalid("Name can only contain letters")
      else Validated.valid(name)

    def validateAge(age: Int): Validated[String, Int] =
      if (age < 0) Validated.invalid("Age must be positive")
      else if (age > 150) Validated.invalid("Age not realistic")
      else Validated.valid(age)

    def validateEmail(email: String): Validated[String, String] =
      Validated
        .valid(email)
        .zip(
          if (!email.contains("@")) Validated.invalid("Email must contain @")
          else Validated.valid(email)
        )
        .zip(
          if (!email.contains(".")) Validated.invalid("Email must contain .")
          else Validated.valid(email)
        )
        .map { case ((email, _), _) => email }

    val validateUser =
      Transform[(String, Int, String), Validated[String, User]] {
        case (name, age, email) =>
          validateName(name)
            .zip(validateAge(age))
            .zip(validateEmail(email))
            .map { case ((name, age), email) => User(name, age, email) }
      }

    val validInput = ("Matthieu", 27, "matthieu.court@protonmail.com")
    val invalidInput = ("", -1, "invalid")

    val validResult = validateUser.runSync(validInput)
    val invalidResult = validateUser.runSync(invalidInput)

    assert(validResult.value.isRight)
    assert(invalidResult.value.isLeft)
    assertEquals(
      invalidResult.value.left.get,
      List(
        "Name cannot be empty",
        "Age must be positive",
        "Email must contain @",
        "Email must contain ."
      )
    )
  }

  test("Extract and Load should support andThen composition") {
    val extract1: Extract[String, Int] = Extract(_.length)
    val extract2: Extract[Int, Double] = Extract(_ * 2.0)
    val combined = extract1 andThen extract2
    assertEquals(combined.runSync("hello"), 10.0)

    val load1: Load[String, Int] = Load(_.toInt)
    val load2: Load[Int, String] = Load(x => (x * 2).toString)
    val combinedLoad = load1 andThen load2
    assertEquals(combinedLoad.runSync("21"), "42")
  }

  test(
    "Transform should compose with Extract and Load in concurrent pipelines"
  ) {
    var t1Started, t2Started, l1Started = 0L

    val e1 = Extract("hello")
    val t1 = Transform[String, Int] { s =>
      t1Started = System.currentTimeMillis()
      Thread.sleep(100)
      s.length
    }
    val t2 = Transform[String, String] { s =>
      t2Started = System.currentTimeMillis()
      Thread.sleep(100)
      s.toUpperCase
    }
    val l1 = Load[(Int, String), List[String]] { s =>
      l1Started = System.currentTimeMillis()
      Thread.sleep(100)
      s._2.split("").toList
    }

    val pipeline = e1 ~> (t1 &> t2) ~> l1

    pipeline.unsafeRun(())

    assert(
      Math.abs(t1Started - t2Started) < 50,
      "t1 and t2 should start around same time"
    )
  }

  test("ETL pipeline with Reader config and Writer logs") {
    case class Config(url: String, key: String)
    type Log = List[String]
    type DataWriter[A] = Writer[Log, A]

    val fetchUser = Reader[Config, Transform[String, DataWriter[String]]] {
      config =>
        Transform { id =>
          Writer(
            List(s"Fetching user $id from ${config.url}"),
            s"User $id"
          )
        }
    }

    val processUser =
      Reader[Config, Transform[DataWriter[String], DataWriter[String]]] {
        config =>
          Transform { writerInput =>
            for {
              value <- writerInput
              result <- Writer(
                List(s"Processing $value with key ${config.key}"),
                s"Processed: $value"
              )
            } yield result
          }
      }

    val configuredPipeline = for {
      fetch <- fetchUser
      process <- processUser
    } yield Extract("123") ~> fetch ~> process

    val config = Config("https://api.com", "secret")
    val (logs, result) = configuredPipeline.run(config).unsafeRun(()).run()

    assertEquals(
      logs,
      List(
        "Fetching user 123 from https://api.com",
        "Processing User 123 with key secret"
      )
    )
    assertEquals(result, "Processed: User 123")
  }

  test("pipelines should compose with ~>") {
    val plusFiveExclaim: Pipeline[Int, String] =
      Transform((x: Int) => x + 5) ~>
        Transform((x: Int) => x.toString + "!")

    val doubleString: Pipeline[String, String] =
      Extract((s: String) => s) ~>
        Transform[String, String](x => x ++ x)

    val pipeline: Pipeline[Int, String] = plusFiveExclaim ~> doubleString

    assertEquals(pipeline.unsafeRun(2), "7!7!")
  }

  test("should flatten nested tuples up to 10 elements") {
    val e1 = Extract(1)
    val e2 = Extract("two")
    val e3 = Extract(3.0)
    val e4 = Extract(true)
    val e5 = Extract('c')
    val e6 = Extract(6L)
    val e7 = Extract(7.0f)
    val e8 = Extract("eight")
    val e9 = Extract(9)
    val e10 = Extract(10L)

    val combined =
      (e1 &> e2 &> e3 &> e4 &> e5 &> e6 &> e7 &> e8 &> e9 &> e10).zip

    val result = combined.runSync(())

    val expected = (1, "two", 3.0, true, 'c', 6L, 7.0f, "eight", 9, 10L)

    assertEquals(result, expected)

    val transform = Transform[
      (Int, String, Double, Boolean, Char, Long, Float, String, Int, Long),
      String
    ] { case (a1, a2, a3, a4, a5, a6, a7, a8, a9, a10) =>
      s"Combined: $a1,$a2,$a3,$a4,$a5,$a6,$a7,$a8,$a9,$a10"
    }

    val load = Load[String, String](identity)

    val pipeline = combined ~> transform ~> load
    val pipelineResult = pipeline.unsafeRun(())

    assert(
      pipelineResult.startsWith("Combined: 1,two,3.0,true,c,6,7.0,eight,9,10")
    )
  }

  test("pipeline should support both sequential and parallel composition") {
    var p1Started, p2Started = 0L

    val p1 = Pipeline(Extract[Unit, Int] { _ =>
      p1Started = System.currentTimeMillis()
      Thread.sleep(100)
      42
    })

    val p2 = Pipeline(Extract[Unit, String] { _ =>
      p2Started = System.currentTimeMillis()
      Thread.sleep(100)
      "hello"
    })

    val sequential = p1 & p2
    val (seqNum, seqStr) = sequential.unsafeRun(())
    assertEquals(seqNum, 42)
    assertEquals(seqStr, "hello")
    assert(
      Math.abs(p1Started - p2Started) >= 100,
      "Sequential operations should run one after another"
    )

    p1Started = 0L
    p2Started = 0L

    val parallel = p1 &> p2
    val (parNum, parStr) = parallel.unsafeRun(())
    assertEquals(parNum, 42)
    assertEquals(parStr, "hello")
    assert(
      Math.abs(p1Started - p2Started) < 50,
      "Parallel operations should start at roughly the same time"
    )
  }

  test("should fuse two pipelines then split and return one result") {
    case class User(name: String, age: Int)
    case class Order(id: Int, amount: Double)
    case class Report(text: String)
    case class Notification(message: String)

    val userPipeline =
      Extract("Alice") ~> Transform[String, User](name => User(name, 25))
    val orderPipeline =
      Extract(42) ~> Transform[Int, Order](id => Order(id, 99.99))

    val fusedPipeline = (userPipeline & orderPipeline).zip ~>
      Transform[(User, Order), (Report, Notification)] { case (user, order) =>
        val report = Report(s"User ${user.name} spent ${order.amount}")
        val notification =
          Notification(s"Order ${order.id} processed for ${user.name}")
        (report, notification)
      }

    val reportPipeline =
      fusedPipeline ~> Transform[(Report, Notification), Report](_._1)

    val result = reportPipeline.unsafeRun(())
    assertEquals(result, Report("User Alice spent 99.99"))
  }

  test("showcase multi-source pipeline with multiple writes") {
    case class A(value: String)
    case class B(value: Int)
    case class C(value: Double)
    case class D(a: String, b: Int)
    case class E(value: Double)
    case class F(d: D, e: E)
    case class Result(value: String)

    val s1 = Extract("source1") ~> Transform[String, A](s => A(s))
    val s2 = Extract(42) ~> Transform[Int, B](n => B(n))
    val s3 = Extract(3.14) ~> Transform[Double, C](d => C(d))

    val tD = Transform[(A, B), D] { case (a, b) => D(a.value, b.value) }
    val tE = Transform[C, E](c => E(c.value * 2))
    val tF = Transform[(D, E), F] { case (d, e) => F(d, e) }

    val w1 = Transform[F, Result] { f =>
      println(s"W1: Writing ${f.d.a}")
      Result(s"W1: ${f.d.a}")
    }
    val w2 = Transform[F, Unit] { f =>
      println(s"W2: Writing ${f.d.b}")
    }
    val w3 = Transform[F, Unit] { f =>
      println(s"W3: Writing ${f.e.value}")
    }

    val pipeline =
      (
        ((s1 & s2) ~> tD) &
          (s3 ~> tE)
      ) ~> tF ~>
        (w1 & w2 & w3).zip

    val R = pipeline.unsafeRun(())._1

    assertEquals(R, Result("W1: source1"))
  }

  test("Pretty pipeline 1") {

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
    val w2: Load[String, Unit] = Load(s => println(s"W2: $s"))
    val w3: Load[String, Unit] = Load(s => println(s"W3: $s"))

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

  test("Pretty pipeline 2") {
    /* Re-use existing pipelines */
    val fetchUser: Pipeline[Unit, String] =
      Extract("john_doe") ~> Transform[String, String](_.toUpperCase)

    /* ... or define new sources */
    val fetchOrder: Extract[Unit, String] = Extract("order 42")
    val fetchPayment: Extract[Unit, String] = Extract("99.99")

    val D: Transform[(String, String), String] =
      Transform { case (user, order) => s"$user placed $order" }
    val E: Transform[String, String] =
      Transform(payment => s"Validated: $payment")
    val F: Transform[(String, String), String] =
      Transform { case (userOrder, payment) =>
        s"REPORT: [$userOrder | $payment]"
      }

    /* Define multiple sinks */
    val saveToDB: Load[String, String] = Load(report => {
      println(s"DB Save: $report"); report
    })
    val sendEmail: Load[String, Unit] =
      Load(report => println(s"Email Sent: $report"))
    val logReport: Load[String, Unit] =
      Load(report => println(s"Log Entry: $report"))

    /* So you can connect your pipeline to others */
    val R = Load[(String, Unit, Unit), String](_._1)

    val pipeline: Pipeline[Unit, String] =
      (
        (fetchUser & fetchOrder) ~> D &
          (fetchPayment ~> E)
      ) ~> F ~>
        (saveToDB & sendEmail & logReport).zip ~> R

    /* Run at end of World ...
     * Performs side effects then returns: "[JOHN_DOE placed order 42 | Validated: 99.99]"
     */
    val result: String = pipeline.unsafeRun(())
  }

  test(
    ">> operator should execute pipelines in sequence regardless of output"
  ) {
    var executionOrder = List[String]()

    val p1 = Pipeline[Unit, Int]((u: Unit) => {
      executionOrder = executionOrder :+ "p1"
      42
    })

    val p2 = Pipeline[Unit, String]((u: Unit) => {
      executionOrder = executionOrder :+ "p2"
      "hello"
    })

    val p3 = Pipeline[Unit, Boolean]((u: Unit) => {
      executionOrder = executionOrder :+ "p3"
      true
    })

    val sequentialPipeline = p1 >> p2 >> p3
    val result = sequentialPipeline.unsafeRun(())

    assertEquals(result, true)

    assertEquals(executionOrder, List("p1", "p2", "p3"))
  }
}

/*
 digraph ETL {
  //rankdir=LR;
  node [shape=box, style=rounded, fontname="Helvetica", fontsize=12];
  edge [fontname="Helvetica", fontsize=10];

  {
    node [fillcolor="#e1f5fe", style="filled,rounded"]
    s1; s2; s3;
  }

  {
    node [fillcolor="#fff3e0", style="filled,rounded"]
    D; E; F;
  }

  {
    node [fillcolor="#f9fbe7", style="filled,rounded"]
    w1; w2; w3;
  }

  R [fillcolor="#dcedc8", style="filled,rounded"];

  s1 -> D;
  s2 -> D;
  s3 -> E;
  D -> F;
  E -> F;
  F -> w1;
  F -> w2;
  F -> w3;
  w1 -> R;

  {rank=same; s1; s2; s3}
  {rank=same; w1; w2; w3}
 }
 */
