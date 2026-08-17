package etl4s

import scala.reflect.macros.blackbox

/**
 * Compile-time capture of the enclosing `val`/`def` name for Scala 2 (the
 * `sourcecode.Name` trick), used to name a [[Node]] leaf: `val parse = Node(...)`
 * -> "parse". Walks the owner chain from the expansion site, skipping synthetic
 * / anonymous owners, and falls back to "???" when there is no usable binding.
 *
 * Lives in the macro module for the same reason as [[TypeNameMacro]]; builds a
 * tree referencing `_root_.etl4s.Name` by name, so no dependency on the library.
 */
object NameMacro {
  def derive(c: blackbox.Context): c.Expr[Nothing] = {
    import c.universe._

    def clean(n: String): String   = n.stripSuffix("$").trim
    def usable(s: Symbol): Boolean =
      s != NoSymbol && s.isTerm && !s.isSynthetic && {
        val t = s.asTerm
        val n = clean(s.name.decodedName.toString)
        (t.isVal || t.isVar || t.isMethod) &&
        n.nonEmpty && n != "macro" && !n.startsWith("$") && !n.startsWith("<")
      }
    def find(s: Symbol): String =
      if (s == NoSymbol) "???"
      else if (usable(s)) clean(s.name.decodedName.toString)
      else find(s.owner)

    val n = find(c.internal.enclosingOwner)
    c.Expr[Nothing](q"_root_.etl4s.Name($n)")
  }
}
