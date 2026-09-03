package etl4s

import scala.util.{Try, Success}

class BatchCollectionsSpec extends munit.FunSuite {

  val fetch: Node[Unit, List[String]] = Node(_ => List("1", "2", "oops", "4"))

  test("collectEach maps each element and drops the None results") {
    val parse    = Transform[String, Option[Int]](s => scala.util.Try(s.toInt).toOption)
    val pipeline = fetch ~> collectEach(parse)

    assertEquals(pipeline.unsafeRun(()), List(1, 2, 4))
  }

  test("collectEach preserves the concrete collection type") {
    val fetchV: Node[Unit, Vector[String]] = Node(_ => Vector("1", "x", "3"))
    val parse = Transform[String, Option[Int]]((s: String) => scala.util.Try(s.toInt).toOption)
    assertEquals((fetchV ~> collectEach(parse)).unsafeRun(()), Vector(1, 3))
  }

  test("collectEachPar runs concurrently under an effect, still drops Nones") {
    val parse = Transform[String, Option[Int]]((s: String) => scala.util.Try(s.toInt).toOption)
    val p     = fetch ~> collectEachPar(4)(parse)
    assertEquals(p.compile[Try].unsafeRun(()), Success(List(1, 2, 4)))
  }

  test("filterEach keeps elements where the predicate node holds") {
    val nums: Node[Unit, List[Int]] = Node(_ => List(1, 2, 3, 4, 5, 6))
    val isEven                      = Transform[Int, Boolean](_ % 2 == 0)
    assertEquals((nums ~> filterEach(isEven)).unsafeRun(()), List(2, 4, 6))
  }

  test("filterEachPar keeps matching elements under an effect") {
    val nums: Node[Unit, List[Int]] = Node(_ => List(1, 2, 3, 4, 5, 6))
    val big                         = Transform[Int, Boolean](_ > 3)
    assertEquals((nums ~> filterEachPar(3)(big)).compile[Try].unsafeRun(()), Success(List(4, 5, 6)))
  }

  test("collectEach keeps its inner step inspectable in stages") {
    val parse = Transform[String, Option[Int]]((s: String) => scala.util.Try(s.toInt).toOption)
      .withName("parse")
    val p = fetch ~> collectEach(parse)
    assert(p.stages.map(_.name).contains("parse"), p.stages.toString)
  }
}
