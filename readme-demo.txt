import etl4s._

/* Define your building blocks */
val fiveExtract: Extract[Unit, Int]  = Extract(5)
val timesTwo:    Transform[Int, Int] = Transform(_ * 2)

/* Add retry logic */
val plusFive: Transform[Int, Int] = Transform {
  var attempts = 0
  (x: Int) =>
    attempts += 1
    if (attempts < 3) throw new Exception else x + 5
}.withRetry(maxAttempts = 3, initialDelayMs = 10)

/* Compose with `andThen` */
val timesTwoPlusFive: Transform[Int, Int]    = timesTwo `andThen` plusFive
val exclaim:          Transform[Int, String] = Transform(_.toString + "!")
val consoleLoad:      Load[String, Unit]     = Load(println(_))

/* Make blocks config-driven with `.requires` */
val dbLoad = Load[String, Unit].requires[String] { ctx => in =>
  println(s"Loaded to $ctx DB value: $in")
}

/* Stitch pipeline with ~> */
val pipeline =
  fiveExtract ~> timesTwoPlusFive ~> exclaim ~> (consoleLoad &> dbLoad)

/* Provide context, then run at end of World */
pipeline.provide("[SQLite]").unsafeRun(())

import etl4s._

/* Define your building blocks */
val fiveExtract:   Extract[Unit, Int]  = Extract(5)
val timesTwo:      Transform[Int, Int] = Transform(_ * 2)
val riskyPlusFive: Transform[Int, Int] = Transform {
  var attempts = 0
  (x: Int) =>
    attempts += 1
    if (attempts < 3) throw new Exception else x + 5
}

/* Compose with `andThen` */
val composedT: Transform[Int, Int] = timesTwo `andThen` plusFive

/* Add retry logic */
val timesTwoPlusFive: Transform[Int, Int]    = composedT
                                                  .withRetry(maxAttempts=3, initialDelayMs=10)
val exclaim:          Transform[Int, String] = Transform(_.toString + "!")
val consoleLoad:      Load[String, Unit]     = Load(println(_))

/* Make blocks config-driven with `.requires` */
val dbLoad = Load[String, Unit].requires[String] { dbType => x =>
  println(s"Loaded to $ctx DB value: $x")
}

/* Stitch pipeline with ~> */
val pipeline =
  fiveExtract ~> timesTwoPlusFive ~> exclaim ~> (consoleLoad &> dbLoad)

/* Provide context, then run at end of World */
pipeline.provide("[SQLite]").unsafeRun(())

