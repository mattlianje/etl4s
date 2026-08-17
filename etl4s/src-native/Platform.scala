package etl4s

/** Scala Native-specific platform implementations (similar to JVM) */
object Platform {

  /** Sleep the current thread for the specified milliseconds */
  def sleep(millis: Long): Unit = Thread.sleep(millis)
}
