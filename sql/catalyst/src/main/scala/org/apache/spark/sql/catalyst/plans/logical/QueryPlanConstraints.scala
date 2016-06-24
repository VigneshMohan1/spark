/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.expressions._


trait QueryPlanConstraints { self: LogicalPlan =>

  /**
   * An [[ExpressionSet]] that contains invariants about the rows output by this operator. For
   * example, if this set contains the expression `a = 2` then that expression is guaranteed to
   * evaluate to `true` for all rows produced.
   */
  lazy val constraints: ExpressionSet = {
    if (conf.constraintPropagationEnabled) {
      ExpressionSet(
        validConstraints
          .union(inferAdditionalConstraints(validConstraints))
          .union(constructIsNotNullConstraints(validConstraints))
          .filter { c =>
            c.references.nonEmpty && c.references.subsetOf(outputSet) && c.deterministic
          }
      )
    } else {
      ExpressionSet(Set.empty)
    }
  }

  /**
   * This method can be overridden by any child class of QueryPlan to specify a set of constraints
   * based on the given operator's constraint propagation logic. These constraints are then
   * canonicalized and filtered automatically to contain only those attributes that appear in the
   * [[outputSet]].
   *
   * See [[Canonicalize]] for more details.
   */
  protected def validConstraints: Set[Expression] = Set.empty

  /**
   * Infers a set of `isNotNull` constraints from null intolerant expressions as well as
   * non-nullable attributes. For e.g., if an expression is of the form (`a > 5`), this
   * returns a constraint of the form `isNotNull(a)`
   */
  private def constructIsNotNullConstraints(constraints: Set[Expression]): Set[Expression] = {
    // First, we propagate constraints from the null intolerant expressions.
    var isNotNullConstraints: Set[Expression] = constraints.flatMap(inferIsNotNullConstraints)

    // Second, we infer additional constraints from non-nullable attributes that are part of the
    // operator's output
    val nonNullableAttributes = output.filterNot(_.nullable)
    isNotNullConstraints ++= nonNullableAttributes.map(IsNotNull).toSet

    isNotNullConstraints -- constraints
  }

  /**
   * Infer the Attribute-specific IsNotNull constraints from the null intolerant child expressions
   * of constraints.
   */
  private def inferIsNotNullConstraints(constraint: Expression): Seq[Expression] =
    constraint match {
      // When the root is IsNotNull, we can push IsNotNull through the child null intolerant
      // expressions
      case IsNotNull(expr) => scanNullIntolerantField(expr).map(IsNotNull(_))
      // Constraints always return true for all the inputs. That means, null will never be returned.
      // Thus, we can infer `IsNotNull(constraint)`, and also push IsNotNull through the child
      // null intolerant expressions.
      case _ => scanNullIntolerantField(constraint).map(IsNotNull(_))
    }

  /**
   * Recursively explores the expressions which are null intolerant and returns all attributes
   * in these expressions.
   */
  private def scanNullIntolerantField(expr: Expression): Seq[Expression] = expr match {
    case ev: ExtractValue => Seq(ev)
    case a: Attribute => Seq(a)
    case _: NullIntolerant => expr.children.flatMap(scanNullIntolerantField)
    case _ => Seq.empty[Attribute]
  }

  // Collect aliases from expressions of the whole tree rooted by the current QueryPlan node, so
  // we may avoid producing recursive constraints.
  private lazy val aliasMap: AttributeMap[Expression] = AttributeMap(
    expressions.collect {
      case a: Alias => (a.toAttribute, a.child)
    } ++ children.flatMap(_.asInstanceOf[QueryPlanConstraints].aliasMap))
    // Note: the explicit cast is necessary, since Scala compiler fails to infer the type.

  /**
   * Infers an additional set of constraints from a given set of equality constraints.
   * For e.g., if an operator has constraints of the form (`a = 5`, `a = b`), this returns an
   * additional constraint of the form `b = 5`.
   */
  private def inferAdditionalConstraints(constraints: Set[Expression]): Set[Expression] = {
    val aliasedConstraints = eliminateAliasedExpressionInConstraints(constraints)
    var inferredConstraints = Set.empty[Expression]
    aliasedConstraints.foreach {
      case eq @ EqualTo(l: Attribute, r: Attribute) =>
        val candidateConstraints = aliasedConstraints - eq
        inferredConstraints ++= replaceConstraints(candidateConstraints, l, r)
        inferredConstraints ++= replaceConstraints(candidateConstraints, r, l)
      case _ => // No inference
    }
    inferredConstraints -- constraints
  }

  /**
   * Replace the aliased expression in [[Alias]] with the alias name if both exist in constraints.
   * Thus non-converging inference can be prevented.
   * E.g. `Alias(b, f(a)), a = b` infers `f(a) = f(f(a))` without eliminating aliased expressions.
   * Also, the size of constraints is reduced without losing any information.
   * When the inferred filters are pushed down the operators that generate the alias,
   * the alias names used in filters are replaced by the aliased expressions.
   */
  private def eliminateAliasedExpressionInConstraints(constraints: Set[Expression])
    : Set[Expression] = {
    val attributesInEqualTo = constraints.flatMap {
      case EqualTo(l: Attribute, r: Attribute) => l :: r :: Nil
      case _ => Nil
    }
    var aliasedConstraints = constraints
    attributesInEqualTo.foreach { a =>
      if (aliasMap.contains(a)) {
        val child = aliasMap.get(a).get
        aliasedConstraints = replaceConstraints(aliasedConstraints, child, a)
      }
    }
    aliasedConstraints
  }

  private def replaceConstraints(
      constraints: Set[Expression],
      source: Expression,
      destination: Attribute): Set[Expression] = constraints.map(_ transform {
    case e: Expression if e.semanticEquals(source) => destination
  })
}
