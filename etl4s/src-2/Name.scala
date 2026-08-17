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
 * The binding name of a [[Node]], captured at COMPILE time from the enclosing
 * `val`/`def` (the sourcecode.Name trick): `val parse = Node(...)` -> "parse".
 * Falls back to "???" when there is no enclosing definition (e.g. an inline
 * node with no `val` and no `.withName`).
 */
final case class Name(value: String)

object Name {
  implicit def derive: Name = macro NameMacro.derive
}
