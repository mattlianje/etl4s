package etl4s

import scala.language.experimental.macros

/**
 * A predicate `B => Boolean` carrying the source text of the expression that
 * produced it, captured at compile time from the call site
 */
final case class Predicate[-B](f: B => Boolean, source: String = "")

object Predicate {
  implicit def fromFn[B](f: B => Boolean): Predicate[B] = macro PredicateMacro.fromFn[B]
}
