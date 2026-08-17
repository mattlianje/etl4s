package etl4s

/** JVM-specific platform implementations */
object Platform {

  /** Sleep the current thread for the specified milliseconds */
  def sleep(millis: Long): Unit = Thread.sleep(millis)
}
