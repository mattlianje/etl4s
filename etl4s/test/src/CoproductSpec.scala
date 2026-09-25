package etl4s

import scala.util.{Try, Success}

class CoproductSpec extends munit.FunSuite {

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

  case class Cfg(factor: Int, label: String)

  test("Reader | fans in two context-aware branches") {
    object Jobs extends Etl4sCtx[Cfg] {
      val fromInt = Etl4sCtx.Extract[Int, String] { c => i => s"${c.label}:${i * c.factor}" }
      val fromStr = Etl4sCtx.Transform[String, String] { c => s => s"${c.label}:$s" }
    }
    import Jobs._

    val merged = fromInt | fromStr
    val run    = merged.provideContext(Cfg(2, "x"))

    assertEquals(run.unsafeRun(Left(5)), "x:10")
    assertEquals(run.unsafeRun(Right("hi")), "x:hi")
  }

  test("Reader + routes an Either through independent context-aware branches") {
    object Jobs extends Etl4sCtx[Cfg] {
      val dbl = Etl4sCtx.Extract[Int, Int] { c => i => i * c.factor }
      val up  = Etl4sCtx.Transform[String, String] { c => s => s"${c.label}-$s" }
    }
    import Jobs._

    val ch  = dbl + up
    val run = ch.provideContext(Cfg(3, "L"))

    assertEquals(run.unsafeRun(Left(4)), Left(12))
    assertEquals(run.unsafeRun(Right("hi")), Right("L-hi"))
  }

  test("Reader <|> falls back to the alternative when the primary throws") {
    object Jobs extends Etl4sCtx[Cfg] {
      val primary  = Etl4sCtx.Extract[String, Int] { _ => s => s.toInt }
      val fallback = Etl4sCtx.Transform[String, Int] { c => _ => c.factor }
    }
    import Jobs._

    val safe = primary <|> fallback
    val run  = safe.provideContext(Cfg(-1, "L"))

    assertEquals(run.unsafeRun("7"), 7)
    assertEquals(run.unsafeRun("nope"), -1)
  }

  test("Reader coproduct ops mix with a plain Node and stay inspectable") {
    object Jobs extends Etl4sCtx[Cfg] {
      val fromInt = Etl4sCtx.Extract[Int, String] { c => i => s"i${i * c.factor}" }
    }
    import Jobs._

    val fromStr: Node[String, String] = Node[String, String](s => s"s$s")
    val merged                        = fromInt | fromStr

    assertEquals(merged.stages.map(_.name), List("fromInt", "fromStr"))

    val run = merged.provideContext(Cfg(10, "x"))
    assertEquals(run.unsafeRun(Left(2)), "i20")
    assertEquals(run.unsafeRun(Right("z")), "sz")
  }

  test("plain Node ⊕ Reader works in both directions, like ~>") {
    object Jobs extends Etl4sCtx[Cfg] {
      val ctxStr = Etl4sCtx.Transform[String, String] { c => s => s"${c.label}:$s" }
      val ctxInt = Etl4sCtx.Extract[Int, Int] { c => i => i * c.factor }
      val safety = Etl4sCtx.Transform[String, Int] { c => _ => c.factor }
    }
    import Jobs._

    val plainInt: Node[Int, String] = Node[Int, String](i => s"i$i")
    val plainStr: Node[String, Int] = Node[String, Int](_.toInt)

    val fannedIn = plainInt | ctxStr
    val fi       = fannedIn.provideContext(Cfg(0, "L"))
    assertEquals(fi.unsafeRun(Left(3)), "i3")
    assertEquals(fi.unsafeRun(Right("z")), "L:z")

    val chosen = plainInt + ctxInt
    val ch     = chosen.provideContext(Cfg(4, "L"))
    assertEquals(ch.unsafeRun(Left(2)), Left("i2"))
    assertEquals(ch.unsafeRun(Right(5)), Right(20))

    val safe = plainStr <|> safety
    val s    = safe.provideContext(Cfg(-9, "L"))
    assertEquals(s.unsafeRun("7"), 7)
    assertEquals(s.unsafeRun("nope"), -9)
  }
}
