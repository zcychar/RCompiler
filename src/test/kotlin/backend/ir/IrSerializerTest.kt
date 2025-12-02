package backend.ir

import kotlin.test.Test
import kotlin.test.assertTrue

class IrSerializerTest {
    @Test
    fun `serializer emits builtin prologue before functions`() {
        val module = IrModule()
        val fn = IrFunction("foo", IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT)))
        module.declareFunction(fn)
        val serializer = IrSerializer()
        val output = serializerToString(serializer, module)
        val prologueIndex = output.indexOf("declare i32 @printf")
        val functionIndex = output.indexOf("define void @foo")
        assertTrue(prologueIndex >= 0)
        assertTrue(functionIndex > prologueIndex)
    }

    private fun serializerToString(serializer: IrSerializer, module: IrModule): String {
        val temp = kotlin.io.path.createTempFile()
        serializer.write(module, temp)
        val text = temp.toFile().readText()
        temp.toFile().delete()
        return text
    }
}
