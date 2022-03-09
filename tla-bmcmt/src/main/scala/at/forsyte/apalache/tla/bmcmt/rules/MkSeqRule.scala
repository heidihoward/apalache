package at.forsyte.apalache.tla.bmcmt.rules

import at.forsyte.apalache.tla.bmcmt._
import at.forsyte.apalache.tla.bmcmt.rules.aux.ProtoSeqOps
import at.forsyte.apalache.tla.bmcmt.types.IntT
import at.forsyte.apalache.tla.lir.TypedPredefs._
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.convenience.tla
import at.forsyte.apalache.tla.lir.oper.ApalacheOper
import at.forsyte.apalache.tla.lir.storage.BodyMapFactory
import at.forsyte.apalache.tla.lir.transformations.impl.IdleTracker
import at.forsyte.apalache.tla.lir.values.TlaInt
import at.forsyte.apalache.tla.pp.{InlinerOfUserOper, TlaInputError}

/**
 * Rewriting rule for MkSeq. This rule is similar to [[FoldSeqRule]].
 *
 * @author
 *   Igor Konnov
 */
class MkSeqRule(rewriter: SymbStateRewriter) extends RewritingRule {
  private val proto = new ProtoSeqOps(rewriter)

  override def isApplicable(symbState: SymbState): Boolean = symbState.ex match {
    // match the internal representation of lambda expressions or embedded calls
    case OperEx(ApalacheOper.mkSeq, _, LetInEx(NameEx(appName), TlaOperDecl(operName, params, _))) =>
      appName == operName && params.size == 1
    case _ => false
  }

  override def apply(state: SymbState): SymbState = state.ex match {
    case OperEx(ApalacheOper.mkSeq, lenEx, LetInEx(NameEx(_), opDecl)) =>
      val capacity = extractCapacity(lenEx)

      val operT = opDecl.typeTag.asTlaType1()
      val elemT = operT match {
        case OperT1(Seq(IntT1()), et) => et
        case tp                       =>
          // this case should be found by the type checker
          val msg = "Expected an operator of type Int => <elem type>. Found: " + tp
          throw new TlaInputError(msg, Some(opDecl.ID))
      }

      // expressions are transient, we don't need tracking
      val inliner = InlinerOfUserOper(BodyMapFactory.makeFromDecl(opDecl), new IdleTracker)

      def mkElem(state: SymbState, index: Int): (SymbState, ArenaCell) = {
        // get the cell for the index
        val (newArena, indexCell) = rewriter.intValueCache.create(state.arena, index)
        // elem = A(indexCell)
        val appEx = tla.appOp(tla.name(opDecl.name).as(operT), indexCell.toNameEx.as(IntT1()))
        val inlinedEx = inliner.apply(appEx.as(elemT))
        // simply rewrite the body of the definition with the index cell as the argument
        val nextState = rewriter.rewriteUntilDone(state.setArena(newArena).setRex(inlinedEx))
        (nextState, nextState.asCell)
      }

      // create the proto sequence with the help of the user-defined operator
      var nextState = proto.make(state, capacity, mkElem)
      val protoSeq = nextState.asCell
      // create the sequence on top of the proto sequence
      nextState = nextState.updateArena(_.appendCell(IntT()))
      val lenCell = nextState.arena.topCell
      rewriter.solverContext.assertGroundExpr(tla.eql(lenCell.toNameEx.as(IntT1()), tla.int(capacity)).as(BoolT1()))
      proto.mkSeq(nextState, SeqT1(elemT), protoSeq, lenCell)

    case _ =>
      throw new RewriterException("%s is not applicable".format(getClass.getSimpleName), state.ex)
  }

  // extract capacity from the length expression
  private def extractCapacity(lenEx: TlaEx): Int = {
    lenEx match {
      case ValEx(TlaInt(n)) if n.isValidInt && n >= 0 =>
        n.toInt

      case ValEx(TlaInt(n)) if !n.isValidInt || n < 0 =>
        val msg = s"Expected an integer in the range [0, ${Int.MaxValue}]. Found: $n"
        throw new TlaInputError(msg, Some(lenEx.ID))

      case unexpectedEx =>
        val msg = "Expected a constant integer expression. Found: " + unexpectedEx
        throw new TlaInputError(msg, Some(lenEx.ID))
    }
  }
}
