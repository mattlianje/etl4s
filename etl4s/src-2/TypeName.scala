/*
 * +==========================================================================+
 * |                                 etl4s                                    |
 * |                     Powerful, whiteboard-style ETL                       |
 * |                     Compatible with Scala 2.12/2.13                      |
 * |                                                                          |
 * | Copyright 2025 Matthieu Court (matthieu.court@protonmail.com)            |
 * | Apache License 2.0                                                       |
 * +==========================================================================+
 */

package etl4s

import scala.language.experimental.macros

/**
 * A human-readable name for a type `A`, captured at COMPILE time and carried as
 * a plain value so the reified [[Node]] graph can print `String => Int` at
 * runtime — long after the JVM has erased the real types.
 *
 * Auto-derives on demand via [[TypeNameMacro]]: `implicitly[TypeName[String]].show == "String"`.
 */
final case class TypeName[A](show: String)

object TypeName {
  implicit def derive[A]: TypeName[A] = macro TypeNameMacro.derive[A]
}
