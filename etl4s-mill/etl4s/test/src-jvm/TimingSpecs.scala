package etl4s

import scala.concurrent.ExecutionContext.Implicits.global

/** Tests that require real timing/blocking - JVM only */
class TimingSpecs extends munit.FunSuite {

  test("parallel execution with &> runs concurrently") {
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

    val combined = (slow1 &> slow2).unsafeRun(())

    assertEquals(combined._1, "result1")
    assertEquals(combined._2, 42)
    assert(
      Math.abs(started1 - started2) < 50,
      s"Tasks should start near-simultaneously, but started ${Math.abs(started1 - started2)}ms apart"
    )
  }

  test("unsafeRunTrace measures execution time accurately") {
    val sleepDuration = 100
    val sleepNode = Node[Unit, Unit] { _ =>
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
    var attempts = List.empty[Long]
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
