package backend.ir

import frontend.semantic.Int32Type
import frontend.semantic.StructType
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeMapperTest {
    @Test
    fun `struct layout preserves field order`() {
        val context = CodegenContext()
        val mapper = TypeMapper(context)
        val struct = StructType("Pair", linkedMapOf("a" to Int32Type, "b" to Int32Type))
        val ir = mapper.structLayout(struct) as IrStruct
        assertEquals(2, ir.fields.size)
        assertEquals(IrPrimitive(PrimitiveKind.I32), ir.fields[0])
        assertEquals(IrPrimitive(PrimitiveKind.I32), ir.fields[1])
    }

    @Test
    fun `bool maps to i1`() {
        val mapper = TypeMapper(CodegenContext())
        val ir = mapper.toIrType(frontend.semantic.BoolType)
        assertEquals(IrPrimitive(PrimitiveKind.BOOL), ir)
    }
}
