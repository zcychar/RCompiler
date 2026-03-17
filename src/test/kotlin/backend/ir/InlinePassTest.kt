package backend.ir

import backend.ir.opt.InlinePass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlinePassTest {
  private val i32 = IrPrimitive(PrimitiveKind.I32)
  private val unit = IrPrimitive(PrimitiveKind.UNIT)
  private val bool = IrPrimitive(PrimitiveKind.BOOL)

  @Test
  fun `inlines simple callee`() {
    val module = IrModule()

    // callee: fn add.(a: i32, b: i32) -> i32 { return a + b }
    val callee = IrFunction(
      "add.", IrFunctionSignature(listOf(i32, i32), i32), listOf("a", "b"),
    )
    module.declareFunction(callee)
    val cb = IrBuilder(module)
    val ce = callee.entryBlock("entry")
    cb.positionAt(callee, ce)
    val sum = cb.emit(
      IrBinary("", i32, BinaryOperator.ADD, IrParameter(0, "a", i32), IrParameter(1, "b", i32))
    )
    cb.emitTerminator(IrReturn("", i32, sum))

    // caller: fn f.() -> i32 { return add.(1, 2) }
    val caller = IrFunction(
      "f.", IrFunctionSignature(emptyList(), i32), emptyList(),
    )
    module.declareFunction(caller)
    val fb = IrBuilder(module)
    val fe = caller.entryBlock("entry")
    fb.positionAt(caller, fe)
    val result = fb.emit(
      IrCall(
        "", i32,
        IrFunctionRef("add.", IrPointer(IrFunctionType(listOf(i32, i32), i32))),
        listOf(IrConstant(1, i32), IrConstant(2, i32)),
      )
    )
    fb.emitTerminator(IrReturn("", i32, result))

    InlinePass().run(module)

    val rendered = caller.render()
    assertFalse(rendered.contains("call"), "call should be removed after inlining")
    assertTrue(rendered.contains("add i32 1, 2"), "inlined arithmetic should be present")
  }

  @Test
  fun `inlines void-returning callee`() {
    val module = IrModule()

    // callee: fn noop.() { return }
    val callee = IrFunction("noop.", IrFunctionSignature(emptyList(), unit), emptyList())
    module.declareFunction(callee)
    val cb = IrBuilder(module)
    cb.positionAt(callee, callee.entryBlock("entry"))
    cb.emitTerminator(IrReturn("", unit, null))

    // caller: fn f.() { noop.(); return }
    val caller = IrFunction("f.", IrFunctionSignature(emptyList(), unit), emptyList())
    module.declareFunction(caller)
    val fb = IrBuilder(module)
    fb.positionAt(caller, caller.entryBlock("entry"))
    fb.emit(
      IrCall("", unit, IrFunctionRef("noop.", IrPointer(IrFunctionType(emptyList(), unit))), emptyList())
    )
    fb.emitTerminator(IrReturn("", unit, null))

    InlinePass().run(module)

    val rendered = caller.render()
    assertFalse(rendered.contains("call"), "void call should be inlined away")
    assertTrue(rendered.contains("ret void"), "should still have ret void")
  }

  @Test
  fun `does not inline self-recursive function`() {
    val module = IrModule()

    // fn rec.(n: i32) -> i32 { return rec.(n - 1) }
    val fn = IrFunction("rec.", IrFunctionSignature(listOf(i32), i32), listOf("n"))
    module.declareFunction(fn)
    val b = IrBuilder(module)
    b.positionAt(fn, fn.entryBlock("entry"))
    val sub = b.emit(IrBinary("", i32, BinaryOperator.SUB, IrParameter(0, "n", i32), IrConstant(1, i32)))
    val result = b.emit(
      IrCall("", i32, IrFunctionRef("rec.", IrPointer(IrFunctionType(listOf(i32), i32))), listOf(sub))
    )
    b.emitTerminator(IrReturn("", i32, result))

    // caller
    val caller = IrFunction("f.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(caller)
    val fb = IrBuilder(module)
    fb.positionAt(caller, caller.entryBlock("entry"))
    val r = fb.emit(
      IrCall("", i32, IrFunctionRef("rec.", IrPointer(IrFunctionType(listOf(i32), i32))), listOf(IrConstant(5, i32)))
    )
    fb.emitTerminator(IrReturn("", i32, r))

    InlinePass().run(module)

    val rendered = caller.render()
    assertTrue(rendered.contains("call"), "self-recursive callee should NOT be inlined")
  }

  @Test
  fun `does not inline mutually recursive functions`() {
    val module = IrModule()
    val sig = IrFunctionSignature(listOf(i32), i32)
    val fnType = IrPointer(IrFunctionType(listOf(i32), i32))

    // fn a.(n) { return b.(n) }
    val fnA = IrFunction("a.", sig, listOf("n"))
    module.declareFunction(fnA)
    val ba = IrBuilder(module)
    ba.positionAt(fnA, fnA.entryBlock("entry"))
    val ra = ba.emit(IrCall("", i32, IrFunctionRef("b.", fnType), listOf(IrParameter(0, "n", i32))))
    ba.emitTerminator(IrReturn("", i32, ra))

    // fn b.(n) { return a.(n) }
    val fnB = IrFunction("b.", sig, listOf("n"))
    module.declareFunction(fnB)
    val bb = IrBuilder(module)
    bb.positionAt(fnB, fnB.entryBlock("entry"))
    val rb = bb.emit(IrCall("", i32, IrFunctionRef("a.", fnType), listOf(IrParameter(0, "n", i32))))
    bb.emitTerminator(IrReturn("", i32, rb))

    // caller
    val caller = IrFunction("f.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(caller)
    val fb = IrBuilder(module)
    fb.positionAt(caller, caller.entryBlock("entry"))
    val r = fb.emit(IrCall("", i32, IrFunctionRef("a.", fnType), listOf(IrConstant(1, i32))))
    fb.emitTerminator(IrReturn("", i32, r))

    InlinePass().run(module)

    val rendered = caller.render()
    assertTrue(rendered.contains("call"), "mutually recursive callee should NOT be inlined")
  }

  @Test
  fun `does not inline builtin functions`() {
    val module = IrModule()
    val printSig = IrFunctionSignature(listOf(i32), unit)
    val printType = IrPointer(IrFunctionType(listOf(i32), unit))

    // caller: fn f.() { printInt.(42); return }
    // Note: printInt. is NOT declared as an IrFunction in the module (it's a builtin)
    val caller = IrFunction("f.", IrFunctionSignature(emptyList(), unit), emptyList())
    module.declareFunction(caller)
    val fb = IrBuilder(module)
    fb.positionAt(caller, caller.entryBlock("entry"))
    fb.emit(IrCall("", unit, IrFunctionRef("printInt.", printType), listOf(IrConstant(42, i32))))
    fb.emitTerminator(IrReturn("", unit, null))

    InlinePass().run(module)

    val rendered = caller.render()
    assertTrue(rendered.contains("call"), "builtin call should NOT be inlined")
    assertTrue(rendered.contains("@printInt."), "builtin reference should remain")
  }

  @Test
  fun `does not inline function exceeding threshold`() {
    val module = IrModule()

    // callee with many instructions
    val callee = IrFunction("big.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(callee)
    val cb = IrBuilder(module)
    cb.positionAt(callee, callee.entryBlock("entry"))
    var v: IrValue = IrConstant(0, i32)
    // Emit enough instructions to exceed a threshold of 5
    for (i in 0 until 10) {
      v = cb.emit(IrBinary("", i32, BinaryOperator.ADD, v, IrConstant(1, i32)))
    }
    cb.emitTerminator(IrReturn("", i32, v))

    // caller
    val caller = IrFunction("f.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(caller)
    val fb = IrBuilder(module)
    fb.positionAt(caller, caller.entryBlock("entry"))
    val r = fb.emit(
      IrCall("", i32, IrFunctionRef("big.", IrPointer(IrFunctionType(emptyList(), i32))), emptyList())
    )
    fb.emitTerminator(IrReturn("", i32, r))

    // Use a small threshold
    InlinePass(instructionThreshold = 5).run(module)

    val rendered = caller.render()
    assertTrue(rendered.contains("call"), "large callee should NOT be inlined with small threshold")
  }

  @Test
  fun `transitive inlining via bottom-up ordering`() {
    val module = IrModule()

    // fn c.() -> i32 { return 42 }
    val fnC = IrFunction("c.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(fnC)
    val bc = IrBuilder(module)
    bc.positionAt(fnC, fnC.entryBlock("entry"))
    bc.emitTerminator(IrReturn("", i32, IrConstant(42, i32)))

    // fn b.() -> i32 { return c.() + 1 }
    val fnB = IrFunction("b.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(fnB)
    val bb = IrBuilder(module)
    bb.positionAt(fnB, fnB.entryBlock("entry"))
    val cv = bb.emit(
      IrCall("", i32, IrFunctionRef("c.", IrPointer(IrFunctionType(emptyList(), i32))), emptyList())
    )
    val bv = bb.emit(IrBinary("", i32, BinaryOperator.ADD, cv, IrConstant(1, i32)))
    bb.emitTerminator(IrReturn("", i32, bv))

    // fn a.() -> i32 { return b.() + 2 }
    val fnA = IrFunction("a.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(fnA)
    val ba = IrBuilder(module)
    ba.positionAt(fnA, fnA.entryBlock("entry"))
    val bCall = ba.emit(
      IrCall("", i32, IrFunctionRef("b.", IrPointer(IrFunctionType(emptyList(), i32))), emptyList())
    )
    val av = ba.emit(IrBinary("", i32, BinaryOperator.ADD, bCall, IrConstant(2, i32)))
    ba.emitTerminator(IrReturn("", i32, av))

    InlinePass().run(module)

    val rendered = fnA.render()
    assertFalse(rendered.contains("call"), "all calls should be inlined transitively")
    // After transitive inlining, the add operations from b should be present
    assertTrue(rendered.contains("add i32"), "inlined arithmetic should be present")
  }

  @Test
  fun `multi-return callee uses phi merge`() {
    val module = IrModule()

    // callee: fn pick.(c: bool) -> i32 { if c { return 1 } else { return 2 } }
    val callee = IrFunction(
      "pick.", IrFunctionSignature(listOf(bool), i32), listOf("c"),
    )
    module.declareFunction(callee)
    val cb = IrBuilder(module)
    val entry = callee.entryBlock("entry")
    val thenBlk = callee.createBlock("then")
    val elseBlk = callee.createBlock("else")
    cb.positionAt(callee, entry)
    cb.emitTerminator(
      IrBranch("", unit, IrParameter(0, "c", bool), "then", "else")
    )
    cb.positionAt(callee, thenBlk)
    cb.emitTerminator(IrReturn("", i32, IrConstant(1, i32)))
    cb.positionAt(callee, elseBlk)
    cb.emitTerminator(IrReturn("", i32, IrConstant(2, i32)))

    // caller: fn f.() -> i32 { return pick.(true) }
    val caller = IrFunction("f.", IrFunctionSignature(emptyList(), i32), emptyList())
    module.declareFunction(caller)
    val fb = IrBuilder(module)
    fb.positionAt(caller, caller.entryBlock("entry"))
    val r = fb.emit(
      IrCall(
        "", i32,
        IrFunctionRef("pick.", IrPointer(IrFunctionType(listOf(bool), i32))),
        listOf(IrConstant(1, bool)),
      )
    )
    fb.emitTerminator(IrReturn("", i32, r))

    InlinePass().run(module)

    val rendered = caller.render()
    assertFalse(rendered.contains("call"), "call should be inlined")
    assertTrue(rendered.contains("phi i32"), "multi-return should produce a phi")
  }
}
