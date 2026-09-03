package etl4s

class PredicateSourceSmoke extends munit.FunSuite {

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
