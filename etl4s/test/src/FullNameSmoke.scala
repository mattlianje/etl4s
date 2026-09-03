package etl4s

/** Fixtures at a stable, top-level location so the captured FQ path is deterministic. */
object FullNameFixture {
  val doubler = Node[Int, Int](_ * 2)
  val inc     = Node[Int, Int](_ + 1)
  val show    = Node[Int, String](_.toString)
  val cfgNode = Node.requires[Int, Int, Int](c => a => a + c)

  val pipeline = doubler ~> inc ~> show
}

class FullNameSpec extends munit.FunSuite {

  test("leaf nodes capture the fully-qualified binding path") {
    assertEquals(FullNameFixture.doubler.getName, Some("doubler"))
    assertEquals(FullNameFixture.doubler.getFullName, Some("etl4s.FullNameFixture.doubler"))
  }

  test("readers capture the fully-qualified binding path (before providing config)") {
    assertEquals(FullNameFixture.cfgNode.getName, Some("cfgNode"))
    assertEquals(FullNameFixture.cfgNode.getFullName, Some("etl4s.FullNameFixture.cfgNode"))
    assertEquals(
      FullNameFixture.cfgNode.provideContext(1).getFullName,
      Some("etl4s.FullNameFixture.cfgNode")
    )
  }

  test("composite nodes have no single name of their own") {
    val p = FullNameFixture.doubler ~> FullNameFixture.doubler
    assertEquals(p.getName, None)
    assertEquals(p.getFullName, None)
  }

  test("walking a pipeline's AST yields the FQ path of every leaf, in order") {
    val fqns = FullNameFixture.pipeline.stages.map(_.fullName)
    assertEquals(
      fqns,
      List(
        "etl4s.FullNameFixture.doubler",
        "etl4s.FullNameFixture.inc",
        "etl4s.FullNameFixture.show"
      )
    )
  }
}
