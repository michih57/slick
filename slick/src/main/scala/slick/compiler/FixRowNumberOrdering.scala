package slick.compiler

import slick.ast._
import Util._

/** Inject the proper orderings into the RowNumber nodes produced earlier by
  * the resolveFixJoins phase. */
class FixRowNumberOrdering extends Phase {
  val name = "fixRowNumberOrdering"

  def apply(state: CompilerState) =
    if(state.get(Phase.resolveZipJoins).get) state.map(n => fix(n)) else state

  /** Push ORDER BY into RowNumbers in ordered Comprehensions. */
  def fix(n: Node, parent: Option[Comprehension] = None): Node = (n, parent) match {
    case (r @ RowNumber(_), Some(c)) if !c.orderBy.isEmpty =>
      RowNumber(c.orderBy).nodeTyped(r.nodeType)
    case (c: Comprehension, _) => c.nodeMapScopedChildren {
      case (Some(gen), ch) => fix(ch, None)
      case (None, ch) => fix(ch, Some(c))
    }.nodeWithComputedType()
    case (n, _) => n.nodeMapChildren(ch => fix(ch, parent), keepType = true)
  }
}
