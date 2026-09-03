package etl4s

import scala.language.experimental.macros

/**
 * The binding name of a [[Node]], captured at COMPILE time from the enclosing
 * `val`/`def` (the sourcecode.Name trick): `val parse = Node(...)` -> "parse".
 * Falls back to "???" when there is no enclosing definition (e.g. an inline
 * node with no `val` and no `.withName`).
 */
final case class Name(value: String, fullName: String = "")

object Name {
  implicit def derive: Name = macro NameMacro.derive
}
