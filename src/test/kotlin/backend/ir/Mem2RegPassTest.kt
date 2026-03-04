package backend.ir

import backend.ir.opt.Mem2RegPass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Mem2RegPassTest {
  private val i32 = IrPrimitive(PrimitiveKind.I32)
  private val unit = IrPrimitive(PrimitiveKind.UNIT)
  private val bool = IrPrimitive(PrimitiveKind.BOOL)

  @Test
  fun `promotes straight-line local alloca`() {
    val module = IrModule()
    val function = IrFunction(
      name = "f.",
      signature = IrFunctionSignature(parameters = emptyList(), returnType = i32),
      parameterNames = emptyList(),
    )
    module.declareFunction(function)
    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)

    val x = builder.emit(IrAlloca("x", IrPointer(i32), i32), "x")
    builder.emit(IrStore("", unit, x, IrConstant(7, i32)))
    val loaded = builder.emit(IrLoad("xv", i32, x), "xv")
    builder.emitTerminator(IrReturn("", i32, loaded))

    Mem2RegPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains("alloca i32"), "alloca should be removed after promotion")
    assertFalse(rendered.contains("load i32"), "load should be removed after promotion")
    assertFalse(rendered.contains("store i32 7, ptr %x"), "store to promoted slot should be removed")
    assertTrue(rendered.contains("ret i32 7"), "return should use SSA constant directly")
  }

  @Test
  fun `inserts phi for branch merge`() {
    val module = IrModule()
    val function = IrFunction(
      name = "branchPhi.",
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
    val x = builder.emit(IrAlloca("x", IrPointer(i32), i32), "x")
    builder.emitTerminator(
      IrBranch(
        "",
        unit,
        IrParameter(0, "cond", bool),
        "then",
        "else",
      )
    )

    builder.positionAt(function, thenBlock)
    builder.emit(IrStore("", unit, x, IrConstant(1, i32)))
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, elseBlock)
    builder.emit(IrStore("", unit, x, IrConstant(2, i32)))
    builder.emitTerminator(IrJump("", unit, "merge"))

    builder.positionAt(function, mergeBlock)
    val out = builder.emit(IrLoad("out", i32, x), "out")
    builder.emitTerminator(IrReturn("", i32, out))

    Mem2RegPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains("alloca i32"), "alloca should be promoted")
    assertTrue(rendered.contains(" = phi i32 "), "merge should contain a phi for promoted slot")
    assertTrue(rendered.contains("[1, %then]"), "phi must include then incoming")
    assertTrue(rendered.contains("[2, %else]"), "phi must include else incoming")
    assertFalse(rendered.contains("load i32"), "load should be removed by promotion")
  }

  @Test
  fun `inserts phi for loop-carried value`() {
    val module = IrModule()
    val function = IrFunction(
      name = "loopPhi.",
      signature = IrFunctionSignature(parameters = emptyList(), returnType = i32),
      parameterNames = emptyList(),
    )
    module.declareFunction(function)
    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    val loop = function.createBlock("loop")
    val body = function.createBlock("body")
    val exit = function.createBlock("exit")

    builder.positionAt(function, entry)
    val x = builder.emit(IrAlloca("x", IrPointer(i32), i32), "x")
    builder.emit(IrStore("", unit, x, IrConstant(0, i32)))
    builder.emitTerminator(IrJump("", unit, "loop"))

    builder.positionAt(function, loop)
    val cur = builder.emit(IrLoad("cur", i32, x), "cur")
    val cond = builder.emit(IrCmp("c", bool, ComparePredicate.SLT, cur, IrConstant(3, i32)), "c")
    builder.emitTerminator(IrBranch("", unit, cond, "body", "exit"))

    builder.positionAt(function, body)
    val v = builder.emit(IrLoad("v", i32, x), "v")
    val inc = builder.emit(IrBinary("inc", i32, BinaryOperator.ADD, v, IrConstant(1, i32)), "inc")
    builder.emit(IrStore("", unit, x, inc))
    builder.emitTerminator(IrJump("", unit, "loop"))

    builder.positionAt(function, exit)
    val out = builder.emit(IrLoad("out", i32, x), "out")
    builder.emitTerminator(IrReturn("", i32, out))

    Mem2RegPass().run(module, function)

    val rendered = function.render()
    assertFalse(rendered.contains("alloca i32"), "alloca should be promoted")
    assertTrue(rendered.contains("loop:"), "loop block should remain")
    assertTrue(rendered.contains(" = phi i32 "), "loop should require phi for carried value")
    assertFalse(rendered.contains("load i32"), "loads from promoted slot should be removed")
  }

  @Test
  fun `does not promote escaped slot`() {
    val module = IrModule()
    val calleeType = IrFunctionType(listOf(IrPointer(i32)), unit)
    val function = IrFunction(
      name = "escape.",
      signature = IrFunctionSignature(parameters = emptyList(), returnType = unit),
      parameterNames = emptyList(),
    )
    module.declareFunction(function)
    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)

    val x = builder.emit(IrAlloca("x", IrPointer(i32), i32), "x")
    builder.emit(
      IrCall(
        "",
        unit,
        IrFunctionRef("sink.", IrPointer(calleeType)),
        listOf(x),
      )
    )
    builder.emitTerminator(IrReturn("", unit, null))

    Mem2RegPass().run(module, function)

    val rendered = function.render()
    assertTrue(rendered.contains("alloca i32"), "escaped alloca must not be promoted")
  }
}
