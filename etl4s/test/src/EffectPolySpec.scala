package etl4s

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Try, Success, Failure}

class EffectPolySpec extends munit.FunSuite {

  val parse = Node[String, Int](_.trim.toInt)
  val inc   = Node[Int, Int](_ + 1)
  val neg   = Node[Int, Int](-_)

  test("as[Id].unsafeRun matches the synchronous default unsafeRun") {
    val p = parse ~> inc
    assertEquals(p.compile[Id].unsafeRun("41"), 42)
    assertEquals(p.compile[Id].unsafeRun("41"), p.unsafeRun("41"))
  }

  test("as[Try].unsafeRun captures success and step failures without throwing") {
    assertEquals((parse ~> inc).compile[Try].unsafeRun("41"), Success(42))
    (parse.compile[Try].unsafeRun("oops")) match {
      case Failure(_: NumberFormatException) => ()
      case other                             => fail(s"expected Failure(NFE), got $other")
    }
  }

  test("as[Future].unsafeRun yields a Future of the result") {
    val f: Future[Int] = (parse ~> inc).compile[Future].unsafeRun("41")
    f.map(assertEquals(_, 42))
  }

  test("onFailure recovers under an effect (Try + Future)") {
    val boom = Node[String, Int](_ => throw new RuntimeException("boom")).onFailure(_ => -1)
    assertEquals(boom.compile[Try].unsafeRun("x"), Success(-1))
    boom.compile[Future].unsafeRun("x").map(assertEquals(_, -1))
  }

  test("withRetry succeeds after transient failures under an effect") {
    var n     = 0
    val flaky = Node[String, Int] { _ =>
      n += 1; if (n < 3) throw new RuntimeException("transient") else 99
    }.withRetry(maxAttempts = 5, initialDelayMs = 1)
    assertEquals(flaky.compile[Try].unsafeRun("g"), Success(99))
  }

  test("concurrent &> runs under Future and returns the tuple") {
    val g: Node[String, (Int, Int)] = parse ~> (inc &> neg)
    g.compile[Future].unsafeRun("10").map(assertEquals(_, (11, -10)))
  }

  test("conditionals fold through the effect interpreter") {
    val small = Node[Int, String](n => s"small:$n")
    val big   = Node[Int, String](n => s"big:$n")
    val p     = parse ~> If[Int](_ < 10)(small).Else(big)
    assertEquals(p.compile[Try].unsafeRun("3"), Success("small:3"))
    assertEquals(p.compile[Id].unsafeRun("42"), "big:42")
  }

  test("users can add their own effect with a single instance") {
    final case class Box[A](value: A)
    implicit val boxEffect: Effect[Box] = new Effect[Box] {
      def pure[A](a: A): Box[A]                                             = Box(a)
      def delay[A](thunk: => A): Box[A]                                     = Box(thunk)
      def flatMap[A, B](fa: Box[A])(f: A => Box[B]): Box[B]                 = f(fa.value)
      def handleErrorWith[A](fa: => Box[A])(h: Throwable => Box[A]): Box[A] =
        try fa
        catch { case t: Throwable => h(t) }
    }

    val p: Node[String, (Int, Int)] = parse ~> (inc &> neg)
    assertEquals(p.compile[Box].unsafeRun("10"), Box((11, -10)))
  }
}
