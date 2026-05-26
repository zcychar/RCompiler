package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class IrBuilderNamingTest {
  private val i32 = IrPrimitive(PrimitiveKind.I32)

  @Test
  fun `compiler temporaries do not collide with source-style locals`() {
    val module = IrModule()
    val function = IrFunction(
      name = "naming.",
      signature = IrFunctionSignature(parameters = emptyList(), returnType = i32),
      parameterNames = emptyList(),
    )
    module.declareFunction(function)
    val builder = IrBuilder(module)
    val entry = function.entryBlock("entry")
    builder.positionAt(function, entry)

    val sourceStyleLocal = builder.emit(
      IrAlloca("t10", IrPointer(i32), i32),
      builder.freshLocalName("t10"),
    )
    val temp = builder.emit(
      IrBinary("", i32, BinaryOperator.MUL, IrConstant(1, i32), IrConstant(7, i32)),
    )
    val duplicateTempRequest = builder.emit(
      IrBinary("rcc.tmp.1", i32, BinaryOperator.ADD, temp, IrConstant(1, i32)),
    )
    val entryNamedLocal = builder.emit(
      IrAlloca("entry", IrPointer(i32), i32),
      builder.freshLocalName("entry"),
    )

    assertEquals("t10", sourceStyleLocal.name)
    assertEquals("rcc.tmp.1", temp.name)
    assertEquals("rcc.tmp.1.2", duplicateTempRequest.name)
    assertEquals("entry.2", entryNamedLocal.name)
  }
}
