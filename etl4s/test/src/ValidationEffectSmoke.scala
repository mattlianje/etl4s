package etl4s

import scala.util.{Try, Failure}

class ValidationEffectSpec extends munit.FunSuite {

  val base = Node[Int, String](n => s"v$n").withName("base")

  test("ensure / ensurePar build with no ExecutionContext in scope") {
    val e = base.ensure(input = Seq(x => if (x > 0) None else Some("positive")))
    val p = base.ensurePar(input = Seq(x => if (x > 0) None else Some("positive")))
    assertEquals(e.unsafeRun(5), "v5")
    assertEquals(p.unsafeRun(5), "v5")
  }

  test("sync validation throws ValidationException collecting every error") {
    val node = base.ensurePar(
      input = Seq(
        x => if (x > 0) None else Some("must be positive"),
        x => if (x % 2 == 0) None else Some("must be even")
      )
    )
    val ex = intercept[ValidationException](node.unsafeRun(-3))
    assert(ex.getMessage.contains("must be positive"), ex.getMessage)
    assert(ex.getMessage.contains("must be even"), ex.getMessage)
  }

  test("compile[Try] captures a validation failure instead of throwing") {
    val node = base.ensurePar(input = Seq(x => if (x > 0) None else Some("positive")))
    node.compile[Try].unsafeRun(-1) match {
      case Failure(_: ValidationException) => ()
      case other => fail(s"expected Failure(ValidationException), got $other")
    }
    assertEquals(node.compile[Try].unsafeRun(2).get, "v2")
  }

  test("output / change checks fold through the effect too") {
    val node = base.ensure(
      output = Seq(s => if (s.startsWith("v")) None else Some("bad prefix")),
      change = Seq { case (in, out) => if (out.contains(in.toString)) None else Some("mismatch") }
    )
    assertEquals(node.compile[Id].unsafeRun(7), "v7")
  }

  test("a validated node is introspectable — inner step shows in stages") {
    val node  = base.ensurePar(input = Seq(_ => None))
    val names = node.stages.map(_.name)
    assertEquals(names, List("base"))
  }
}
