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

    val m = g.mermaid
    assert(m.startsWith("flowchart LR"), m)
    assert(m.contains("n0 --> n1"), m)
    assert(m.contains("n0 --> n2"), m)
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
    assertEquals(id.stages.head, Node.StageInfo("id", "Int", "Int"))
    assertEquals(const.stages.head, Node.StageInfo("const", "String", "Int"))
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

    val m = p.mermaid
    assert(m.contains("-.->"), m)
    // dot likewise
    assert(p.dot.contains("[style=dashed];"), p.dot)
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
    assertEquals(pipeline.stages.head, Node.StageInfo("parse", "String", "Double"))
    assertEquals(pipeline.stages.last, Node.StageInfo("format", "Double", "String"))

    val m = pipeline.mermaid
    assert(m.startsWith("flowchart LR"), m)
    assert(pipeline.dot.startsWith("digraph G {"), pipeline.dot)

    assertEquals(pipeline.provideContext(Cfg(0.0, "EUR")).unsafeRun("100.5"), "100.5 EUR")
  }

  test("dot renders a Graphviz digraph with the same structure as mermaid") {
    val parse = Node[String, Int](_.trim.toInt)
    val inc   = Node[Int, Int](_ + 1)
    val neg   = Node[Int, Int](-_)
    val g     = parse ~> (inc & neg)

    val d = g.dot
    assert(d.startsWith("digraph G {"), d)
    assert(d.contains("rankdir=LR;"), d)
    assert(d.contains("""n0 [label="parse\nString => Int"];"""), d)
    assert(d.contains("n0 -> n1;"), d)
    assert(d.contains("n0 -> n2;"), d)
    assert(d.trim.endsWith("}"), d)
  }
}
