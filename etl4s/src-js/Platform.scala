package etl4s

/** Scala.js-specific platform implementations */
object Platform {

  /** Sleep - on JS we can't block, so this is a no-op (immediate return) */
  def sleep(millis: Long): Unit = ()
}
