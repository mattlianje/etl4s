package etl4s

/** Scala 3-specific tests that use features not available in Scala 2 */
class Scala3Specs extends munit.FunSuite {

  test("Reader composition with unrelated types uses intersection") {
    trait DatabaseConfig { def connectionString: String }
    trait ApiConfig      { def apiKey: String           }

    val dbReader = Reader[DatabaseConfig, Node[String, String]] { cfg =>
      Node(input => s"DB[${cfg.connectionString}]: $input")
    }

    val apiReader = Reader[ApiConfig, Node[String, String]] { cfg =>
      Node(input => s"API[${cfg.apiKey}]: $input")
    }

    val pipeline = dbReader ~> apiReader

    val combined = new DatabaseConfig with ApiConfig {
      val connectionString = "jdbc:mysql://localhost"
      val apiKey           = "secret-key-123"
    }

    val result = pipeline.provide(combined).unsafeRun("test-data")
    assert(result.contains("DB[jdbc:mysql://localhost]"))
    assert(result.contains("API[secret-key-123]"))
  }

  test("Node conditional branching with heterogeneous output types (union)") {
    case class StringResult(value: String)
    case class IntResult(value: Int)
    case class DefaultResult(message: String)

    val source: Node[Int, Int] = Node((n: Int) => n)

    val stringBranch: Node[Int, StringResult]   = Node(n => StringResult(s"positive: $n"))
    val intBranch: Node[Int, IntResult]         = Node(n => IntResult(n * -1))
    val defaultBranch: Node[Int, DefaultResult] = Node(_ => DefaultResult("zero"))

    val conditional = source
      .If(_ > 0)(stringBranch)
      .ElseIf(_ < 0)(intBranch)
      .Else(defaultBranch)

    val result1 = conditional.unsafeRun(5)
    val result2 = conditional.unsafeRun(-3)
    val result3 = conditional.unsafeRun(0)

    result1 match {
      case r: StringResult => assertEquals(r.value, "positive: 5")
      case _               => fail("Expected StringResult")
    }

    result2 match {
      case r: IntResult => assertEquals(r.value, 3)
      case _            => fail("Expected IntResult")
    }

    result3 match {
      case r: DefaultResult => assertEquals(r.message, "zero")
      case _                => fail("Expected DefaultResult")
    }
  }

  test(
    "Reader conditional branching with heterogeneous configs (intersection) and outputs (union)"
  ) {
    trait DatabaseConfig { def dbUrl: String         }
    trait CacheConfig    { def cacheEnabled: Boolean }
    trait ApiConfig      { def apiKey: String        }

    case class DbResult(url: String, data: String)
    case class CacheResult(cached: Boolean, data: String)
    case class ApiResult(key: String, data: String)

    val source: Reader[DatabaseConfig, Node[String, String]] = Reader { cfg =>
      Node(input => input)
    }

    val dbBranch: Reader[DatabaseConfig, Node[String, DbResult]] = Reader { cfg =>
      Node(s => DbResult(cfg.dbUrl, s))
    }

    val cacheBranch: Reader[CacheConfig, Node[String, CacheResult]] = Reader { cfg =>
      Node(s => CacheResult(cfg.cacheEnabled, s))
    }

    val apiBranch: Reader[ApiConfig, Node[String, ApiResult]] = Reader { cfg =>
      Node(s => ApiResult(cfg.apiKey, s))
    }

    val conditional = source
      .If((cfg: DatabaseConfig) => (s: String) => s.startsWith("db:"))(dbBranch)
      .ElseIf((cfg: DatabaseConfig) => (s: String) => s.startsWith("cache:"))(cacheBranch)
      .Else(apiBranch)

    val config = new DatabaseConfig with CacheConfig with ApiConfig {
      val dbUrl        = "jdbc:postgres://localhost"
      val cacheEnabled = true
      val apiKey       = "secret-123"
    }

    val pipeline = conditional.provide(config)

    val result1 = pipeline.unsafeRun("db:users")
    val result2 = pipeline.unsafeRun("cache:session")
    val result3 = pipeline.unsafeRun("other:data")

    result1 match {
      case r: DbResult =>
        assertEquals(r.url, "jdbc:postgres://localhost")
        assert(r.data.contains("db:users"))
      case other => fail(s"Expected DbResult but got: $other (${other.getClass})")
    }

    result2 match {
      case r: CacheResult =>
        assertEquals(r.cached, true)
        assert(r.data.contains("cache:session"))
      case _ => fail("Expected CacheResult")
    }

    result3 match {
      case r: ApiResult =>
        assertEquals(r.key, "secret-123")
        assert(r.data.contains("other:data"))
      case _ => fail("Expected ApiResult")
    }
  }

  test("Reader conditional with mixed Node and Reader branches") {
    trait Config { def multiplier: Int }

    case class ReaderResult(value: Int)
    case class NodeResult(value: String)

    val source: Reader[Config, Node[Int, Int]] = Reader { cfg =>
      Node(_ * cfg.multiplier)
    }

    val readerBranch: Reader[Config, Node[Int, ReaderResult]] = Reader { cfg =>
      Node(n => ReaderResult(n + cfg.multiplier))
    }

    val nodeBranch: Node[Int, NodeResult] = Node(n => NodeResult(s"node: $n"))

    val conditional = source
      .If(cfg => n => n > 10)(readerBranch)
      .Else(nodeBranch)

    val config   = new Config { val multiplier = 2 }
    val pipeline = conditional.provide(config)

    val result1 = pipeline.unsafeRun(10)
    val result2 = pipeline.unsafeRun(3)

    result1 match {
      case r: ReaderResult => assertEquals(r.value, 22)
      case _               => fail("Expected ReaderResult")
    }

    result2 match {
      case r: NodeResult => assertEquals(r.value, "node: 6")
      case _             => fail("Expected NodeResult")
    }
  }

  test("Reader conditional with multiple elseIf") {
    case class Config(min: Int, max: Int)

    val source     = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
    val formatLow  = Reader[Config, Node[Int, String]] { _ => Node(n => s"too-low:$n") }
    val formatHigh = Reader[Config, Node[Int, String]] { _ => Node(n => s"too-high:$n") }
    val formatOk   = Reader[Config, Node[Int, String]] { _ => Node(n => s"ok:$n") }

    val classifier = source
      .If((cfg: Config) => (n: Int) => n < cfg.min)(formatLow)
      .ElseIf((cfg: Config) => (n: Int) => n > cfg.max)(formatHigh)
      .Else(formatOk)

    val config = Config(10, 100)
    assertEquals(classifier.provide(config).unsafeRun(5), "too-low:5")
    assertEquals(classifier.provide(config).unsafeRun(50), "ok:50")
    assertEquals(classifier.provide(config).unsafeRun(150), "too-high:150")
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
      .If((cfg: Config) => (n: Int) => n < 0)(toNegative)
      .ElseIf((cfg: Config) => (n: Int) => n == 0)(toZero)
      .Else(toPositive)

    val config = Config(2)
    assertEquals(pipeline.provide(config).unsafeRun(-5), "negative:-5")
    assertEquals(pipeline.provide(config).unsafeRun(0), "zero")
    assertEquals(pipeline.provide(config).unsafeRun(10), "positive:20")
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

    // Condition-first syntax works uniformly (same as Scala 2)
    val categorize = source
      .If((cfg: Config) => (u: User) => u.age < cfg.adultAge)(toMinor)
      .ElseIf((cfg: Config) => (u: User) => u.age < cfg.seniorAge)(toAdult)
      .Else(toSenior)

    val pipeline = extract ~> categorize
    val config   = Config(18, 65)

    assertEquals(pipeline.provide(config).unsafeRun("Alice,15"), ProcessedUser("Alice", "minor"))
    assertEquals(pipeline.provide(config).unsafeRun("Bob,30"), ProcessedUser("Bob", "adult"))
    assertEquals(
      pipeline.provide(config).unsafeRun("Charlie,70"),
      ProcessedUser("Charlie", "senior")
    )
  }

  test("& operator auto-flattens to flat tuples") {
    val getA: Node[Int, String]  = Node(_ => "a")
    val getB: Node[Int, Int]     = Node(_ => 1)
    val getC: Node[Int, Double]  = Node(_ => 2.0)
    val getD: Node[Int, Boolean] = Node(_ => true)

    val ab                      = getA & getB
    val abResult: (String, Int) = ab.unsafeRun(0)
    assertEquals(abResult, ("a", 1))

    val abc                              = getA & getB & getC
    val abcResult: (String, Int, Double) = abc.unsafeRun(0)
    assertEquals(abcResult, ("a", 1, 2.0))

    val abcd                                       = getA & getB & getC & getD
    val abcdResult: (String, Int, Double, Boolean) = abcd.unsafeRun(0)
    assertEquals(abcdResult, ("a", 1, 2.0, true))
  }

  test("&> operator auto-flattens with concurrent execution") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val getA: Node[Int, String] = Node(_ => "a")
    val getB: Node[Int, Int]    = Node(_ => 1)
    val getC: Node[Int, Double] = Node(_ => 2.0)

    val abc                              = getA &> getB &> getC
    val abcResult: (String, Int, Double) = abc.unsafeRun(0)
    assertEquals(abcResult, ("a", 1, 2.0))
  }

  test("Reader & auto-flattens with config intersection") {
    trait ConfigA { def a: String }
    trait ConfigB { def b: Int    }
    trait ConfigC { def c: Double }

    val readerA: Reader[ConfigA, Node[Unit, String]] = Reader(cfg => Node(_ => cfg.a))
    val readerB: Reader[ConfigB, Node[Unit, Int]]    = Reader(cfg => Node(_ => cfg.b))
    val readerC: Reader[ConfigC, Node[Unit, Double]] = Reader(cfg => Node(_ => cfg.c))

    val combined = readerA & readerB & readerC

    val config = new ConfigA with ConfigB with ConfigC {
      val a = "hello"
      val b = 42
      val c = 3.14
    }

    val result: (String, Int, Double) = combined.provide(config).unsafeRun(())
    assertEquals(result, ("hello", 42, 3.14))
  }

}
