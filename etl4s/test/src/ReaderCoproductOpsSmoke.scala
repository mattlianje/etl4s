package etl4s

class ReaderCoproductOpsSpec extends munit.FunSuite {

  case class Cfg(factor: Int, label: String)

  test("Reader | fans in two context-aware branches") {
    object Jobs extends Context[Cfg] {
      val fromInt = Context.Extract[Int, String] { c => i => s"${c.label}:${i * c.factor}" }
      val fromStr = Context.Transform[String, String] { c => s => s"${c.label}:$s" }
    }
    import Jobs._

    val merged = fromInt | fromStr
    val run    = merged.provideContext(Cfg(2, "x"))

    assertEquals(run.unsafeRun(Left(5)), "x:10")
    assertEquals(run.unsafeRun(Right("hi")), "x:hi")
  }

  test("Reader + routes an Either through independent context-aware branches") {
    object Jobs extends Context[Cfg] {
      val dbl = Context.Extract[Int, Int] { c => i => i * c.factor }
      val up  = Context.Transform[String, String] { c => s => s"${c.label}-$s" }
    }
    import Jobs._

    val ch  = dbl + up
    val run = ch.provideContext(Cfg(3, "L"))

    assertEquals(run.unsafeRun(Left(4)), Left(12))
    assertEquals(run.unsafeRun(Right("hi")), Right("L-hi"))
  }

  test("Reader <|> falls back to the alternative when the primary throws") {
    object Jobs extends Context[Cfg] {
      val primary  = Context.Extract[String, Int] { _ => s => s.toInt }
      val fallback = Context.Transform[String, Int] { c => _ => c.factor }
    }
    import Jobs._

    val safe = primary <|> fallback
    val run  = safe.provideContext(Cfg(-1, "L"))

    assertEquals(run.unsafeRun("7"), 7)
    assertEquals(run.unsafeRun("nope"), -1)
  }

  test("Reader coproduct ops mix with a plain Node and stay inspectable") {
    object Jobs extends Context[Cfg] {
      val fromInt = Context.Extract[Int, String] { c => i => s"i${i * c.factor}" }
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
    object Jobs extends Context[Cfg] {
      val ctxStr = Context.Transform[String, String] { c => s => s"${c.label}:$s" }
      val ctxInt = Context.Extract[Int, Int] { c => i => i * c.factor }
      val safety = Context.Transform[String, Int] { c => _ => c.factor }
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
