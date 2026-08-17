package etl4s

/**
 * Marker trait for validation checks that can be used in Reader.ensure()
 */
sealed trait ValidationCheck[T, A] {
  def toCurried: T => A => Option[String]
}

/**
 * Context-aware validation check (already curried)
 */
case class CurriedCheck[T, A](f: T => A => Option[String]) extends ValidationCheck[T, A] {
  def toCurried: T => A => Option[String] = f
}

/**
 * Plain validation check (will be lifted to ignore context)
 */
case class PlainCheck[T, A](f: A => Option[String]) extends ValidationCheck[T, A] {
  def toCurried: T => A => Option[String] = _ => f
}

/**
 * Exception thrown when validation fails.
 */
class ValidationException(message: String) extends RuntimeException(message)
