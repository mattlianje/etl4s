package etl4s

import scala.quoted.*

/**
 * A predicate `B => Boolean` carrying the source text of the expression that
 * produced it, captured at compile time from the call site.
 */
final case class Predicate[-B](f: B => Boolean, source: String = "")

object Predicate {

  inline def fromFn[B](inline f: B => Boolean): Predicate[B] =
    ${ deriveImpl('f) }

  private def deriveImpl[B: Type](f: Expr[B => Boolean])(using Quotes): Expr[Predicate[B]] = {
    import quotes.reflect.*
    val src = f.asTerm.pos.sourceCode.getOrElse("").trim
    '{ Predicate($f, ${ Expr(src) }) }
  }
}
