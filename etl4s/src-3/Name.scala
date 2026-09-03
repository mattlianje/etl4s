package etl4s

import scala.quoted.*

/**
 * The binding name of a [[Node]], captured at compile time from the enclosing val/def
 *
 * @param value    Short binding name: `val parse = Node(...)` -> "parse"
 * @param fullName Fully-qualified path of the binding
 */
final case class Name(value: String, fullName: String = "")

object Name {
  inline given derive: Name = ${ deriveImpl }

  private def deriveImpl(using Quotes): Expr[Name] = {
    import quotes.reflect.*
    def clean(n: String): String     = n.stripSuffix("$").trim
    def usable(sym: Symbol): Boolean =
      (sym.isValDef || sym.isDefDef) && !sym.flags.is(Flags.Synthetic) && {
        val n = clean(sym.name)
        n.nonEmpty && n != "macro" && !n.startsWith("$") && !n.startsWith("<")
      }
    def find(sym: Symbol): Symbol =
      if sym.isNoSymbol then sym
      else if usable(sym) then sym
      else find(sym.owner)
    val sym   = find(Symbol.spliceOwner)
    val short = if sym.isNoSymbol then "???" else clean(sym.name)
    /* Strip module objects with a trailing $ in the fullName */
    val fullName =
      if sym.isNoSymbol then ""
      else sym.fullName.replace("$.", ".").stripSuffix("$")
    '{ Name(${ Expr(short) }, ${ Expr(fullName) }) }
  }
}
