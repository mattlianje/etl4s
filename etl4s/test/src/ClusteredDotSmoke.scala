package etl4s

class ClusteredDotSmoke extends munit.FunSuite {

  test("toDotClustered draws `&` fan-out as a dashed compound cluster") {
    val parse    = Node[String, Int](_.trim.toInt)
    val subtotal = Node[Int, Double](_.toDouble).withName("subtotal")
    val shipping = Node[Int, Double](_ => 5.0).withName("shipping")
    val total    = Node[(Double, Double), Double] { case (a, b) => a + b }.withName("total")

    val d = (parse ~> (subtotal & shipping) ~> total).toDotClustered()

    assert(d.startsWith("digraph G {"), d)
    assert(d.contains("subgraph cluster_"), d)
    assert(d.contains("""label="&"; style=dashed; color=gray60;"""), d)
    assert(d.contains("""label="subtotal"""), d)
    assert(d.contains("""label="shipping"""), d)
    assert(!d.contains("lhead=cluster_"), d)
    assert(!d.contains("ltail=cluster_"), d)
    assert(!d.contains("compound=true"), d)
    assert(d.contains("""label="Int""""), d)
    assert(d.contains("""label="Double""""), d)
    assert(!d.contains("(Double, Double)"), d)
    assert(d.contains("""label="._1: Double""""), d)
    assert(d.contains("""label="._2: Double""""), d)
    assert(d.contains("shape=point"), d)
    assert(d.contains("""label="String""""), d)
    assert(!d.contains("shape=diamond"), d)
    assert(d.trim.endsWith("}"), d)

    val noParens = (parse ~> (subtotal & shipping) ~> total).toDotClustered
    assertEquals(noParens, d)
  }

  test("toDotClustered draws `?` conditional as a dashed cluster; predicate on node") {
    val d = Node[String, Double](_.trim.toDouble)
      .withName("total")
      .If(_ > 100)(Node[Double, String](_ => "a").withName("price"))
      .ElseIf(_ > 50)(Node[Double, String](_ => "b").withName("price"))
      .Else(Node[Double, String](_ => "c").withName("price"))
      .toDotClustered()

    assert(d.contains("""label="?"; style=dashed; color=gray60;"""), d)
    assert(d.contains("price (_ > 100)"), d)
    assert(d.contains("price (_ > 50)"), d)
    assert(d.contains("price (else)"), d)
    assert(d.contains("""label="Double""""), d)
    assert(d.contains("style=dashed"), d)
    assert(!d.contains("lhead=cluster_"), d)
  }

  test("toDotClustered distinguishes concurrent `&>` / `*>` from sequential") {
    val a    = Node[Int, Int](_ + 1).withName("a")
    val b    = Node[Int, Int](_ * 2).withName("b")
    val seq  = (a & b).toDotClustered()
    val conc = (a &> b).toDotClustered()
    assert(seq.contains("""label="&"; style=dashed"""), seq)
    assert(conc.contains("""label="&>"; style=dashed"""), conc)

    val c        = Node[Int, Int](_ + 1).withName("c")
    val e        = Node[String, Int](_.length).withName("e")
    val prodSeq  = (c * e).toDotClustered()
    val prodConc = (c *> e).toDotClustered()
    assert(prodSeq.contains("""label="*"; style=dashed"""), prodSeq)
    assert(prodConc.contains("""label="*>"; style=dashed"""), prodConc)
  }

  test("toDotClustered routes chained `|` fan-ins through a merge point, not an N×M mesh") {
    val fromInt  = Node[Int, Either[Int, String]](Left(_)).withName("fromInt")
    val fromStr  = Node[String, Either[Int, String]](Right(_)).withName("fromStr")
    val classify = fromInt | fromStr

    val netInt = Node[Int, String](i => s"i$i").withName("netInt")
    val netStr = Node[String, String](s => s"s$s").withName("netStr")
    val route  = netInt | netStr

    val d = (classify ~> route).toDotClustered()

    def idOf(p: String): String =
      """n(\d+) \[label="([^"]+)"\]""".r
        .findAllMatchIn(d)
        .collectFirst { case m if m.group(2).startsWith(p) => m.group(1) }
        .get
    val fromIntId = idOf("fromInt"); val fromStrId = idOf("fromStr")
    val netIntId  = idOf("netInt"); val netStrId   = idOf("netStr")

    def edge(a: String, b: String): Boolean =
      d.contains(s"n$a -> n$b;") || d.contains(s"n$a -> n$b [")

    // no direct branch-to-branch mesh
    assert(!edge(fromIntId, netIntId), d)
    assert(!edge(fromIntId, netStrId), d)
    assert(!edge(fromStrId, netIntId), d)
    assert(!edge(fromStrId, netStrId), d)

    // both upstream branches converge on a single node...
    val fromIntTgt = s"n$fromIntId -> n(\\d+)".r.findFirstMatchIn(d).get.group(1)
    val fromStrTgt = s"n$fromStrId -> n(\\d+)".r.findFirstMatchIn(d).get.group(1)
    assertEquals(fromIntTgt, fromStrTgt, d)

    // ...which is a `shape=point` merge that then feeds both downstream branches
    assert(d.contains(s"n$fromIntTgt [shape=point"), d)
    assert(edge(fromIntTgt, netIntId), d)
    assert(edge(fromIntTgt, netStrId), d)
  }

  test("toDotClustered dashes `?` branch outputs, like `|`/`orElse` (only one fires)") {
    val d = Node[String, Double](_.trim.toDouble)
      .withName("total")
      .If(_ > 100)(Node[Double, String](_ => "a").withName("hi"))
      .Else(Node[Double, String](_ => "b").withName("lo"))
      .toDotClustered()

    def idOf(p: String): String =
      """n(\d+) \[label="([^"]+)"\]""".r
        .findAllMatchIn(d)
        .collectFirst { case m if m.group(2).startsWith(p) => m.group(1) }
        .get

    val hiId  = idOf("hi"); val loId = idOf("lo")
    val hiOut = s"""n$hiId -> n\\d+ \\[[^\\]]*style=dashed""".r
    val loOut = s"""n$loId -> n\\d+ \\[[^\\]]*style=dashed""".r
    assert(hiOut.findFirstIn(d).isDefined, d)
    assert(loOut.findFirstIn(d).isDefined, d)
  }

  test("toDotClustered dashes the edges INTO a `?` select (only one branch is taken)") {
    val d = (Node[Int, String](i => s"v$i").withName("src") ~>
      If[String](_.length > 3)(Node[String, String](s => s"big $s").withName("big"))
        .Else(Node[String, String](s => s"sm $s").withName("small"))).toDotClustered()

    def idOf(p: String): String =
      """n(\d+) \[label="([^"]+)"\]""".r
        .findAllMatchIn(d)
        .collectFirst { case m if m.group(2).startsWith(p) => m.group(1) }
        .get
    val srcId = idOf("src"); val bigId = idOf("big"); val smallId = idOf("small")

    def dashedEdge(a: String, b: String): Boolean =
      s"""n$a -> n$b \\[[^\\]]*style=dashed""".r.findFirstIn(d).isDefined

    // src reaches each branch by a dashed arrow (routed: only one is taken)
    assert(dashedEdge(srcId, bigId), d)
    assert(dashedEdge(srcId, smallId), d)
  }

  test("toDotClustered keeps `<|>` primary input solid but dashes the fallback input") {
    val primary  = Node[String, Int](_.trim.toInt).withName("primary")
    val fallback = Node[String, Int](_ => 0).withName("fallback")
    val d        = (primary <|> fallback).toDotClustered()

    def idOf(p: String): String =
      """n(\d+) \[label="([^"]+)"\]""".r
        .findAllMatchIn(d)
        .collectFirst { case m if m.group(2).startsWith(p) => m.group(1) }
        .get
    val into = """n(\d+) -> n(\d+)""".r
      .findAllMatchIn(d)
      .map(m => m.group(2) -> m.group(1))
      .toMap
    val anchor = into(idOf("primary"))

    // shared anchor: primary edge solid (always runs), fallback edge dashed
    assert(d.contains(s"n$anchor -> n${idOf("primary")} [label="), d)
    assert(!d.contains(s"n$anchor -> n${idOf("primary")} [label=\"String\", style=dashed"), d)
    assert(
      s"""n$anchor -> n${idOf("fallback")} \\[[^\\]]*style=dashed""".r.findFirstIn(d).isDefined,
      d
    )
  }

  test("toDotClustered feeds broadcast blocks (`<|>`/`&`) from one shared input anchor") {
    def idOf(d: String, p: String): String =
      """n(\d+) \[label="([^"]+)"\]""".r
        .findAllMatchIn(d)
        .collectFirst { case m if m.group(2).startsWith(p) => m.group(1) }
        .get

    def sharedInput(d: String, lhs: String, rhs: String): Boolean = {
      val lId  = idOf(d, lhs); val rId = idOf(d, rhs)
      val into = """n(\d+) -> n(\d+)""".r
        .findAllMatchIn(d)
        .map(m => m.group(2) -> m.group(1))
        .toMap
      into.get(lId).exists(a => into.get(rId).contains(a) && d.contains(s"n$a [shape=point"))
    }

    val parse   = Node[String, Int](_.trim.toInt).withName("parse")
    val salvage = Node[String, Int](_ => 0).withName("salvage")
    val orD     = (parse <|> salvage).toDotClustered()
    assert(sharedInput(orD, "parse", "salvage"), orD)

    val aa   = Node[Int, Int](_ + 1).withName("aa")
    val bb   = Node[Int, Int](_ * 2).withName("bb")
    val parD = (aa & bb).toDotClustered()
    assert(sharedInput(parD, "aa", "bb"), parD)
  }

  test("toDotClustered `*` tags the tuple slots feeding each branch") {
    val src = Node[Int, (String, Double)](n => (n.toString, n.toDouble)).withName("src")
    val c   = Node[String, Int](_.length).withName("len")
    val e   = Node[Double, Int](_.toInt).withName("trunc")

    val d = (src ~> (c * e)).toDotClustered()
    assert(d.contains("""label="*"; style=dashed; color=gray60;"""), d)
    assert(d.contains("._1: String"), d)
    assert(d.contains("._2: Double"), d)
  }
}
