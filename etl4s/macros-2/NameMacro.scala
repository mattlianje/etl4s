package etl4s

import scala.reflect.macros.blackbox

/**
 * Compile-time capture of the enclosing `val`/`def` name for Scala 2 (the
 * `sourcecode.Name` trick), used to name a [[Node]] leaf: `val parse = Node(...)`
 * -> "parse"
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
    def find(s: Symbol): Symbol =
      if (s == NoSymbol) NoSymbol
      else if (usable(s)) s
      else find(s.owner)

    val sym  = find(c.internal.enclosingOwner)
    val n    = if (sym == NoSymbol) "???" else clean(sym.name.decodedName.toString)
    val full = if (sym == NoSymbol) "" else sym.fullName
    c.Expr[Nothing](q"_root_.etl4s.Name($n, $full)")
  }
}
