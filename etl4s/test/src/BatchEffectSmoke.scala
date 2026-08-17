package etl4s

import scala.util.{Try, Success}

class BatchEffectSpec extends munit.FunSuite {

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
