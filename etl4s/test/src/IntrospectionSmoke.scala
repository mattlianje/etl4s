package etl4s

import scala.concurrent.ExecutionContext.Implicits.global

class NodeIntrospectionSpec extends munit.FunSuite {

  test("stages capture val-names and in/out type names in execution order") {
    val parse = Node[String, Int](_.trim.toInt)
    val inc   = Node[Int, Int](_ + 1)
    val neg   = Node[Int, Int](-_)
    val join  = Node[(Int, Int), String] { case (a, b) => s"$a & $b" }

    val pipeline = parse ~> (inc &> neg) ~> join

    assertEquals(pipeline.stages.map(_.name), List("parse", "inc", "neg", "join"))
    assertEquals(pipeline.stages.head.in, "String")
    assertEquals(pipeline.stages.head.out, "Int")
    assertEquals(pipeline.unsafeRun("10"), "11 & -10")
  }

  test("mermaid renders fan-out / fan-in for parallel composition") {
    val parse = Node[String, Int](_.trim.toInt)
    val inc   = Node[Int, Int](_ + 1)
    val neg   = Node[Int, Int](-_)
    val g     = parse ~> (inc & neg)

    val m = g.toMermaid
    assert(m.startsWith("flowchart LR"), m)
    assert(m.contains("""n3(( )):::junction"""), m)
    assert(m.contains("""n0 -->|"Int"| n3"""), m)
    assert(m.contains("""n3 -->|"Int"| n1"""), m)
    assert(m.contains("""n3 -->|"Int"| n2"""), m)
    assert(m.contains("""-->|"String"| n0"""), m)
    assert(m.contains("(( )):::anchor"), m)
    assert(m.contains("classDef junction"), m)
    assert(m.contains("classDef anchor"), m)
  }

  test("`&` and `*` both render as little dots; edges distinguish them") {
    val a      = Node[Int, String](_.toString)
    val b      = Node[Int, String](_.toString)
    val andMer = (a & b).toMermaid
    val andDot = (a & b).toDot
    assert(andMer.contains("""(( )):::junction"""), andMer)
    assert(andDot.contains("shape=point, width=0.12"), andDot)

    val c       = Node[String, Int](_.length)
    val d       = Node[Double, Int](_.toInt)
    val prodMer = (c * d).toMermaid
    val prodDot = (c * d).toDot
    // * : same little dot, but outgoing edges are tagged `._1: T` / `._2: U`
    assert(prodMer.contains("""(( )):::junction"""), prodMer)
    assert(prodMer.contains("""|"._1: String"|"""), prodMer)
    assert(prodMer.contains("""|"._2: Double"|"""), prodMer)
    assert(prodDot.contains("shape=point, width=0.12"), prodDot)
    assert(prodDot.contains("""label="._1: String""""), prodDot)
    assert(prodDot.contains("""label="._2: Double""""), prodDot)
  }

  test("structural toDot / toMermaid accept showTypes and direction options") {
    val extract = Node[String, Int](_.length)
    val load    = Node[Int, String](_.toString)
    val p       = extract ~> load

    assert(p.toMermaid.contains("""-->|"Int"|"""), p.toMermaid)
    assert(p.toMermaid.startsWith("flowchart LR"))
    assert(p.toDot.contains("rankdir=LR"))
    assert(p.toDot.contains("""label="Int""""), p.toDot)

    val nodeTypesMer = p.toMermaid(typesOnNodes = true)
    val nodeTypesDot = p.toDot(typesOnNodes = true)
    assert(nodeTypesMer.contains("String") && nodeTypesMer.contains("&rArr;"), nodeTypesMer)
    assert(nodeTypesDot.contains("String") && nodeTypesDot.contains("=>"), nodeTypesDot)

    val noTypesMer = p.toMermaid(showTypes = false)
    val noTypesDot = p.toDot(showTypes = false)
    assert(!noTypesMer.contains("&rArr;"), noTypesMer)
    assert(!noTypesMer.contains("|\"Int\"|"), noTypesMer)
    assert(!noTypesDot.contains("=>"), noTypesDot)
    assert(!noTypesDot.contains("""label="Int""""), noTypesDot)
    assert(noTypesMer.contains("extract"), noTypesMer)

    assert(p.toMermaid(direction = Direction.TB).startsWith("flowchart TB"))
    assert(p.toDot(direction = Direction.TB).contains("rankdir=TB"))

    val r: Reader[Int, Node[String, String]] =
      Reader(_ => Node[String, String](_.toUpperCase))
    assert(r.toMermaid(showTypes = false).startsWith("flowchart LR"))
  }

  test(".withName overrides the auto-captured leaf name") {
    val renamed = Node[Int, Int](_ * 2).withName("times-two")
    assertEquals(renamed.stages.head.name, "times-two")
  }

  test("flatMap collapses to a single <dynamic> stage past its source") {
    val parse = Node[String, Int](_.trim.toInt)
    val dyn   = parse.flatMap(n => Node[String, String](_ => "~" * n))
    assertEquals(dyn.stages.map(_.name), List("parse", "<dynamic>"))
    assertEquals(dyn.unsafeRun("3"), "~~~")
  }

  test("value/alias constructors capture the call-site val-name and concrete types") {
    val someE  = Extract(1)
    val loader = Load[Int, Unit](_ => ())
    val id     = Node.identity[Int]
    val const  = Node.pure[String, Int](7)

    assertEquals(someE.stages.head.name, "someE")
    assertEquals(someE.stages.head.in, "Any")
    assertEquals(someE.stages.head.out, "Int")

    assertEquals(loader.stages.head.name, "loader")
    assertEquals(id.stages.head.copy(fullName = ""), Node.StageInfo("id", "Int", "Int"))
    assertEquals(const.stages.head.copy(fullName = ""), Node.StageInfo("const", "String", "Int"))
  }

  test("conditionals reify: source + every branch are visible, fan-out is dashed") {
    val parse = Node[String, Int](_.trim.toInt)
    val small = Node[Int, String](n => s"small:$n")
    val big   = Node[Int, String](n => s"big:$n")

    val p = parse ~> If[Int](_ < 10)(small).Else(big)

    val names = p.stages.map(_.name)
    assert(names.contains("parse"), names.toString)
    assert(names.contains("small"), names.toString)
    assert(names.contains("big"), names.toString)

    assertEquals(p.unsafeRun("3"), "small:3")
    assertEquals(p.unsafeRun("42"), "big:42")

    val m = p.toMermaid
    assert(m.contains("-.->"), m)
    assert(m.contains("""{"?"}:::decision"""), m)
    assert(!m.contains("""["input"]"""), m)
    val pd = p.toDot
    assert(pd.contains("[style=dashed"), pd)
    assert(pd.contains("shape=diamond"), pd)
    assert(pd.contains("""label="?""""), pd)
    assert(!pd.contains("""label="input""""), pd)
  }

  test("`|` (Fanin) and `<|>` (OrElse) render a little join dot on exit") {
    val fromInt = Node[Int, String](i => s"int:$i")
    val fromStr = Node[String, String](s => s"str:$s")
    val merged  = fromInt | fromStr
    val mer     = merged.toMermaid
    val dot     = merged.toDot
    assert(mer.contains("""(( )):::junction"""), mer)
    assert(mer.contains("-.->"), mer)
    assert(dot.contains("shape=point, width=0.12"), dot)
    assert(dot.contains("[style=dashed"), dot)

    val primary  = Node[String, Int](_.toInt)
    val fallback = Node[String, Int](_ => 0)
    val safe     = primary <|> fallback
    val safeMer  = safe.toMermaid
    val safeDot  = safe.toDot
    assert(safeMer.contains("""(( )):::junction"""), safeMer)
    assert(safeMer.contains("-.->"), safeMer)
    assert(safeDot.contains("shape=point, width=0.12"), safeDot)
    assert(safeDot.contains("[style=dashed"), safeDot)
  }

  test("pass-through conditional (no Else) keeps unmatched input flowing") {
    val tag: Node[Int, Int] = If[Int](_ > 0)(Node[Int, Int](_ * 10))
    assertEquals(tag.unsafeRun(5), 50)
    assertEquals(tag.unsafeRun(-5), -5)
    assert(tag.stages.contains(Node.StageInfo("input", "Int", "Int")), tag.stages.toString)
  }

  test("config-driven pipeline is inspectable BEFORE providing config") {
    case class Cfg(taxRate: Double, currency: String)
    object Billing extends Context[Cfg] {
      val parse    = Context.Extract[String, Double] { _ => s => s.trim.toDouble }
      val applyTax = Context.Transform[Double, Double] { cfg => amt => amt * (1 + cfg.taxRate) }
      val format   = Context.Transform[Double, String] { cfg => t => s"$t ${cfg.currency}" }
    }
    import Billing._

    val pipeline = parse ~> applyTax ~> format

    assertEquals(pipeline.stages.map(_.name), List("parse", "applyTax", "format"))
    assertEquals(
      pipeline.stages.head.copy(fullName = ""),
      Node.StageInfo("parse", "String", "Double")
    )
    assertEquals(
      pipeline.stages.last.copy(fullName = ""),
      Node.StageInfo("format", "Double", "String")
    )

    val m = pipeline.toMermaid
    assert(m.startsWith("flowchart LR"), m)
    assert(pipeline.toDot.startsWith("digraph G {"), pipeline.toDot)

    assertEquals(pipeline.provideContext(Cfg(0.0, "EUR")).unsafeRun("100.5"), "100.5 EUR")
  }

  test("dot renders a Graphviz digraph with the same structure as mermaid") {
    val parse = Node[String, Int](_.trim.toInt)
    val inc   = Node[Int, Int](_ + 1)
    val neg   = Node[Int, Int](-_)
    val g     = parse ~> (inc & neg)

    val d = g.toDot
    assert(d.startsWith("digraph G {"), d)
    assert(d.contains("rankdir=LR;"), d)
    assert(d.contains("""n0 [label="parse"];"""), d)
    assert(d.contains("shape=point, width=0.12"), d)
    assert(d.contains("""n0 -> n3 [label="Int"];"""), d)
    assert(d.contains("""n3 -> n1 [label="Int"];"""), d)
    assert(d.contains("""n3 -> n2 [label="Int"];"""), d)
    assert(d.contains("shape=point, width=0.08"), d)
    assert(d.contains("""[label="String"]"""), d)
    assert(d.trim.endsWith("}"), d)
  }
}
