package backend.ir

import backend.ir.opt.Mem2RegPass
import backend.ir.opt.SroaPass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SroaPassTest {
    private val i32 = IrPrimitive(PrimitiveKind.I32)
    private val bool = IrPrimitive(PrimitiveKind.BOOL)
    private val unit = IrPrimitive(PrimitiveKind.UNIT)

    @Test
    fun `splits struct field and lets mem2reg promote scalar slot`() {
        val (module, function, builder) = newFunction("sroaStruct.", i32)
        val point = IrStruct("Point", listOf(i32, i32))
        val entry = function.entryBlock("entry")
        builder.positionAt(function, entry)

        val alloca = builder.emit(IrAlloca("p", IrPointer(point), point), "p")
        val field = builder.emit(
            IrGep("", IrPointer(i32), alloca, listOf(IrConstant(0, i32), IrConstant(1, i32)))
        )
        builder.emit(IrStore("", unit, field, IrConstant(42, i32)))
        val loaded = builder.emit(IrLoad("", i32, field))
        builder.emitTerminator(IrReturn("", i32, loaded))

        SroaPass().run(module, function)
        Mem2RegPass().run(module, function)

        val rendered = function.render()
        assertFalse(rendered.contains("alloca %Point"), "aggregate alloca should be removed")
        assertFalse(rendered.contains("getelementptr"), "field gep should be removed")
        assertFalse(rendered.contains("load i32"), "split scalar slot should be promoted")
        assertTrue(rendered.contains("ret i32 42"), "return should use promoted scalar value")
    }

    @Test
    fun `splits nested struct field through aggregate intermediate gep`() {
        val (module, function, builder) = newFunction("sroaNested.", i32)
        val inner = IrStruct("Inner", listOf(i32, i32))
        val outer = IrStruct("Outer", listOf(inner, i32))
        val entry = function.entryBlock("entry")
        builder.positionAt(function, entry)

        val alloca = builder.emit(IrAlloca("o", IrPointer(outer), outer), "o")
        val innerPtr = builder.emit(
            IrGep("", IrPointer(inner), alloca, listOf(IrConstant(0, i32), IrConstant(0, i32)))
        )
        val field = builder.emit(
            IrGep("", IrPointer(i32), innerPtr, listOf(IrConstant(0, i32), IrConstant(1, i32)))
        )
        builder.emit(IrStore("", unit, field, IrConstant(7, i32)))
        val loaded = builder.emit(IrLoad("", i32, field))
        builder.emitTerminator(IrReturn("", i32, loaded))

        SroaPass().run(module, function)
        Mem2RegPass().run(module, function)

        val rendered = function.render()
        assertFalse(rendered.contains("alloca %Outer"), "outer alloca should be removed")
        assertFalse(rendered.contains("getelementptr"), "nested geps should be removed")
        assertTrue(rendered.contains("ret i32 7"), "nested scalar should be promoted")
    }

    @Test
    fun `rejects array access with dynamic index`() {
        val (module, function, builder) = newFunction("sroaDynamicArray.", i32, listOf(i32), listOf("idx"))
        val array = IrArray(i32, 4)
        val entry = function.entryBlock("entry")
        builder.positionAt(function, entry)

        val alloca = builder.emit(IrAlloca("arr", IrPointer(array), array), "arr")
        val elem = builder.emit(
            IrGep("", IrPointer(i32), alloca, listOf(IrConstant(0, i32), IrParameter(0, "idx", i32)))
        )
        builder.emit(IrStore("", unit, elem, IrConstant(3, i32)))
        val loaded = builder.emit(IrLoad("", i32, elem))
        builder.emitTerminator(IrReturn("", i32, loaded))

        SroaPass().run(module, function)

        val rendered = function.render()
        assertTrue(rendered.contains("alloca [4 x i32]"), "dynamic index array must not be split")
        assertTrue(rendered.contains("getelementptr"), "dynamic index gep must remain")
    }

    @Test
    fun `expands whole aggregate copy before splitting`() {
        val (module, function, builder) = newFunction("sroaWholeCopy.", unit)
        val point = IrStruct("Point", listOf(i32, i32))
        val entry = function.entryBlock("entry")
        builder.positionAt(function, entry)

        val src = builder.emit(IrAlloca("src", IrPointer(point), point), "src")
        val dst = builder.emit(IrAlloca("dst", IrPointer(point), point), "dst")
        val value = builder.emit(IrLoad("", point, src))
        builder.emit(IrStore("", unit, dst, value))
        builder.emitTerminator(IrReturn("", unit, null))

        SroaPass().run(module, function)

        val rendered = function.render()
        assertFalse(rendered.contains("%src = alloca %Point"), "whole load source should be split")
        assertFalse(rendered.contains("%dst = alloca %Point"), "whole store destination should be split")
        assertFalse(rendered.contains("load %Point"), "whole aggregate load should be scalarized")
        assertFalse(rendered.contains("store %Point"), "whole aggregate store should be scalarized")
        assertTrue(rendered.contains("load i32"), "scalarized copy should load scalar fields")
        assertTrue(rendered.contains("store i32"), "scalarized copy should store scalar fields")
    }

    @Test
    fun `whole aggregate copy keeps load snapshot before later source writes`() {
        val (module, function, builder) = newFunction("sroaWholeCopySnapshot.", unit)
        val point = IrStruct("Point", listOf(i32, i32))
        val entry = function.entryBlock("entry")
        builder.positionAt(function, entry)

        val src = builder.emit(IrAlloca("src", IrPointer(point), point), "src")
        val dst = builder.emit(IrAlloca("dst", IrPointer(point), point), "dst")
        val field = builder.emit(
            IrGep("", IrPointer(i32), src, listOf(IrConstant(0, i32), IrConstant(0, i32)))
        )
        val value = builder.emit(IrLoad("", point, src))
        builder.emit(IrStore("", unit, field, IrConstant(99, i32)))
        builder.emit(IrStore("", unit, dst, value))
        builder.emitTerminator(IrReturn("", unit, null))

        SroaPass().run(module, function)

        val rendered = function.render()
        val scalarLoadIndex = rendered.indexOf("load i32")
        val laterStoreIndex = rendered.indexOf("store i32 99")
        assertTrue(scalarLoadIndex >= 0, "aggregate load should become scalar loads")
        assertTrue(laterStoreIndex >= 0, "later scalar field store should remain")
        assertTrue(
            scalarLoadIndex < laterStoreIndex,
            "scalar leaf loads must stay at the original aggregate load position",
        )
    }

    @Test
    fun `rejects whole aggregate load that escapes non-store use`() {
        val (module, function, builder) = newFunction("sroaWholeEscape.", pointReturnType())
        val point = pointReturnType()
        val entry = function.entryBlock("entry")
        builder.positionAt(function, entry)

        val src = builder.emit(IrAlloca("src", IrPointer(point), point), "src")
        val value = builder.emit(IrLoad("", point, src))
        builder.emitTerminator(IrReturn("", point, value))

        SroaPass().run(module, function)

        val rendered = function.render()
        assertTrue(rendered.contains("%src = alloca %Point"), "escaping aggregate load source must remain")
        assertTrue(rendered.contains("load %Point"), "escaping aggregate load must remain")
    }

    private fun pointReturnType(): IrStruct = IrStruct("Point", listOf(i32, i32))

    private fun newFunction(
        name: String,
        returnType: IrType,
        parameters: List<IrType> = emptyList(),
        parameterNames: List<String> = emptyList(),
    ): Triple<IrModule, IrFunction, IrBuilder> {
        val module = IrModule()
        val function = IrFunction(
            name = name,
            signature = IrFunctionSignature(parameters = parameters, returnType = returnType),
            parameterNames = parameterNames,
        )
        module.declareFunction(function)
        return Triple(module, function, IrBuilder(module))
    }
}
