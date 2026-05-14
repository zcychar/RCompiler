package backend.ir

import backend.ir.opt.AggressiveDeadCodeEliminationPass
import backend.ir.opt.CfgSimplificationPass
import backend.ir.opt.ConstantPropagationPass
import backend.ir.opt.DeadCodeEliminationPass
import backend.ir.opt.PassPipeline
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptimizationPassesTest {
  private val i32 = IrPrimitive(PrimitiveKind.I32)
  private val unit = IrPrimitive(PrimitiveKind.UNIT)
  private val bool = IrPrimitive(PrimitiveKind.BOOL)

  @Test
  fun `constant propagation folds arithmetic into return`() {
    val (module, function, builder) = newFunction("foldArithmetic.", i32)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)

    val sum = builder.emit(
      IrBinary("", i32, BinaryOperator.ADD, IrConstant(19, i32), IrConstant(23, i32))
    )
    builder.emitTerminator(IrReturn("", i32, sum))

    ConstantPropagationPass().run(module, function)
    DeadCodeEliminationPass().run(module, function)

    val rendered = function.render()
    assertTrue(rendered.contains("ret i32 42"), "return should use folded constant")
    assertFalse(rendered.contains(" add "), "folded arithmetic instruction should be removed")
  }

  @Test
  fun `constant propagation simplifies constant branch and removes unreachable block`() {
    val (module, function, builder) = newFunction("foldBranch.", i32)
    val entry = function.entryBlock("entry")
    val thenBlock = function.createBlock("then")
    val elseBlock = function.createBlock("else")

    builder.positionAt(function, entry)
    builder.emitTerminator(IrBranch("", unit, IrConstant(1, bool), "then", "else"))

    builder.positionAt(function, thenBlock)
    builder.emitTerminator(IrReturn("", i32, IrConstant(7, i32)))

    builder.positionAt(function, elseBlock)
    builder.emitTerminator(IrReturn("", i32, IrConstant(9, i32)))

    ConstantPropagationPass().run(module, function)

    val rendered = function.render()
    assertTrue(rendered.contains("br label %then"), "constant true branch should become an unconditional jump")
    assertFalse(rendered.contains("else:"), "unreachable else block should be removed")
  }

  @Test
  fun `constant propagation does not fold division by zero`() {
    val (module, function, builder) = newFunction("keepDivZero.", i32)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)

    val div = builder.emit(
      IrBinary("", i32, BinaryOperator.SDIV, IrConstant(1, i32), IrConstant(0, i32))
    )
    builder.emitTerminator(IrReturn("", i32, div))

    ConstantPropagationPass().run(module, function)

    val rendered = function.render()
    assertTrue(rendered.contains("sdiv i32 1, 0"), "division by zero must not be folded")
  }

  @Test
  fun `dead code elimination removes unused pure instructions but preserves effects`() {
    val (module, function, builder) = newFunction("dce.", unit)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)

    val x = builder.emit(IrAlloca("x", IrPointer(i32), i32), "x")
    builder.emit(IrBinary("dead", i32, BinaryOperator.MUL, IrConstant(3, i32), IrConstant(4, i32)), "dead")
    builder.emit(IrStore("", unit, x, IrConstant(5, i32)))
    builder.emitTerminator(IrReturn("", unit, null))

    DeadCodeEliminationPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains("%dead ="), "unused pure arithmetic should be removed")
    assertTrue(rendered.contains("alloca i32"), "store address must keep its alloca")
    assertTrue(rendered.contains("store i32 5"), "stores are observable and must be preserved")
  }

  @Test
  fun `aggressive dead code elimination removes dead pure dependency chain`() {
    val (module, function, builder) = newFunction("adce.", i32)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)

    val a = builder.emit(IrBinary("a", i32, BinaryOperator.ADD, IrConstant(1, i32), IrConstant(2, i32)), "a")
    builder.emit(IrBinary("b", i32, BinaryOperator.MUL, a, IrConstant(3, i32)), "b")
    builder.emitTerminator(IrReturn("", i32, IrConstant(0, i32)))

    AggressiveDeadCodeEliminationPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains("%a ="), "dead dependency root should be removed")
    assertFalse(rendered.contains("%b ="), "dead dependency user should be removed")
    assertTrue(rendered.contains("ret i32 0"), "observable return must remain")
  }

  @Test
  fun `cfg simplification rewrites branch with identical targets to jump`() {
    val (module, function, builder) = newFunction("sameTarget.", unit)
    val entry = function.entryBlock("entry")
    function.createBlock("exit")

    builder.positionAt(function, entry)
    builder.emitTerminator(IrBranch("", unit, IrLocal("cond", bool), "exit", "exit"))

    CfgSimplificationPass().run(module, function)

    val rendered = function.render()
    assertTrue(rendered.contains("br label %exit"), "same-target branch should become jump")
    assertFalse(rendered.contains("br i1 %cond"), "dead condition branch should be removed")
  }

  @Test
  fun `cfg simplification bypasses empty jump block and rewrites phi predecessors`() {
    val (module, function, builder) = newFunction("emptyBlock.", i32)
    val entry = function.entryBlock("entry")
    val left = function.createBlock("left")
    val right = function.createBlock("right")
    val linker = function.createBlock("linker")
    val merge = function.createBlock("merge")

    builder.positionAt(function, entry)
    builder.emitTerminator(IrBranch("", unit, IrLocal("cond", bool), "left", "right"))

    builder.positionAt(function, left)
    builder.emitTerminator(IrJump("", unit, "linker"))

    builder.positionAt(function, right)
    builder.emitTerminator(IrJump("", unit, "linker"))

    builder.positionAt(function, linker)
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, merge)
    val phi = builder.emit(
      IrPhi("p", i32, listOf(PhiBranch(IrConstant(7, i32), "linker"))),
      "p",
    )
    builder.emitTerminator(IrReturn("", i32, phi))

    CfgSimplificationPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains("linker:"), "empty jump-only block should be removed")
    assertTrue(rendered.contains("br label %merge"), "predecessors should jump directly to merge")
    assertFalse(rendered.contains("%linker"), "phi incoming should not refer to removed block")
    assertTrue(rendered.contains("[7, %entry]"), "one empty predecessor can be bypassed to entry")
    assertTrue(rendered.contains("[7, %right]"), "remaining predecessor value should be preserved")
  }

  @Test
  fun `optimization pipeline folds phi after constant branch cleanup`() {
    val module = IrModule()
    val function = IrFunction(
      name = "pipelinePhi.",
      signature = IrFunctionSignature(parameters = emptyList(), returnType = i32),
      parameterNames = emptyList(),
    )
    module.declareFunction(function)
    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    val thenBlock = function.createBlock("then")
    val elseBlock = function.createBlock("else")
    val merge = function.createBlock("merge")

    builder.positionAt(function, entry)
    builder.emitTerminator(IrBranch("", unit, IrConstant(1, bool), "then", "else"))

    builder.positionAt(function, thenBlock)
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, elseBlock)
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, merge)
    val phi = builder.emit(
      IrPhi(
        "p",
        i32,
        listOf(
          PhiBranch(IrConstant(11, i32), "then"),
          PhiBranch(IrConstant(22, i32), "else"),
        ),
      ),
      "p",
    )
    builder.emitTerminator(IrReturn("", i32, phi))

    PassPipeline(
      listOf(
        ConstantPropagationPass(),
        CfgSimplificationPass(),
        DeadCodeEliminationPass(),
        AggressiveDeadCodeEliminationPass(),
        CfgSimplificationPass(),
        DeadCodeEliminationPass(),
      )
    ).run(module)

    val rendered = function.render()
    assertTrue(rendered.contains("ret i32 11"), "single reachable phi incoming should fold")
    assertFalse(rendered.contains("phi i32"), "folded phi should be removed")
    assertFalse(rendered.contains("else:"), "unreachable incoming block should be removed")
  }

  private fun newFunction(name: String, returnType: IrType): Triple<IrModule, IrFunction, IrBuilder> {
    val module = IrModule()
    val function = IrFunction(
      name = name,
      signature = IrFunctionSignature(parameters = emptyList(), returnType = returnType),
      parameterNames = emptyList(),
    )
    module.declareFunction(function)
    return Triple(module, function, IrBuilder(module))
  }
}
