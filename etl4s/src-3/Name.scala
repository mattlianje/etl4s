package etl4s

import scala.quoted.*

/**
 * The binding name of a [[Node]], captured at compile time from the enclosing `val`/`def`
 */
final case class Name(value: String)

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
    def find(sym: Symbol): String =
      if sym.isNoSymbol then "???"
      else if usable(sym) then clean(sym.name)
      else find(sym.owner)
    '{ Name(${ Expr(find(Symbol.spliceOwner)) }) }
  }
}
