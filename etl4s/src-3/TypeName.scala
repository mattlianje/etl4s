package etl4s

import scala.quoted.*

/**
 * A human-readable name for a type `A`, captured at compile time and carried as
 * a plain value so the reified [[Node]] graph can print `String => Int` at runtime
 *
 * Auto-derives on demand: `summon[TypeName[String]].show == "String"`
 */
final case class TypeName[A](show: String)

object TypeName {
  inline given derive[A]: TypeName[A] = ${ deriveImpl[A] }

  private def deriveImpl[A: Type](using Quotes): Expr[TypeName[A]] = {
    import quotes.reflect.*
    val name = TypeRepr.of[A].show(using Printer.TypeReprShortCode)
    '{ TypeName[A](${ Expr(name) }) }
  }
}
