package etl4s

import scala.quoted.*

/**
 * Macros backing the value-side `If` / `ElseIf` DSL for Scala 3. Each impl
 * captures the raw source text of the predicate argument at the call site
 */
object IfMacros {

  private def captureSrc(using Quotes)(term: quotes.reflect.Term): String = {
    import quotes.reflect.*
    val fromArg = term.pos.sourceCode.getOrElse("").trim
    if (fromArg.nonEmpty) fromArg
    else Position.ofMacroExpansion.sourceCode.getOrElse("").trim
  }

  def nodeIf[A: Type, B: Type, C: Type](
    node: Expr[Node[A, B]],
    condition: Expr[B => Boolean],
    branch: Expr[Node[B, C]]
  )(using Quotes): Expr[PartialConditionalBuilder[A, B, C]] = {
    import quotes.reflect.*
    val src = captureSrc(condition.asTerm)
    '{
      PartialConditionalBuilder[A, B, C](
        $node,
        List((Predicate[B]($condition, ${ Expr(src) }), $branch))
      )
    }
  }

  def partialElseIf[A: Type, B: Type, C: Type, C2: Type](
    self: Expr[PartialConditionalBuilder[A, B, C]],
    condition: Expr[B => Boolean],
    branch: Expr[Node[B, C2]]
  )(using Quotes): Expr[PartialConditionalBuilder[A, B, C | C2]] = {
    import quotes.reflect.*
    val src = captureSrc(condition.asTerm)
    '{
      val prev = $self
      PartialConditionalBuilder[A, B, C | C2](
        prev.sourceNode,
        prev.branches.map { case (cond, n) =>
          (cond, n.asInstanceOf[Node[B, C | C2]])
        } :+ (
          Predicate[B]($condition, ${ Expr(src) }),
          $branch.asInstanceOf[Node[B, C | C2]]
        )
      )
    }
  }

  def completeElseIf[A: Type, B: Type, C: Type, C2: Type](
    self: Expr[CompleteConditionalBuilder[A, B, C]],
    condition: Expr[B => Boolean],
    branch: Expr[Node[B, C2]]
  )(using Quotes): Expr[CompleteConditionalBuilder[A, B, C | C2]] = {
    import quotes.reflect.*
    val src = captureSrc(condition.asTerm)
    '{
      val prev = $self
      CompleteConditionalBuilder[A, B, C | C2](
        prev.sourceNode,
        prev.branches.map { case (cond, n) =>
          (cond, n.asInstanceOf[Node[B, C | C2]])
        } :+ (
          Predicate[B]($condition, ${ Expr(src) }),
          $branch.asInstanceOf[Node[B, C | C2]]
        ),
        prev.defaultBranch.asInstanceOf[Node[B, C | C2]]
      )
    }
  }

  def topIf[A: Type](
    condition: Expr[A => Boolean]
  )(using Quotes): Expr[ValueIfStart[A]] = {
    import quotes.reflect.*
    val src = captureSrc(condition.asTerm)
    '{ new ValueIfStart[A](Predicate[A]($condition, ${ Expr(src) })) }
  }
}
