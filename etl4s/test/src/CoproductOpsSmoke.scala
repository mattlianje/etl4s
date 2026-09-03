package etl4s

import scala.util.{Try, Success}

class CoproductOpsSpec extends munit.FunSuite {

  test("| fans in: routes an Either to the matching branch, merges output") {
    val fromInt = Node[Int, String](i => s"int:$i")
    val fromStr = Node[String, String](s => s"str:$s")
    val merged  = fromInt | fromStr

    assertEquals(merged.unsafeRun(Left(1)), "int:1")
    assertEquals(merged.unsafeRun(Right("hi")), "str:hi")
  }

  test("+ chooses: routes an Either through independent branches, keeps the Either") {
    val dbl = Node[Int, Int](_ * 2)
    val up  = Node[String, String](_.toUpperCase)
    val ch  = dbl + up

    assertEquals(ch.unsafeRun(Left(21)), Left(42))
    assertEquals(ch.unsafeRun(Right("hi")), Right("HI"))
  }

  test("<|> falls back to the alternative on failure (Id)") {
    val primary  = Node[String, Int](_.toInt)
    val fallback = Node[String, Int](_ => 0)
    val safe     = primary <|> fallback

    assertEquals(safe.unsafeRun("7"), 7)
    assertEquals(safe.unsafeRun("oops"), 0)
  }

  test("<|> re-runs the fallback on the ORIGINAL input, not a Throwable") {
    val primary  = Node[Int, Int](n => if (n > 0) n else throw new RuntimeException("neg"))
    val fallback = Node[Int, Int](_.abs)
    val safe     = primary <|> fallback

    assertEquals(safe.unsafeRun(-5), 5)
    assertEquals(safe.unsafeRun(9), 9)
  }

  test("<|> recovers under an effect via handleErrorWith") {
    val primary  = Node[String, Int](_.toInt)
    val fallback = Node[String, Int](_ => -1)
    val safe     = primary <|> fallback

    assertEquals(safe.compile[Try].unsafeRun("5"), Success(5))
    assertEquals(safe.compile[Try].unsafeRun("nope"), Success(-1))
  }

  test("coproduct ops compose with ~> and expose their leaves in stages") {
    val fromInt = Node[Int, String](i => s"i$i")
    val fromStr = Node[String, String](s => s"s$s")
    val tag     = Node[String, String](_ + "!")
    val p       = (fromInt | fromStr) ~> tag

    assertEquals(p.unsafeRun(Left(3)), "i3!")
    assertEquals(p.unsafeRun(Right("x")), "sx!")
    assertEquals(p.stages.map(_.name), List("fromInt", "fromStr", "tag"))
  }
}
