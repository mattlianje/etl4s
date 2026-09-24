package etl4s

import scala.util.{Try, Success}

class BatchSpec extends munit.FunSuite {

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

  val clean: Node[Int, Int]     = Node(_ + 1)
  val enrich: Node[Int, String] = Node(n => s"v$n")

  test("each / eachPar build with no ExecutionContext in scope") {
    val src: Node[Unit, List[Int]] = Node(_ => List(1, 2, 3))
    assertEquals((src ~> each(clean)).unsafeRun(()), List(2, 3, 4))
    assertEquals((src ~> eachPar(2)(clean)).unsafeRun(()), List(2, 3, 4))
  }

  test("sync eachPar is the Id model — order preserved, no threads") {
    val src: Node[Unit, List[Int]] = Node(_ => (1 to 10).toList)
    val out                        = (src ~> eachPar(4)(clean ~> enrich)).unsafeRun(())
    assertEquals(out, (2 to 11).map(n => s"v$n").toList)
  }

  test("compile[Id] matches the plain synchronous unsafeRun") {
    val p = Node[Unit, List[Int]](_ => List(10, 20)) ~> each(clean)
    assertEquals(p.compile[Id].unsafeRun(()), p.unsafeRun(()))
  }

  test("compile[Try] folds a batch, capturing element failures") {
    val src: Node[Unit, List[Int]] = Node(_ => List(1, 2, 3))
    val ok                         = src ~> eachPar(2)(clean)
    assertEquals(ok.compile[Try].unsafeRun(()), Success(List(2, 3, 4)))

    val boom = src ~> eachPar(2)(Node[Int, Int](n => if (n == 2) sys.error("boom") else n))
    assert(boom.compile[Try].unsafeRun(()).isFailure)
  }

  test("a reified batch is introspectable — inner step shows in stages") {
    val p     = Node[Unit, List[Int]](_ => Nil) ~> eachPar(3)(clean.withName("clean"))
    val names = p.stages.map(_.name)
    assert(names.contains("clean"), s"expected 'clean' among stages, got $names")
  }

  test("each preserves the concrete collection type through the fold") {
    val src: Node[Unit, Vector[Int]] = Node(_ => Vector(1, 2, 3))
    val out: Vector[Int]             = (src ~> each(clean)).compile[Id].unsafeRun(())
    assertEquals(out, Vector(2, 3, 4))
  }
}
