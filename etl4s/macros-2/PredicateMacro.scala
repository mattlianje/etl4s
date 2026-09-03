package etl4s

import scala.reflect.macros.blackbox

/**
 * Compile-time capture of a predicate's source text for Scala 2
 */
object PredicateMacro {
  def fromFn[B: c.WeakTypeTag](c: blackbox.Context)(f: c.Expr[B => Boolean]): c.Expr[Nothing] = {
    import c.universe._

    val bodyPos = f.tree match {
      case Function(_, body) if body != null && body.pos != null && body.pos.isRange => body.pos
      case _                                                                         => null
    }
    val outerPos = f.tree.pos
    val pos      = if (outerPos != null && outerPos.isRange) outerPos else bodyPos

    val src: String =
      if (pos != null && pos.isRange) {
        val content = pos.source.content
        val end     = math.min(content.length, pos.end)
        var s       = math.max(0, pos.start)
        var w       = s
        while (w > 0 && content(w - 1).isWhitespace) w -= 1
        if (w > 0 && content(w - 1) == '_') s = w - 1
        if (end > s) new String(content.slice(s, end)).trim else ""
      } else {
        f.tree match {
          case Function(vparams, body) if vparams.exists(_.mods.hasFlag(Flag.SYNTHETIC)) =>
            try {
              val rendered = showCode(body)
              val subbed   = vparams.foldLeft(rendered) { (acc, vp) =>
                acc.replace(vp.name.decodedName.toString, "_")
              }
              if (subbed.endsWith("()")) subbed.dropRight(2) else subbed
            } catch { case _: Throwable => "" }
          case _ =>
            try showCode(f.tree)
            catch { case _: Throwable => "" }
        }
      }

    val tpe = weakTypeOf[B]
    c.Expr[Nothing](q"_root_.etl4s.Predicate[$tpe]($f, $src)")
  }
}
