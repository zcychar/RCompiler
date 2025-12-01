package backend.ir

import frontend.semantic.ArrayType
import frontend.semantic.BoolType
import frontend.semantic.CharType
import frontend.semantic.ErrorType
import frontend.semantic.ISizeType
import frontend.semantic.Int32Type
import frontend.semantic.IntType
import frontend.semantic.NeverType
import frontend.semantic.RefType
import frontend.semantic.SelfType
import frontend.semantic.StrType
import frontend.semantic.StringType
import frontend.semantic.StructType
import frontend.semantic.Type
import frontend.semantic.USizeType
import frontend.semantic.UInt32Type
import frontend.semantic.UnitType

class TypeMapper {
    fun toIrType(type: Type): IrType = when (type) {
        is BoolType -> IrPrimitive(PrimitiveKind.BOOL)
        is CharType -> IrPrimitive(PrimitiveKind.CHAR)
        is Int32Type -> IrPrimitive(PrimitiveKind.I32)
        is UInt32Type -> IrPrimitive(PrimitiveKind.U32)
        is ISizeType -> IrPrimitive(PrimitiveKind.ISIZE)
        is USizeType -> IrPrimitive(PrimitiveKind.USIZE)
        is UnitType -> IrPrimitive(PrimitiveKind.UNIT)
        is NeverType -> IrPrimitive(PrimitiveKind.NEVER)
        is IntType -> IrPrimitive(PrimitiveKind.I32)
        is StrType -> IrSlice(IrPrimitive(PrimitiveKind.CHAR))
        is StringType -> IrStruct("String", listOf(IrPointer(IrPrimitive(PrimitiveKind.CHAR)), IrPrimitive(PrimitiveKind.U32)))
        is RefType -> IrPointer(toIrType(type.baseType))
        is ArrayType -> IrArray(toIrType(type.elementType), type.size)
        is StructType -> IrStruct(type.name, type.fields.values.map { toIrType(it) })
        is SelfType -> IrPointer(IrOpaque("Self"))
        is ErrorType -> IrOpaque("error")
        else -> IrOpaque(type.toString())
    }

    fun functionSignature(paramTypes: List<Type>, returnType: Type): IrFunctionSignature {
        val params = paramTypes.map { toIrType(it) }
        val ret = toIrType(returnType)
        return IrFunctionSignature(params, ret)
    }
}
