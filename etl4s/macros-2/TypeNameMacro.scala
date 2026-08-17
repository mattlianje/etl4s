package etl4s

import scala.reflect.macros.blackbox

/**
 * Compile-time derivation of a human-readable [[TypeName]] for Scala 2.
 *
 * In Scala 2 a def-macro's implementation must live in a different compilation
 * unit from its use... so this object is compiled as its own tiny module whose
 * classes are then bundled into the single `etl4s` jar (users still only need `import etl4s._`)
 */
object TypeNameMacro {
  def derive[A: c.WeakTypeTag](c: blackbox.Context): c.Expr[Nothing] = {
    import c.universe._

    def render(t: Type): String = {
      val dt = t.dealias
      dt match {
        case TypeRef(_, sym, args) =>
          val n = sym.name.decodedName.toString
          if (args.isEmpty) n
          else if (n.matches("Tuple\\d+")) args.map(render).mkString("(", ", ", ")")
          else n + args.map(render).mkString("[", ", ", "]")
        case other => other.toString
      }
    }

    val tpe  = weakTypeOf[A]
    val show = render(tpe)
    c.Expr[Nothing](q"_root_.etl4s.TypeName[$tpe]($show)")
  }
}
