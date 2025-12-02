package backend.ir

import frontend.semantic.ArrayType
import frontend.semantic.BoolType
import frontend.semantic.CharType
import frontend.semantic.Enum
import frontend.semantic.EnumType
import frontend.semantic.ErrorType
import frontend.semantic.Function
import frontend.semantic.ISizeType
import frontend.semantic.Int32Type
import frontend.semantic.IntType
import frontend.semantic.NeverType
import frontend.semantic.Namespace
import frontend.semantic.RefType
import frontend.semantic.SelfType
import frontend.semantic.StrType
import frontend.semantic.StringType
import frontend.semantic.Struct
import frontend.semantic.StructType
import frontend.semantic.Type
import frontend.semantic.USizeType
import frontend.semantic.UInt32Type
import frontend.semantic.UnitType

/**
 * Maps semantic types into IR types according to the backend design doc.
 * The mapper keeps a tiny bit of context so `Self` inside methods resolves to the concrete impl type.
 */
class TypeMapper(private val context: CodegenContext) {
    private var selfType: Type? = null

    fun beginMethod(self: Type) {
        selfType = self
    }

    fun endMethod() {
        selfType = null
    }

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
        is StrType -> stringLike(type)
        is StringType -> stringLike(type)
        is RefType -> IrPointer(toIrType(type.baseType), mutable = type.isMutable)
        is ArrayType -> IrArray(toIrType(type.elementType), type.size)
        is StructType -> structLayout(type)
        is EnumType -> IrPrimitive(PrimitiveKind.I32)
        is SelfType -> mapSelf(type)
        is ErrorType -> IrOpaque("error")
        else -> IrOpaque(type.toString())
    }

    fun functionSignature(function: Function): IrFunctionSignature {
        val previousSelf = selfType
        if (function.self != null) {
            beginMethod(function.self!!)
        }

        val params = mutableListOf<IrType>()
        function.selfParam?.let {
            val rawSelf = function.self ?: previousSelf ?: selfType
            val selfSemantic = if (it.isRef) RefType(rawSelf ?: SelfType(false, false), it.isMut) else rawSelf
            params += toIrType(selfSemantic ?: SelfType(isMut = it.isMut, isRef = it.isRef))
        }
        params += function.params.map { param -> toIrType(param.type) }
        val ret = toIrType(function.returnType)

        selfType = previousSelf
        return IrFunctionSignature(params, ret)
    }

    fun structLayout(structType: StructType): IrType =
        IrStruct(structType.name, structType.fields.values.map { fieldType -> toIrType(fieldType) })

    fun stringLike(type: Type): IrType = when (type) {
        is StringType -> IrStruct(
            "String",
            listOf(
                IrPointer(IrPrimitive(PrimitiveKind.CHAR)),
                IrPrimitive(PrimitiveKind.U32),
            ),
        )

        is StrType -> IrSlice(IrPrimitive(PrimitiveKind.CHAR))
        else -> IrOpaque(type.toString())
    }

    private fun mapSelf(self: SelfType): IrType {
        val concrete = selfType
        val base = concrete?.let { toIrType(it) } ?: IrOpaque("Self")
        return if (self.isRef) IrPointer(base, mutable = self.isMut) else base
    }

    fun resolveImplType(scope: frontend.semantic.Scope, impl: frontend.ast.ImplItemNode): Type {
        val path = impl.type as? frontend.ast.TypePathNode
            ?: error("Unsupported impl target ${impl.type}")
        val name = path.name ?: error("impl target missing name")
        return when (val symbol = scope.resolve(name, Namespace.TYPE)) {
            is Struct -> symbol.type
            is Enum -> symbol.type
            else -> error("impl target $name is not a struct or enum")
        }
    }
}
