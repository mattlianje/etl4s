package etl4s

import scala.reflect.macros.blackbox

/** Macros for If/Else lambda extraction
  *
  * With Scala 2 implicit macro conversion will not drive underscore lambda
  * inference.
  *
  * Returns `c.Expr[Nothing]` (same pattern as [[NameMacro]]) so this module
  * doesn't need any etl4s types on its compile classpath.
  */
object ConditionalOpsMacros {

  private def captureSrc(c: blackbox.Context)(tree: c.Tree): String = {
    val pos = tree.pos
    if (pos != null && pos.isRange) {
      val content = pos.source.content
      val end     = math.min(content.length, pos.end)
      var s       = math.max(0, pos.start)
      var w       = s
      while (w > 0 && content(w - 1).isWhitespace) w -= 1
      if (w > 0 && content(w - 1) == '_') s = w - 1
      if (end > s) new String(content.slice(s, end)).trim else ""
    } else {
      try c.universe.showCode(tree)
      catch { case _: Throwable => "" }
    }
  }

  def nodeIf[A, B, C](c: blackbox.Context)(
    condition: c.Expr[Any]
  )(branch: c.Expr[Any]): c.Expr[Nothing] = {
    import c.universe._
    val src = captureSrc(c)(condition.tree)
    c.Expr[Nothing](q"""
      _root_.etl4s.PartialConditionalBuilder(
        ${c.prefix.tree}.node,
        _root_.scala.collection.immutable.List(
          (_root_.etl4s.Predicate($condition, $src), $branch)
        )
      )
    """)
  }

  def partialElseIf[A, B, C](c: blackbox.Context)(
    condition: c.Expr[Any]
  )(branch: c.Expr[Any]): c.Expr[Nothing] = {
    import c.universe._
    val src = captureSrc(c)(condition.tree)
    c.Expr[Nothing](q"""
      _root_.etl4s.PartialConditionalBuilder(
        ${c.prefix.tree}.sourceNode,
        ${c.prefix.tree}.branches :+ ((_root_.etl4s.Predicate($condition, $src), $branch))
      )
    """)
  }

  def completeElseIf[A, B, C](c: blackbox.Context)(
    condition: c.Expr[Any]
  )(branch: c.Expr[Any]): c.Expr[Nothing] = {
    import c.universe._
    val src = captureSrc(c)(condition.tree)
    c.Expr[Nothing](q"""
      _root_.etl4s.CompleteConditionalBuilder(
        ${c.prefix.tree}.sourceNode,
        ${c.prefix.tree}.branches :+ ((_root_.etl4s.Predicate($condition, $src), $branch)),
        ${c.prefix.tree}.defaultBranch
      )
    """)
  }

  def topIf[A](c: blackbox.Context)(condition: c.Expr[Any]): c.Expr[Nothing] = {
    import c.universe._
    val src = captureSrc(c)(condition.tree)
    c.Expr[Nothing](q"""
      new _root_.etl4s.ValueIfStart(_root_.etl4s.Predicate($condition, $src))
    """)
  }
}
