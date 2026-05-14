package backend.codegen

import backend.ir.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastLoweringTest {
  private val i32 = IrPrimitive(PrimitiveKind.I32)
  private val char = IrPrimitive(PrimitiveKind.CHAR)

  @Test
  fun `zero extending char masks eight bits`() {
    val asm = compileCast(CastKind.ZEXT, IrConstant(255, char), i32)

    assertTrue(asm.contains("andi"), "zext char should emit an andi mask:\n$asm")
    assertTrue(asm.contains(", 255"), "zext char must mask with 255, not 1:\n$asm")
    assertFalse(asm.contains(", 1"), "zext char must not use the bool mask:\n$asm")
  }

  @Test
  fun `sign extending char shifts by twenty four bits`() {
    val asm = compileCast(CastKind.SEXT, IrConstant(255, char), i32)

    assertTrue(asm.contains("slli"), "sext char should emit a left shift:\n$asm")
    assertTrue(asm.contains("srai"), "sext char should emit an arithmetic right shift:\n$asm")
    assertTrue(asm.contains(", 24"), "sext char must use 24-bit shifts:\n$asm")
    assertFalse(asm.contains(", 31"), "sext char must not use the bool sign-extension shift:\n$asm")
  }

  @Test
  fun `truncating to char masks eight bits`() {
    val asm = compileCast(CastKind.TRUNC, IrConstant(511, i32), char)

    assertTrue(asm.contains("andi"), "trunc char should emit an andi mask:\n$asm")
    assertTrue(asm.contains(", 255"), "trunc char must mask with 255, not 1:\n$asm")
    assertFalse(asm.contains(", 1"), "trunc char must not use the bool mask:\n$asm")
  }

  private fun compileCast(kind: CastKind, value: IrValue, targetType: IrType): String {
    val module = IrModule()
    val function = IrFunction(
      name = "cast.",
      signature = IrFunctionSignature(parameters = emptyList(), returnType = targetType),
      parameterNames = emptyList(),
    )
    module.declareFunction(function)
    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)
    val cast = builder.emit(IrCast("cast", targetType, value, kind), "cast")
    builder.emitTerminator(IrReturn("", targetType, cast))
    return RiscVCodegen.compile(module)
  }
}
