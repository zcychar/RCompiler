package backend.ir

import kotlin.text.buildString

sealed interface IrInstruction {
    val id: Int
    val type: IrType
    fun render(): String
}

sealed interface IrTerminator : IrInstruction

enum class BinaryOperator(val llvmName: String) {
    ADD("add"),
    SUB("sub"),
    MUL("mul"),
    SDIV("sdiv"),
    UDIV("udiv"),
    SREM("srem"),
    UREM("urem"),
    AND("and"),
    OR("or"),
    XOR("xor"),
}

enum class UnaryOperator(val llvmName: String) {
    NEG("neg"),
    NOT("not"),
}

enum class ComparePredicate(val llvmName: String) {
    EQ("icmp eq"),
    NE("icmp ne"),
    SLT("icmp slt"),
    SLE("icmp sle"),
    SGT("icmp sgt"),
    SGE("icmp sge"),
    ULT("icmp ult"),
    ULE("icmp ule"),
    UGT("icmp ugt"),
    UGE("icmp uge"),
}

enum class CastKind(val llvmName: String) {
    BITCAST("bitcast"),
    TRUNC("trunc"),
    ZEXT("zext"),
    SEXT("sext"),
    PTRTOINT("ptrtoint"),
    INTTOPTR("inttoptr"),
}

data class IrConst(
    override val id: Int,
    override val type: IrType,
    val constant: IrConstant,
) : IrInstruction {
    override fun render(): String = "%$id = ${constant.render()}"
}

data class IrAlloca(
    override val id: Int,
    override val type: IrType,
    val allocatedType: IrType,
    val slotName: String,
) : IrInstruction {
    override fun render(): String = "%$id = alloca ${allocatedType.render()} ; $slotName"
}

data class IrLoad(
    override val id: Int,
    override val type: IrType,
    val address: IrValue,
) : IrInstruction {
    override fun render(): String = "%$id = load ${type.render()}, ${address.type.render()} ${address.render()}"
}

data class IrStore(
    override val id: Int,
    override val type: IrType,
    val address: IrValue,
    val value: IrValue,
) : IrInstruction {
    override fun render(): String =
        "store ${value.type.render()} ${value.render()}, ${address.type.render()} ${address.render()}"
}

data class IrBinary(
    override val id: Int,
    override val type: IrType,
    val operator: BinaryOperator,
    val lhs: IrValue,
    val rhs: IrValue,
) : IrInstruction {
    override fun render(): String =
        "%$id = ${operator.llvmName} ${type.render()} ${lhs.render()}, ${rhs.render()}"
}

data class IrUnary(
    override val id: Int,
    override val type: IrType,
    val operator: UnaryOperator,
    val operand: IrValue,
) : IrInstruction {
    override fun render(): String =
        "%$id = ${operator.llvmName} ${type.render()} ${operand.render()}"
}

data class IrCmp(
    override val id: Int,
    override val type: IrType,
    val predicate: ComparePredicate,
    val lhs: IrValue,
    val rhs: IrValue,
) : IrInstruction {
    override fun render(): String =
        "%$id = ${predicate.llvmName} ${lhs.type.render()} ${lhs.render()}, ${rhs.render()}"
}

data class IrCall(
    override val id: Int,
    override val type: IrType,
    val callee: IrFunctionRef,
    val arguments: List<IrValue>,
) : IrInstruction {
    override fun render(): String = buildString {
        append("%").append(id).append(" = call ").append(type.render()).append(' ')
        append(callee.render())
        append('(')
        arguments.forEachIndexed { index, arg ->
            if (index > 0) append(", ")
            append(arg.type.render()).append(' ').append(arg.render())
        }
        append(')')
    }
}

data class IrPhi(
    override val id: Int,
    override val type: IrType,
    val incoming: MutableList<Pair<String, IrValue>>,
) : IrInstruction {
    override fun render(): String = buildString {
        append("%").append(id).append(" = phi ").append(type.render()).append(' ')
        incoming.forEachIndexed { index, (label, value) ->
            if (index > 0) append(", ")
            append("[ ").append(value.render()).append(", %").append(label).append(" ]")
        }
    }
}

data class IrGep(
    override val id: Int,
    override val type: IrType,
    val base: IrValue,
    val indices: List<IrValue>,
) : IrInstruction {
    override fun render(): String = buildString {
        append("%").append(id).append(" = getelementptr ")
        append(base.type.render()).append(", ")
        append(base.type.render()).append(' ').append(base.render())
        this@IrGep.indices.forEach { indexValue ->
            append(", ").append(indexValue.type.render()).append(' ').append(indexValue.render())
        }
    }
}

data class IrCast(
    override val id: Int,
    override val type: IrType,
    val value: IrValue,
    val kind: CastKind,
) : IrInstruction {
    override fun render(): String =
        "%$id = ${kind.llvmName} ${value.type.render()} ${value.render()} to ${type.render()}"
}

data class IrReturn(
    override val id: Int,
    override val type: IrType,
    val value: IrValue?,
) : IrTerminator {
    override fun render(): String = buildString {
        append("ret ")
        if (value == null) {
            append("void")
        } else {
            append(value.type.render()).append(' ').append(value.render())
        }
    }
}

data class IrBranch(
    override val id: Int,
    override val type: IrType,
    val condition: IrValue,
    val trueTarget: String,
    val falseTarget: String,
) : IrTerminator {
    override fun render(): String = buildString {
        append("br ").append(condition.type.render()).append(' ').append(condition.render())
        append(", label %").append(trueTarget)
        append(", label %").append(falseTarget)
    }
}

data class IrJump(
    override val id: Int,
    override val type: IrType,
    val target: String,
) : IrTerminator {
    override fun render(): String = "br label %$target"
}
