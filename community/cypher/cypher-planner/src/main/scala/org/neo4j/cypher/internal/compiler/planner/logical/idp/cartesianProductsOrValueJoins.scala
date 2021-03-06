/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.idp

import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.QueryPlannerKit
import org.neo4j.cypher.internal.compiler.planner.logical.SortPlanner.SatisfiedForPlan
import org.neo4j.cypher.internal.compiler.planner.logical.steps.BestPlans
import org.neo4j.cypher.internal.expressions.Equals
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.NodeIndexSeek
import org.neo4j.cypher.internal.logical.plans.NodeUniqueIndexSeek
import org.neo4j.cypher.internal.util.Cardinality
import org.neo4j.cypher.internal.util.CartesianOrdering
import org.neo4j.cypher.internal.util.Cost

trait JoinDisconnectedQueryGraphComponents {
  def apply(componentPlans: Set[PlannedComponent],
            fullQG: QueryGraph,
            interestingOrder: InterestingOrder,
            context: LogicalPlanningContext,
            kit: QueryPlannerKit,
            singleComponentPlanner: SingleComponentPlannerTrait): Set[PlannedComponent]
}

case class PlannedComponent(queryGraph: QueryGraph, plan: BestPlans)

case class Component(queryGraph: QueryGraph, plan: LogicalPlan)

/**
 * This class is responsible for connecting two disconnected logical plans, which can be
 * done with hash joins when an useful predicate connects the two plans, or with cartesian
 * product lacking that.
 *
 * The input is a set of disconnected patterns and this class will greedily find the
 * cheapest connection that can be done replace the two input plans with the connected
 * one. This process can then be repeated until a single plan remains.
 *
 * This class is being replaced by [[ComponentConnectorPlanner]].
 * It is still left in the code in case the replacement leads to unexpected regressions.
 * The plan is to remove this in the future, e.g. in the next mayor version.
 *
 * Compared with [[ComponentConnectorPlanner]], this does not always consider ordering
 * during planning, and might thus produce worse plans if there is an ORDER BY.
 */
case object cartesianProductsOrValueJoins extends JoinDisconnectedQueryGraphComponents {

  val COMPONENT_THRESHOLD_FOR_CARTESIAN_PRODUCT = 8

  def apply(plans: Set[PlannedComponent],
            qg: QueryGraph,
            interestingOrder: InterestingOrder,
            context: LogicalPlanningContext,
            kit: QueryPlannerKit,
            singleComponentPlanner: SingleComponentPlannerTrait): Set[PlannedComponent] = {
    require(plans.size > 1, "Can't connect less than 2 components.")

    /*
    To connect disconnected query parts, we have a couple of different ways. First we check if there are any joins that
    we could do. Joins are equal or better than cartesian products, so we always go for the joins when possible.

    Next we perform an exhaustive search for how to combine the remaining query parts together. In-between each step we
    check if any joins have been made available and if any predicates can be applied. This exhaustive search makes for
    better plans, but is exponentially expensive.

    So, when we have too many plans to combine, we fall back to the naive way of just building a left deep tree with
    all query parts cross joined together.
     */
    val joins =
      produceHashJoins(plans, qg, context, kit) ++
        produceNIJVariations(plans, qg, interestingOrder, context, kit, singleComponentPlanner)

    val (joinsSatisfyingOrder, joinsOther) = joins.partition{ case (comp, _) =>
      require(comp.plan.bestResultFulfillingReq.isEmpty, s"Expected only bestResult for component $comp")
      val plan = comp.plan.bestResult
      val asSortedAsPossible = SatisfiedForPlan(plan)
      val providedOrder = context.planningAttributes.providedOrders(plan.id)
      interestingOrder.satisfiedBy(providedOrder) match {
        case asSortedAsPossible() => true
        case _ => false
      }
    }

    if (joinsSatisfyingOrder.nonEmpty) {
      pickTheBest(plans, kit, joinsSatisfyingOrder)
    } else if (joinsOther.nonEmpty) {
      pickTheBest(plans, kit, joinsOther)
    } else if (plans.size < COMPONENT_THRESHOLD_FOR_CARTESIAN_PRODUCT) {
      val cartesianProducts = produceCartesianProducts(plans, qg, context, kit)
      pickTheBest(plans, kit, cartesianProducts)
    }
    else {
      Set(planLotsOfCartesianProducts(plans, qg, context, kit, considerSelections = true))
    }
  }

  private def pickTheBest(plans: Set[PlannedComponent], kit: QueryPlannerKit, joins: Map[PlannedComponent, (PlannedComponent, PlannedComponent)]): Set[PlannedComponent] = {
    val bestPlan = kit.pickBest.ofBestResults(joins.map(_._1.plan)).get
    val bestQG: QueryGraph = joins.collectFirst {
      case (PlannedComponent(fqg, pl), _) if bestPlan == pl => fqg
    }.get
    val (p1, p2) = joins(PlannedComponent(bestQG, bestPlan))

    plans - p1 - p2 + PlannedComponent(bestQG, bestPlan)
  }

  private def theSortedComponent(components: Set[PlannedComponent]): Option[PlannedComponent] = {
    val allSorted = components.collect {
      case pc@PlannedComponent(_, BestResults(_, Some(_))) => pc
    }

    if (allSorted.size > 1) {
      throw new IllegalStateException(s"There can be no more than 1 sorted component. Got: $components")
    }

    allSorted.headOption
  }

  /**
   * Plans a large amount of query parts together. Produces a left deep tree sorted by the cost/cardinality of the query parts.
   *
   * @param considerSelections whether to try and plan selections after each combining of two components.
   */
  private[idp] def planLotsOfCartesianProducts(plans: Set[PlannedComponent],
                                               qg: QueryGraph,
                                               context: LogicalPlanningContext,
                                               kit: QueryPlannerKit,
                                               considerSelections: Boolean): PlannedComponent = {
    val maybeSortedComponent = theSortedComponent(plans)

    def sortCriteria(c: Component): (Cost, Cardinality) = {
      val cardinality = context.planningAttributes.cardinalities(c.plan.id)
      val cost = context.cost.apply(c.plan, context.input, context.planningAttributes.cardinalities)
      (cost, cardinality)
    }

    val components = plans.toList.map {
      case PlannedComponent(queryGraph, BestResults(bestResult, _)) => Component(queryGraph, bestResult)
    }

    val bestComponents: Seq[Component] = if(components.size < 2) {
      components
    } else {
      components.map { c => (c, sortCriteria(c)) }.sortBy(_._2)(CartesianOrdering).map(_._1)
    }

    val bestSortedPlans = maybeSortedComponent.map {
      // If we have a sorted component, that should go to the very left of the cartesian products to keep the sort order
      sortedComponent =>
        val c = Component(sortedComponent.queryGraph, sortedComponent.plan.bestResultFulfillingReq.get)
        c +: bestComponents.filterNot(comp => c.queryGraph == comp.queryGraph)
    }

    def cross(allPlans: Seq[Component]): Component = allPlans.tail.foldLeft(allPlans.head) {
      case (l, r) =>
        val cp = context.logicalPlanProducer.planCartesianProduct(l.plan, r.plan, context)
        val cpWithSelection = if (considerSelections) kit.select(cp, qg) else cp
        Component(l.queryGraph ++ r.queryGraph, cpWithSelection)
    }

    val bestPlan = cross(bestComponents)
    val bestSortedPlan = bestSortedPlans.map(cross).map(_.plan)
    PlannedComponent(bestPlan.queryGraph, BestResults(bestPlan.plan, bestSortedPlan))
  }

  private def produceCartesianProducts(plans: Set[PlannedComponent], qg: QueryGraph, context: LogicalPlanningContext, kit: QueryPlannerKit):
  Map[PlannedComponent, (PlannedComponent, PlannedComponent)] = {
    (for (t1@PlannedComponent(qg1, p1) <- plans; t2@PlannedComponent(qg2, p2) <- plans if p1 != p2) yield {
      val crossProduct = kit.select(context.logicalPlanProducer.planCartesianProduct(p1.bestResult, p2.bestResult, context), qg)
      (PlannedComponent(qg1 ++ qg2, BestResults(crossProduct, None)), (t1, t2))
    }).toMap
  }

  // Developers note: This method has been re-implemented in a very low-level imperative style, because
  // this code path caused a big SOAK regression for queries with 50-60 plans. The current implementation is
  // about 100x faster than the old one, please change functionality here with one eye on performance.
  private def produceNIJVariations(plans: Set[PlannedComponent],
                                   qg: QueryGraph,
                                   interestingOrder: InterestingOrder,
                                   context: LogicalPlanningContext,
                                   kit: QueryPlannerKit,
                                   singleComponentPlanner: SingleComponentPlannerTrait):
  Map[PlannedComponent, (PlannedComponent, PlannedComponent)] = {
    val predicatesWithDependencies: Array[(Expression, Array[String])] = qg.selections.flatPredicates.toArray.map(pred => (pred, pred.dependencies.map(_.name).toArray))
    val planArray = plans.toArray
    val allCoveredIds: Array[Set[String]] = planArray.map(_.queryGraph.allCoveredIds)

    val result = Map.newBuilder[PlannedComponent, (PlannedComponent, PlannedComponent)]

    var a = 0
    while (a < planArray.length) {
      var b = a + 1
      while (b < planArray.length) {

        val planA = planArray(a).plan
        val planB = planArray(b).plan
        val qgA = planArray(a).queryGraph
        val qgB = planArray(b).queryGraph

        for (predicate <- this.predicatesDependendingOnBothSides(predicatesWithDependencies, allCoveredIds(a), allCoveredIds(b))) {
          val nestedIndexJoinAB = planNIJIfApplicable(planA.bestResult, planB.bestResult, qgA, qgB, qg, interestingOrder, predicate, context, kit, singleComponentPlanner)
          val nestedIndexJoinBA = planNIJIfApplicable(planB.bestResult, planA.bestResult, qgB, qgA, qg, interestingOrder, predicate, context, kit, singleComponentPlanner)

          nestedIndexJoinAB.foreach(x => result += ((x, planArray(a) -> planArray(b))))
          nestedIndexJoinBA.foreach(x => result += ((x, planArray(a) -> planArray(b))))
        }
        b += 1
      }
      a += 1
    }

    result.result()
  }

  private def produceHashJoins(plans: Set[PlannedComponent],
                               qg: QueryGraph,
                               context: LogicalPlanningContext,
                               kit: QueryPlannerKit): Map[PlannedComponent, (PlannedComponent, PlannedComponent)]  = {
    (for {
      join <- joinPredicateCandidates(qg.selections.flatPredicates)
      t1@PlannedComponent(_, planA) <- plans if planA.bestResult.satisfiesExpressionDependencies(join.lhs) && !planA.bestResult.satisfiesExpressionDependencies(join.rhs)
      t2@PlannedComponent(_, planB) <- plans if planB.bestResult.satisfiesExpressionDependencies(join.rhs) && !planB.bestResult.satisfiesExpressionDependencies(join.lhs) && planA != planB
    } yield {
      val hashJoinAB = kit.select(context.logicalPlanProducer.planValueHashJoin(planA.bestResult, planB.bestResult, join, join, context), qg)
      val hashJoinBA = kit.select(context.logicalPlanProducer.planValueHashJoin(planB.bestResult, planA.bestResult, join.switchSides, join, context), qg)

      Set(
        (PlannedComponent(context.planningAttributes.solveds.get(hashJoinAB.id).asSinglePlannerQuery.lastQueryGraph, BestResults(hashJoinAB, None)), t1 -> t2),
        (PlannedComponent(context.planningAttributes.solveds.get(hashJoinBA.id).asSinglePlannerQuery.lastQueryGraph, BestResults(hashJoinBA, None)), t1 -> t2)
      )

    }).flatten.toMap
  }

  private def planNIJIfApplicable(lhsPlan: LogicalPlan,
                                  rhsInputPlan: LogicalPlan,
                                  lhsQG: QueryGraph,
                                  rhsQG: QueryGraph,
                                  fullQG: QueryGraph,
                                  interestingOrder: InterestingOrder,
                                  predicate: Expression,
                                  context: LogicalPlanningContext,
                                  kit: QueryPlannerKit,
                                  singleComponentPlanner: SingleComponentPlannerTrait): Iterator[PlannedComponent] = {

    // We cannot plan NIJ if the RHS is more than one component or optional matches because that would require us to recurse into
    // JoinDisconnectedQueryGraphComponents instead of SingleComponentPlannerTrait.
    val notSingleComponent = rhsQG.connectedComponents.size > 1
    val containsOptionals = context.planningAttributes.solveds.get(rhsInputPlan.id).asSinglePlannerQuery.lastQueryGraph.optionalMatches.nonEmpty

    if (notSingleComponent || containsOptionals) {
      Iterator.empty
    } else {
      planNIJ(lhsPlan, rhsInputPlan, lhsQG, rhsQG, interestingOrder, predicate, context, kit, singleComponentPlanner).map {
        result =>
          val resultWithSelection = kit.select(result, fullQG)
          PlannedComponent(context.planningAttributes.solveds.get(resultWithSelection.id).asSinglePlannerQuery.lastQueryGraph, BestResults(resultWithSelection, None))
      }
    }
  }

  /**
   * Index Nested Loop Joins -- if there is a value join connection between the LHS and RHS, and a useful index exists for
   * one of the sides, it can be used if the query is planned as an apply with the index seek on the RHS.
   *
   *   Apply
   * LHS  Index Seek
   */
  def planNIJ(lhsPlan: LogicalPlan,
              rhsInputPlan: LogicalPlan,
              lhsQG: QueryGraph,
              rhsQG: QueryGraph,
              interestingOrder: InterestingOrder,
              predicate: Expression,
              context: LogicalPlanningContext,
              kit: QueryPlannerKit,
              singleComponentPlanner: SingleComponentPlannerTrait): Iterator[LogicalPlan] = {
    // Replan the RHS with the LHS arguments available. If good indexes exist, they can now be used
    // Also keep any hints we might have gotten in the rhsQG so they get considered during planning
    val rhsQgWithLhsArguments = context.planningAttributes.solveds.get(rhsInputPlan.id).asSinglePlannerQuery.lastQueryGraph
      .addArgumentIds(lhsQG.idsWithoutOptionalMatchesOrUpdates.toIndexedSeq)
      .addPredicates(predicate)
      .addHints(rhsQG.hints)
    val contextForRhs = context.withUpdatedCardinalityInformation(lhsPlan)
    // TODO can we give the containsDependentIndexSeeks filter directly to singleComponentPlanner and make it filter out unusable plans earlier?
    // I.e. if no suitable leaf plans exist then we could abort
    val rhsPlans = singleComponentPlanner.planComponent(rhsQgWithLhsArguments, contextForRhs, kit, interestingOrder)

    // Keep only RHSs that actually leverage the data from the LHS to use an index.
    // The reason is that otherwise, we are producing a cartesian product disguising as an Apply, and
    // this confuses the cost model
    rhsPlans.allResults.collect {
      case rhsPlan if containsDependentIndexSeeks(rhsPlan) =>
        context.logicalPlanProducer.planApply(lhsPlan, rhsPlan, context)
    }
  }

  /**
   * Checks whether a plan contains an index seek that depends on a different variable than the one it is introducing.
   */
  def containsDependentIndexSeeks(plan: LogicalPlan): Boolean =
    plan.leaves.exists {
      case NodeIndexSeek(_, _, _, valueExpr, _, _) =>
        valueExpr.expressions.exists(_.dependencies.nonEmpty)
      case NodeUniqueIndexSeek(_, _, _, valueExpr, _, _) =>
        valueExpr.expressions.exists(_.dependencies.nonEmpty)
      case _ => false
    }

  /**
   * Given all predicates, find the ones eligible for value hash joins.
   * Those are equality predicates where both sides have different non-empty dependencies.
   */
  def joinPredicateCandidates(flatPredicates: Seq[Expression]): Set[Equals] = flatPredicates.collect {
    case e@Equals(l, r)
      if l.dependencies.nonEmpty &&
        r.dependencies.nonEmpty &&
        r.dependencies != l.dependencies => e
  }.toSet

  /**
   * Find all the predicates that depend on both the RHS and the LHS.
   * Imperative implementation style for performance. See produceNIJVariations.
   */
  def predicatesDependendingOnBothSides(predicateDependencies: Array[(Expression, Array[String])],
                                        idsFromLeft: Set[String],
                                        idsFromRight: Set[String]): Seq[Expression] =
    predicateDependencies.filter {
      case (_, deps) =>
        var i = 0
        var unfulfilledLhsDep = false
        var unfulfilledRhsDep = false
        var forAllLhsOrRhs = true

        while (i < deps.length) {
          val inLhs = idsFromLeft(deps(i))
          val inRhs = idsFromRight(deps(i))
          unfulfilledLhsDep = unfulfilledLhsDep || !inLhs
          unfulfilledRhsDep = unfulfilledRhsDep || !inRhs
          forAllLhsOrRhs = forAllLhsOrRhs && (inLhs || inRhs)
          i += 1
        }

        unfulfilledLhsDep && // The left plan is not enough
          unfulfilledRhsDep && // Neither is the right one
          forAllLhsOrRhs // But together we're good
    }.map(_._1)
}
