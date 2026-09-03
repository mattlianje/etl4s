package etl4s

class RenderDispatchSpec extends munit.FunSuite {

  test("a single node's toMermaid / toDot render its structural stage graph") {
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
    assert(m.contains("(( )):::anchor"), m)

    val d = g.toDot
    assert(d.startsWith("digraph G {"), d)
    assert(d.contains("rankdir=LR;"), d)
  }

  test("a single Reader's toMermaid / toDot render structure before context") {
    case class Cfg(rate: Double)
    object Jobs extends Context[Cfg] {
      val parse    = Context.Extract[String, Double] { _ => s => s.trim.toDouble }
      val applyTax = Context.Transform[Double, Double] { c => a => a * (1 + c.rate) }
    }
    import Jobs._

    val pipeline = parse ~> applyTax

    val m = pipeline.toMermaid
    assert(m.startsWith("flowchart LR"), m)
    assert(m.contains("parse"), m)
    assert(pipeline.toDot.startsWith("digraph G {"), pipeline.toDot)
  }

  test("a Seq of nodes renders the declared data-lineage graph instead") {
    val a = Node[String, String](identity)
      .lineage(name = "A", inputs = List("s1"), outputs = List("s2"))
    val b = Node[String, String](identity)
      .lineage(name = "B", inputs = List("s2"), outputs = List("s3"))

    val m = Seq(a, b).toMermaid
    assert(m.startsWith("graph LR"), m)
    assert(m.contains("A"), m)
    assert(m.contains("B"), m)

    val d = Seq(a, b).toDot
    assert(d.startsWith("digraph"), d)

    val j = Seq(a, b).toJson
    assert(j.contains("\"A\""), j)
    assert(j.contains("\"B\""), j)
  }
}
