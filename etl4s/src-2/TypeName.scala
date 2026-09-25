package etl4s

import scala.language.experimental.macros

/**
 * A human-readable name for a type `A`, captured at COMPILE time and carried as
 * a plain value so the reified [[Node]] graph can print `String => Int` at runtime
 *
 * Auto-derives on demand via [[TypeNameMacro]]: `implicitly[TypeName[String]].show == "String"`.
 */
final case class TypeName[A](show: String)

object TypeName {
  implicit def derive[A]: TypeName[A] = macro TypeNameMacro.derive[A]
}
