package at.forsyte.apalache.tla.typecomp

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.ApalacheOper
import at.forsyte.apalache.tla.types.tla.TypedParam
import org.junit.runner.RunWith
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalatestplus.junit.JUnitRunner
import scalaz.unused

@RunWith(classOf[JUnitRunner])
class TestApalacheBuilder extends BuilderTest {

  test("assign") {
    type T = (TBuilderInstruction, TBuilderInstruction)
    def mkWellTyped(tt: TlaType1): T =
      (
          builder.prime(builder.name("lhs", tt)),
          builder.name("rhs", tt),
      )

    def mkIllTyped(tt: TlaType1): Seq[T] =
      Seq(
          (
              builder.prime(builder.name("lhs", InvalidTypeMethods.differentFrom(tt))),
              builder.name("rhs", tt),
          ),
          (
              builder.prime(builder.name("lhs", tt)),
              builder.name("rhs", InvalidTypeMethods.differentFrom(tt)),
          ),
      )

    def resultIsExpected = expectEqTyped[TlaType1, T](
        ApalacheOper.assign,
        mkWellTyped,
        { case (a, b) => Seq(a, b) },
        _ => BoolT1,
    )

    checkRun(
        runBinary(
            builder.assign,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    // Assert throws on non-prime
    assertThrows[IllegalArgumentException] {
      build(
          builder.assign(builder.int(1), builder.int(1))
      )
    }

  }

  test("gen") {

    val gen = Gen.zip(Gen.choose(1, 10), singleTypeGen)

    val prop = forAll(gen) { case (n, tt) =>
      val genEx: TlaEx = builder.gen(n, tt)
      genEx.eqTyped(
          OperEx(
              ApalacheOper.gen,
              builder.int(n),
          )(Typed(tt))
      )
    }
    check(prop, minSuccessful(1000), sizeRange(8))

    assertThrows[IllegalArgumentException] {
      build(
          builder.gen(-1, IntT1)
      )
    }
  }

  test("skolem") {
    type T = TBuilderInstruction

    def mkWellTyped(tt: TlaType1): T =
      builder.exists(
          builder.name("x", tt),
          builder.name("S", SetT1(tt)),
          builder.name("p", BoolT1),
      )

    // If ex is not \E, then it's malformed. If it is, it must be Boolean
    def mkIllTyped(@unused tt: TlaType1): Seq[T] = Seq.empty

    def resultIsExpected = expectEqTyped[TlaType1, T](
        ApalacheOper.skolem,
        mkWellTyped,
        Seq(_),
        _ => BoolT1,
    )

    checkRun(
        runUnary(
            builder.skolem,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    // throws on non-existential
    assertThrows[IllegalArgumentException] {
      build(
          builder.skolem(builder.bool(true))
      )
    }
  }

  test("guess") {
    type T = TBuilderInstruction

    def mkWellTyped(tt: TlaType1): T = builder.name("S", SetT1(tt))

    def mkIllTyped(@unused tt: TlaType1): Seq[T] =
      Seq(
          builder.name("S", InvalidTypeMethods.notSet)
      )

    def resultIsExpected = expectEqTyped[TlaType1, T](
        ApalacheOper.guess,
        mkWellTyped,
        Seq(_),
        tt => tt,
    )

    checkRun(
        runUnary(
            builder.guess,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )
  }

  test("expand") {
    type T = TBuilderInstruction

    // Set variant

    def mkWellTyped1(tt: TlaType1): T =
      builder.powSet(builder.name("S", SetT1(tt)))

    // If ex is not SUBSET, then it's malformed. If it is, it must be a set-of-sets type
    def mkIllTyped1(@unused tt: TlaType1): Seq[T] = Seq.empty

    def resultIsExpected1 = expectEqTyped[TlaType1, T](
        ApalacheOper.expand,
        mkWellTyped1,
        Seq(_),
        tt => SetT1(SetT1(tt)),
    )

    checkRun(
        runUnary(
            builder.expand,
            mkWellTyped1,
            mkIllTyped1,
            resultIsExpected1,
        )
    )

    // throws on non-SUBSET
    assertThrows[IllegalArgumentException] {
      build(
          builder.expand(builder.name("S", SetT1(SetT1(IntT1))))
      )
    }

    // Function variant

    type TParam = (TlaType1, TlaType1)

    def mkWellTyped2(tparam: TParam): T = {
      val (a, b) = tparam
      builder.funSet(builder.name("S", SetT1(a)), builder.name("T", SetT1(b)))
    }

    // If ex is not [A -> B], then it's malformed. If it is, it must be a set-of-fns type
    def mkIllTyped2(@unused tparam: TParam): Seq[T] = Seq.empty

    def resultIsExpected2 = expectEqTyped[TParam, T](
        ApalacheOper.expand,
        mkWellTyped2,
        Seq(_),
        { case (a, b) => SetT1(FunT1(a, b)) },
    )

    checkRun(
        runUnary(
            builder.expand,
            mkWellTyped2,
            mkIllTyped2,
            resultIsExpected2,
        )
    )

    // throws on non-functionset
    assertThrows[IllegalArgumentException] {
      build(
          builder.expand(builder.name("S", SetT1(FunT1(IntT1, IntT1))))
      )
    }
  }

  test("constCard") {
    type T = TBuilderInstruction
    type TParam = (Int, TlaType1)

    implicit val gen: Gen[TParam] = Gen.zip(Gen.choose(0, 10), singleTypeGen)

    def mkWellTyped(tparam: TParam): T = {
      val (n, tt) = tparam
      builder.ge(builder.cardinality(builder.name("S", SetT1(tt))), builder.int(n))
    }

    // If ex is not Cardinality(S) >= k, then it's malformed. If it is, it must be a Boolean
    def mkIllTyped(@unused tparam: TParam): Seq[T] = Seq.empty

    def resultIsExpected = expectEqTyped[TParam, T](
        ApalacheOper.constCard,
        mkWellTyped,
        Seq(_),
        _ => BoolT1,
    )

    checkRun(
        runUnary(
            builder.constCard,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    // throws on non-Cardinality
    assertThrows[IllegalArgumentException] {
      build(
          builder.constCard(builder.bool(true))
      )
    }
  }

  object LambdaFactory {
    // we pass body and bodyT separately, so we can avoid building `body` to get its type.
    // when using Gen[TParam] we get the body type explicitly anyway.
    // Assumption: build(body).typeTag.asTlaType1() == bodyT
    /**
     * Creates a lambda expression of the form
     * {{{
     * LET opName(p1,...,pn) == body IN opName }}} ``
     */
    def mkLambda(
        opName: String,
        tparams: Seq[TypedParam],
        body: TBuilderInstruction,
        bodyT: TlaType1): TBuilderInstruction = {
      val paramTs = tparams.map { _._2 }
      val operT = OperT1(paramTs, bodyT)
      builder.letIn(
          builder.name(opName, operT),
          builder.decl(opName, body, tparams: _*),
      )
    }
  }

  test("mkSeq") {
    import LambdaFactory.mkLambda
    type T = (TBuilderInstruction, TBuilderInstruction)
    type TParam = (Int, TlaType1)

    implicit val gen: Gen[TParam] = Gen.zip(Gen.choose(0, 10), singleTypeGen)

    // MkSeq(n, LET F(i) == e IN F)
    def mkWellTyped(tparam: TParam): T = {
      val (n, t) = tparam
      val param = builder.param("i", IntT1)
      (
          builder.int(n),
          mkLambda(
              "F",
              Seq(param),
              builder.name("e", t),
              t,
          ),
      )
    }

    def mkIllTyped(tparam: TParam): Seq[T] = {
      val (n, t) = tparam
      Seq(
          // F is a unary lambda, but the arg-type is not Int
          (
              builder.int(n),
              mkLambda(
                  "F",
                  Seq(builder.param("i", InvalidTypeMethods.notInt)),
                  builder.name("e", t),
                  t,
              ),
          )
      )
    }

    def resultIsExpected = expectEqTyped[TParam, T](
        ApalacheOper.mkSeq,
        mkWellTyped,
        { case (a, b) => Seq(a, b) },
        { case (_, t) => SeqT1(t) },
    )

    checkRun(
        runBinary(
            builder.mkSeq,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    // throws on non-integer literal
    assertThrows[IllegalArgumentException] {
      build(
          builder.mkSeq(
              builder.name("NonLit", IntT1),
              mkLambda(
                  "F",
                  Seq(builder.param("i", IntT1)),
                  builder.name("e", IntT1),
                  IntT1,
              ),
          )
      )
    }

    // throws on negative integer literal
    assertThrows[IllegalArgumentException] {
      build(
          builder.mkSeq(
              builder.int(-1),
              mkLambda(
                  "F",
                  Seq(builder.param("i", IntT1)),
                  builder.name("e", IntT1),
                  IntT1,
              ),
          )
      )
    }

    // throws on non-lambda
    assertThrows[IllegalArgumentException] {
      build(
          builder.mkSeq(
              builder.int(2),
              builder.name("NonLambda", OperT1(Seq(IntT1), IntT1)),
          )
      )
    }

    // throws on non-unary lambda
    assertThrows[IllegalArgumentException] {
      build(
          builder.mkSeq(
              builder.int(2),
              mkLambda(
                  "F",
                  Seq(
                      builder.param("i", IntT1),
                      builder.param("j", IntT1),
                  ),
                  builder.name("e", IntT1),
                  IntT1,
              ),
          )
      )
    }
  }

  test("foldSet/foldSeq") {
    import LambdaFactory.mkLambda
    type T = (TBuilderInstruction, TBuilderInstruction, TBuilderInstruction)
    type TParam = (TlaType1, TlaType1)

    // Fold tests need to generate legal operator parameter types.
    val gen2Param: Gen[TParam] = Gen.zip(parameterTypeGen, parameterTypeGen)

    // Assume SeqOrSetT1 = SeqT1(_) or SetT1(_) below. The tests are otherwise the same

    // ((a,b) => a, a, SeqOrSet(b)) => a
    // FoldSeqOrSet( LET F(x,y) == e IN F, v, S )
    def mkWellTyped(SeqOrSetT1: TlaType1 => TlaType1)(tparam: TParam): T = {
      val (a, b) = tparam
      val params = Seq(
          builder.param("x", a),
          builder.param("y", b),
      )
      (
          mkLambda(
              "F",
              params,
              builder.name("e", a),
              a,
          ),
          builder.name("v", a),
          builder.name("S", SeqOrSetT1(b)),
      )
    }

    def mkIllTyped(SeqOrSetT1: TlaType1 => TlaType1)(tparam: TParam): Seq[T] = {
      val (a, b) = tparam
      // We manipulate each of the types to break correctness, but the names can stay the same
      def mkCustomLambda(xT: TlaType1, yT: TlaType1): TBuilderInstruction =
        mkLambda(
            "F",
            Seq(
                builder.param("x", xT),
                builder.param("y", yT),
            ),
            builder.name("e", xT),
            xT,
        )
      Seq(
          (
              mkCustomLambda(InvalidTypeMethods.differentFrom(a), b),
              builder.name("v", a),
              builder.name("S", SeqOrSetT1(b)),
          ),
          (
              mkCustomLambda(a, InvalidTypeMethods.differentFrom(b)),
              builder.name("v", a),
              builder.name("S", SeqOrSetT1(b)),
          ),
          (
              mkCustomLambda(a, b),
              builder.name("v", InvalidTypeMethods.differentFrom(a)),
              builder.name("S", SeqOrSetT1(b)),
          ),
          (
              mkCustomLambda(a, b),
              builder.name("v", a),
              builder.name("S", SeqOrSetT1(InvalidTypeMethods.differentFrom(b))),
          ),
          (
              mkCustomLambda(a, b),
              builder.name("v", a),
              builder.name("S", IntT1), // both not a set and not a seq
          ),
      )
    }

    def resultIsExpected(seqOrSetT: TlaType1 => TlaType1) = expectEqTyped[TParam, T](
        if (seqOrSetT == SetT1) ApalacheOper.foldSet else ApalacheOper.foldSeq,
        mkWellTyped(seqOrSetT),
        { case (a, b, c) => Seq(a, b, c) },
        { case (a, _) => a },
    )

    def run(seqOrSetT: TlaType1 => TlaType1) =
      runTernary(
          if (seqOrSetT == SetT1) builder.foldSet else builder.foldSeq,
          mkWellTyped(seqOrSetT),
          mkIllTyped(seqOrSetT),
          resultIsExpected(seqOrSetT),
      ) _

    // we pass gen explicitly, since there exists a standard generator for (TT1, TT1). See #1966
    checkRun(run(SetT1))(gen2Param) // FoldSet tests
    checkRun(run(SeqT1))(gen2Param) // FoldSeq tests

    // throws on non-lambda
    assertThrows[IllegalArgumentException] {
      build(
          builder.foldSet(
              builder.name("NonLambda", OperT1(Seq(IntT1, IntT1), IntT1)),
              builder.name("v", IntT1),
              builder.name("S", SetT1(IntT1)),
          )
      )
    }

    assertThrows[IllegalArgumentException] {
      build(
          builder.foldSeq(
              builder.name("NonLambda", OperT1(Seq(IntT1, IntT1), IntT1)),
              builder.name("v", IntT1),
              builder.name("seq", SeqT1(IntT1)),
          )
      )
    }

    // throws on non-binary lambda
    assertThrows[IllegalArgumentException] {
      build(
          builder.foldSet(
              mkLambda(
                  "F",
                  Seq(builder.param("x", IntT1)),
                  builder.name("e", IntT1),
                  IntT1,
              ),
              builder.name("v", IntT1),
              builder.name("S", SetT1(IntT1)),
          )
      )
    }

    assertThrows[IllegalArgumentException] {
      build(
          builder.foldSet(
              mkLambda(
                  "F",
                  Seq(builder.param("x", IntT1)),
                  builder.name("e", IntT1),
                  IntT1,
              ),
              builder.name("v", IntT1),
              builder.name("seq", SeqT1(IntT1)),
          )
      )
    }

  }

  test("setAsFun") {
    type T = TBuilderInstruction
    type TParam = (TlaType1, TlaType1)

    def mkWellTyped(tparam: TParam): T = {
      val (a, b) = tparam
      builder.name("S", SetT1(TupT1(a, b)))
    }

    def mkIllTyped(@unused tparam: TParam): Seq[T] =
      Seq(
          builder.name("S", InvalidTypeMethods.notSet),
          builder.name("S", SetT1(InvalidTypeMethods.notTup)),
      )

    def resultIsExpected = expectEqTyped[TParam, T](
        ApalacheOper.setAsFun,
        mkWellTyped,
        Seq(_),
        { case (a, b) => FunT1(a, b) },
    )

    checkRun(
        runUnary(
            builder.setAsFun,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )
  }

}