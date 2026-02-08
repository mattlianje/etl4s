/*
 * +==========================================================================+
 * |                                 etl4s                                    |
 * |                     Powerful, whiteboard-style ETL                       |
 * |                 Compatible with Scala 2.12, 2.13, and 3                  |
 * |                                                                          |
 * | Copyright 2025 Matthieu Court (matthieu.court@protonmail.com)            |
 * | Apache License 2.0                                                       |
 * +==========================================================================+
 */

package etl4s

/**
 * Marker trait for validation checks that can be used in Reader.ensure()
 */
sealed trait ValidationCheck[T, A] {
  def toCurried: T => A => Option[String]
}

/**
 * Context-aware validation check (already curried)
 */
case class CurriedCheck[T, A](f: T => A => Option[String]) extends ValidationCheck[T, A] {
  def toCurried: T => A => Option[String] = f
}

/**
 * Plain validation check (will be lifted to ignore context)
 */
case class PlainCheck[T, A](f: A => Option[String]) extends ValidationCheck[T, A] {
  def toCurried: T => A => Option[String] = _ => f
}
