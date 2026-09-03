package etl4s

import scala.concurrent.ExecutionContext.Implicits.global

/* Tests that require real timing/blocking - JVM only */
class TimingSpecs extends munit.FunSuite {

  test("&> runs concurrently under as[Future]") {
    import scala.concurrent.{Future, Await}
    import scala.concurrent.duration._

    var started1, started2 = 0L

    val slow1 = Node[Unit, String] { _ =>
      started1 = System.currentTimeMillis()
      Thread.sleep(100)
      "result1"
    }

    val slow2 = Node[Unit, Int] { _ =>
      started2 = System.currentTimeMillis()
      Thread.sleep(100)
      42
    }

    // Concurrency is realized by the effect runner, not the sync path.
    val combined = Await.result((slow1 &> slow2).compile[Future].unsafeRun(()), 2.seconds)

    assertEquals(combined._1, "result1")
    assertEquals(combined._2, 42)
    assert(
      Math.abs(started1 - started2) < 50,
      s"Tasks should start near-simultaneously, but started ${Math.abs(started1 - started2)}ms apart"
    )
  }

  test("*> runs branches concurrently under Future") {
    import scala.concurrent.{Future, Await}
    import scala.concurrent.duration._

    val slowL = Node[Int, Int] { n => Thread.sleep(150); n + 1 }
    val slowR = Node[Int, Int] { n => Thread.sleep(150); n * 2 }
    val block = slowL *> slowR

    val start = System.currentTimeMillis()
    val res   = Await.result(block.compile[Future].unsafeRun((10, 10)), 2.seconds)
    val ms    = System.currentTimeMillis() - start

    assertEquals(res, (11, 20))
    assert(ms < 250, s"expected concurrent (<250ms), took ${ms}ms")
  }

  test("plain unsafeRun runs &> sequentially (the Id model)") {
    var started1, started2 = 0L

    val slow1 = Node[Unit, String] { _ =>
      started1 = System.currentTimeMillis(); Thread.sleep(100); "result1"
    }
    val slow2 = Node[Unit, Int] { _ =>
      started2 = System.currentTimeMillis(); Thread.sleep(100); 42
    }

    val combined = (slow1 &> slow2).unsafeRun(())

    // Same result as concurrent, but branches run in order — no threads.
    assertEquals(combined, ("result1", 42))
    assert(
      started2 - started1 >= 100,
      s"Sync &> should run left-then-right, but started only ${started2 - started1}ms apart"
    )
  }

  test("eachPar runs a group concurrently under compile[Future]") {
    import scala.concurrent.{Future, Await}
    import scala.concurrent.duration._

    // Four 100ms elements in one group of 4: concurrent ~100ms, sequential ~400ms.
    val src  = Node[Unit, List[Int]](_ => (1 to 4).toList)
    val slow = Node[Int, Int] { n => Thread.sleep(100); n * 10 }
    val p    = src ~> eachPar(4)(slow)

    val start   = System.currentTimeMillis()
    val out     = Await.result(p.compile[Future].unsafeRun(()), 2.seconds)
    val elapsed = System.currentTimeMillis() - start

    assertEquals(out, List(10, 20, 30, 40))
    assert(elapsed < 300, s"group of 4 should run concurrently (~100ms), took ${elapsed}ms")
  }

  test("plain eachPar runs sequentially (the Id model)") {
    val src  = Node[Unit, List[Int]](_ => (1 to 4).toList)
    val slow = Node[Int, Int] { n => Thread.sleep(50); n * 10 }
    val p    = src ~> eachPar(4)(slow)

    val start   = System.currentTimeMillis()
    val out     = p.unsafeRun(())
    val elapsed = System.currentTimeMillis() - start

    assertEquals(out, List(10, 20, 30, 40))
    assert(elapsed >= 200, s"sync eachPar should run in order (~200ms), took ${elapsed}ms")
  }

  test("ensurePar runs a stage's checks concurrently under compile[Future]") {
    import scala.concurrent.{Future, Await}
    import scala.concurrent.duration._

    // Four 100ms checks: concurrent ~100ms, sequential ~400ms.
    val slowCheck: Int => Option[String] = _ => { Thread.sleep(100); None }
    val node                             = Node[Int, String](n => s"v$n")
      .ensurePar(input = Seq(slowCheck, slowCheck, slowCheck, slowCheck))

    val start   = System.currentTimeMillis()
    val out     = Await.result(node.compile[Future].unsafeRun(1), 2.seconds)
    val elapsed = System.currentTimeMillis() - start

    assertEquals(out, "v1")
    assert(elapsed < 300, s"ensurePar checks should run concurrently (~100ms), took ${elapsed}ms")
  }

  test("plain ensurePar runs checks sequentially (the Id model)") {
    val slowCheck: Int => Option[String] = _ => { Thread.sleep(80); None }
    val node                             = Node[Int, String](n => s"v$n")
      .ensurePar(input = Seq(slowCheck, slowCheck))

    val start   = System.currentTimeMillis()
    val out     = node.unsafeRun(1)
    val elapsed = System.currentTimeMillis() - start

    assertEquals(out, "v1")
    assert(elapsed >= 160, s"sync ensurePar should run checks in order (~160ms), took ${elapsed}ms")
  }

  test("unsafeRunTrace measures execution time accurately") {
    val sleepDuration = 100
    val sleepNode     = Node[Unit, Unit] { _ =>
      Thread.sleep(sleepDuration)
    }
    val insights    = sleepNode.unsafeRunTrace(())
    val elapsedTime = insights.timeElapsedMillis
    assert(
      elapsedTime >= sleepDuration,
      s"Elapsed time ($elapsedTime ms) should be at least $sleepDuration ms"
    )
    assert(
      elapsedTime < sleepDuration + 50,
      s"Elapsed time ($elapsedTime ms) should not be much longer than $sleepDuration ms"
    )
  }

  test("withRetry delays between attempts") {
    var attempts  = List.empty[Long]
    val failTwice = Node[Unit, String] { _ =>
      attempts = attempts :+ System.currentTimeMillis()
      if (attempts.size < 3) throw new RuntimeException("fail")
      "success"
    }

    val result = failTwice.withRetry(maxAttempts = 3, initialDelayMs = 50).unsafeRun(())

    assertEquals(result, "success")
    assertEquals(attempts.size, 3)

    // Check delays between attempts
    val delay1 = attempts(1) - attempts(0)
    val delay2 = attempts(2) - attempts(1)
    assert(delay1 >= 50, s"First delay ($delay1 ms) should be at least 50ms")
    assert(delay2 >= 100, s"Second delay ($delay2 ms) should be at least 100ms (with backoff)")
  }
}
