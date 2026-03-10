package backend.ir

import backend.ir.opt.PhiLoweringPass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhiLoweringPassTest {
  private val i32 = IrPrimitive(PrimitiveKind.I32)
  private val bool = IrPrimitive(PrimitiveKind.BOOL)
  private val unit = IrPrimitive(PrimitiveKind.UNIT)

  @Test
  fun `lowers merge phi into edge stores and block-entry load`() {
    val module = IrModule()
    val function = IrFunction(
      name = "phiMerge.",
      signature = IrFunctionSignature(parameters = listOf(bool), returnType = i32),
      parameterNames = listOf("cond"),
    )
    module.declareFunction(function)

    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    val thenBlock = function.createBlock("then")
    val elseBlock = function.createBlock("else")
    val mergeBlock = function.createBlock("merge")

    builder.positionAt(function, entry)
    builder.emitTerminator(
      IrBranch(
        name = "",
        type = unit,
        condition = IrParameter(0, "cond", bool),
        trueTarget = "then",
        falseTarget = "else",
      )
    )

    builder.positionAt(function, thenBlock)
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, elseBlock)
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, mergeBlock)
    val merged = builder.emit(
      IrPhi(
        name = "x",
        type = i32,
        incoming = listOf(
          PhiBranch(IrConstant(1, i32), "then"),
          PhiBranch(IrConstant(2, i32), "else"),
        ),
      ),
      "x",
    )
    builder.emitTerminator(IrReturn("", i32, merged))

    PhiLoweringPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains(" = phi "), "all phi nodes should be removed")
    assertTrue(rendered.contains("phi.slot.x = alloca i32"), "phi slot alloca should be inserted in entry")
    assertTrue(rendered.contains("then:\n  store i32 1, ptr %phi.slot.x\n  br label %merge"), "then edge should store incoming value")
    assertTrue(rendered.contains("else:\n  store i32 2, ptr %phi.slot.x\n  br label %merge"), "else edge should store incoming value")
    assertTrue(rendered.contains("merge:\n  %x = load i32, ptr %phi.slot.x"), "merge block should load lowered phi value")
  }

  @Test
  fun `splits critical edge when lowering phi stores`() {
    val module = IrModule()
    val function = IrFunction(
      name = "phiCritical.",
      signature = IrFunctionSignature(parameters = listOf(bool, bool), returnType = i32),
      parameterNames = listOf("cond", "cond2"),
    )
    module.declareFunction(function)

    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    val thenBlock = function.createBlock("then")
    val elseBlock = function.createBlock("else")
    val mergeBlock = function.createBlock("merge")
    val exitBlock = function.createBlock("exit")

    builder.positionAt(function, entry)
    builder.emitTerminator(
      IrBranch(
        name = "",
        type = unit,
        condition = IrParameter(0, "cond", bool),
        trueTarget = "then",
        falseTarget = "else",
      )
    )

    builder.positionAt(function, thenBlock)
    builder.emitTerminator(
      IrBranch(
        name = "",
        type = unit,
        condition = IrParameter(1, "cond2", bool),
        trueTarget = "merge",
        falseTarget = "exit",
      )
    )

    builder.positionAt(function, elseBlock)
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, mergeBlock)
    val merged = builder.emit(
      IrPhi(
        name = "v",
        type = i32,
        incoming = listOf(
          PhiBranch(IrConstant(10, i32), "then"),
          PhiBranch(IrConstant(20, i32), "else"),
        ),
      ),
      "v",
    )
    builder.emitTerminator(IrReturn("", i32, merged))

    builder.positionAt(function, exitBlock)
    builder.emitTerminator(IrReturn("", i32, IrConstant(0, i32)))

    PhiLoweringPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains(" = phi "), "all phi nodes should be removed")
    assertTrue(rendered.contains("label %phi.edge.then.to.merge"), "then->merge edge should be rewritten to a split block")
    assertTrue(rendered.contains("phi.edge.then.to.merge:"), "split block for critical edge should be created")
    assertTrue(rendered.contains("phi.edge.then.to.merge:\n  store i32 10, ptr %phi.slot.v\n  br label %merge"), "split block should contain incoming store for then")
    assertTrue(rendered.contains("else:\n  store i32 20, ptr %phi.slot.v\n  br label %merge"), "else edge can store directly without split")
    assertTrue(rendered.contains("merge:\n  %v = load i32, ptr %phi.slot.v"), "merge block should load lowered phi value")
  }
}
