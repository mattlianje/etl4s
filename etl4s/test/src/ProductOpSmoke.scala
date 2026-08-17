package etl4s

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class ProductOpSpec extends munit.FunSuite {

  val parseName = Node[String, String](_.trim)
  val parseAge  = Node[Int, Int](_ + 1)
  val doubleIt  = Node[Double, Double](_ * 2)

  test("** groups two nodes with different inputs into a tupled block") {
    val both: Node[(String, Int), (String, Int)] = parseName ** parseAge
    assertEquals(both.unsafeRun(("  alice  ", 30)), ("alice", 31))
  }

  test("** feeds _._1 to left and _._2 to right") {
    val left  = Node[String, Int](_.length)
    val right = Node[Int, String](n => "x" * n)
    val block = left ** right
    assertEquals(block.unsafeRun(("abcd", 3)), (4, "xxx"))
  }

  test("chaining a ** b ** c flattens outputs but nests inputs") {
    val block                      = parseName ** parseAge ** doubleIt
    val out: (String, Int, Double) = block.unsafeRun((("  bob ", 9), 2.5))
    assertEquals(out, ("bob", 10, 5.0))
  }

  test("**> runs the two branches and returns the flat tuple") {
    val block: Node[(String, Int), (String, Int)] = parseName **> parseAge
    assertEquals(block.unsafeRun(("  cara ", 41)), ("cara", 42))
  }

  test("** works under an effect via compile[F].unsafeRun") {
    val block = parseName ** parseAge
    assertEquals(block.compile[Try].unsafeRun(("  dan ", 7)), scala.util.Success(("dan", 8)))
    block.compile[Future].unsafeRun(("  eve ", 1)).map(assertEquals(_, ("eve", 2)))
  }

  test("stages / mermaid / dot render both product branches") {
    val left  = Node[String, Int](_.length).withName("left")
    val right = Node[Int, String](_.toString).withName("right")
    val block = left ** right

    assertEquals(block.stages.map(_.name), List("left", "right"))

    val m = block.mermaid
    assert(m.startsWith("flowchart LR"), m)

    val d = block.dot
    assert(d.startsWith("digraph G {"), d)
    assert(d.contains("left"), d)
    assert(d.contains("right"), d)
  }
}
