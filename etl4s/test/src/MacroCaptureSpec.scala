package etl4s

/** Fixtures at a stable, top-level location so the captured FQ path is deterministic. */
object FullNameFixture {
  val doubler = Node[Int, Int](_ * 2)
  val inc     = Node[Int, Int](_ + 1)
  val show    = Node[Int, String](_.toString)
  val cfgNode = Node.requires[Int, Int, Int](c => a => a + c)

  val pipeline = doubler ~> inc ~> show
}

class MacroCaptureSpec extends munit.FunSuite {

  test("leaf nodes capture the fully-qualified binding path") {
    assertEquals(FullNameFixture.doubler.getName, Some("doubler"))
    assertEquals(FullNameFixture.doubler.getFullName, Some("etl4s.FullNameFixture.doubler"))
  }

  test("readers capture the fully-qualified binding path (before providing config)") {
    assertEquals(FullNameFixture.cfgNode.getName, Some("cfgNode"))
    assertEquals(FullNameFixture.cfgNode.getFullName, Some("etl4s.FullNameFixture.cfgNode"))
    assertEquals(
      FullNameFixture.cfgNode.provideContext(1).getFullName,
      Some("etl4s.FullNameFixture.cfgNode")
    )
  }

  test("composite nodes have no single name of their own") {
    val p = FullNameFixture.doubler ~> FullNameFixture.doubler
    assertEquals(p.getName, None)
    assertEquals(p.getFullName, None)
  }

  test("walking a pipeline's AST yields the FQ path of every leaf, in order") {
    val fqns = FullNameFixture.pipeline.stages.map(_.fullName)
    assertEquals(
      fqns,
      List(
        "etl4s.FullNameFixture.doubler",
        "etl4s.FullNameFixture.inc",
        "etl4s.FullNameFixture.show"
      )
    )
  }

  test("Node.If captures predicate source text in mermaid labels") {
    val parse = Node[String, Int](_.trim.toInt)
    val neg   = Node[Int, String](i => s"negative:$i")
    val zero  = Node[Int, String](_ => "zero")
    val pos   = Node[Int, String](i => s"positive:$i")

    val directSrc = Predicate.fromFn[Int](_ < 0).source
    assert(directSrc.nonEmpty, s"Predicate.fromFn returned empty source (got '$directSrc')")

    val p = parse
      .If(_ < 0)(neg)
      .ElseIf(_ == 0)(zero)
      .Else(pos)

    val m = p.toMermaid
    assert(m.contains("\"_ < 0\""), s"missing '_ < 0' label:\n$m")
    assert(m.contains("\"_ == 0\""), s"missing '_ == 0' label:\n$m")
    assert(m.contains("\"else\""), s"missing 'else' label:\n$m")
  }

  test("Node.If captures predicate source text in dot labels") {
    val parse = Node[String, Int](_.trim.toInt)
    val neg   = Node[Int, String](i => s"negative:$i")
    val pos   = Node[Int, String](i => s"positive:$i")

    val d = parse.If(_ < 0)(neg).Else(pos).toDot
    assert(d.contains("\"_ < 0\""), s"missing '_ < 0' in dot:\n$d")
    assert(d.contains("\"else\""), s"missing 'else' in dot:\n$d")
  }

  test("top-level If captures predicate source text") {
    val small = Node[Int, String](i => s"small:$i")
    val big   = Node[Int, String](i => s"big:$i")
    val p     = If[Int](_ < 10)(small).Else(big)

    val m = p.toMermaid
    assert(m.contains("\"_ < 10\""), s"missing '_ < 10':\n$m")
  }

  test("Predicate.source is populated directly (macro sanity)") {
    val a: Predicate[Int]    = Predicate.fromFn[Int](_ > 100)
    val b: Predicate[String] = Predicate.fromFn[String](_.isEmpty)
    assertEquals(a.source, "_ > 100")
    assertEquals(b.source, "_.isEmpty")
  }
}
