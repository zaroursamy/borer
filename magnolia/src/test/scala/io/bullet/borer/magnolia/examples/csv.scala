/* Magnolia, version 0.10.0. Copyright 2018 Jon Pretty, Propensive Ltd.
 *
 * The primary distribution site is: http://co.ntextu.al/
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package io.bullet.borer.magnolia.examples

import io.bullet.borer.magnolia._
import scala.language.experimental.macros

trait Csv[A] {
  def apply(a: A): List[String]
}

object Csv {
  type Typeclass[A] = Csv[A]

  def combine[A](ctx: CaseClass[Csv, A]): Csv[A] = new Csv[A] {
    def apply(a: A): List[String] =
      ctx.parameters.foldLeft(List[String]()) {
        (acc, p) => acc ++ p.typeclass(p.dereference(a))
      }
  }

  def dispatch[A](ctx: SealedTrait[Csv, A]): Csv[A] = new Csv[A] {
    def apply(a: A): List[String] = ctx.dispatch(a)(sub => sub.typeclass(sub.cast(a)))
  }

  implicit def deriveCsv[A]: Csv[A] = macro Magnolia.gen[A]

  implicit val csvStr: Csv[String] = new Csv[String] {
    def apply(a: String): List[String] = List(a)
  }
}