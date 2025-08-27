package utils

import frontend.AST.*
import frontend.RParser

fun RParser.dumpToString(): String {
    return crate.dumpToString()
}

fun CrateNode.dumpToString(indent: Int = 0): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}CrateNode {\n")
    for (item in this.items) {
        builder.append(item.dumpToString(indent + 2))
    }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun ItemNode.dumpToString(indent: Int): String {
    return when (this) {
        is FunctionItemNode -> this.dumpToString(indent)
        is StructItemNode -> this.dumpToString(indent)
        is EnumItemNode -> this.dumpToString(indent)
        is ConstItemNode -> this.dumpToString(indent)
        is TraitItemNode -> this.dumpToString(indent)
        is ImplItemNode -> this.dumpToString(indent)
    }
}

fun FunctionItemNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}FunctionItemNode (isConst=$isConst) {\n")
    builder.append("${padding}  name: $name\n")
    this.selfParam?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}  funParams: [\n")
    for (param in this.funParams) {
        builder.append(param.dumpToString(indent + 4))
    }
    builder.append("${padding}  ]\n")
    this.returnType?.let { builder.append("${padding}  returnType: ${it.dumpToString(indent + 4)}\n") }
    this.body?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun FunctionItemNode.SelfParamNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}SelfParamNode (hasBorrow=$hasBorrow, hasMut=$hasMut, type=${type?.dumpToString(0)})\n")
    return builder.toString()
}

fun FunctionItemNode.FunParamNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}FunParamNode {\n")
    builder.append(pattern.dumpToString(indent + 2))
    builder.append(type.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun StructItemNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}StructItemNode (name=$name) {\n")
    for (field in this.fields) {
        builder.append(field.dumpToString(indent + 2))
    }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun StructItemNode.StructField.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}StructField (name=$name) {\n")
    builder.append(type.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun EnumItemNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}EnumItemNode (name=$name) {\n")
    builder.append("${padding}  variants: ${variants.joinToString(", ")}\n")
    builder.append("${padding}}\n")
    return builder.toString()
}

fun ConstItemNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}ConstItemNode (id=$id) {\n")
    builder.append(type.dumpToString(indent + 2))
    this.expr?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun TraitItemNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}TraitItemNode (id=$name) {\n")
    for (item in this.items) {
        builder.append(item.dumpToString(indent + 2))
    }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun ImplItemNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}ImplItemNode (id=$id) {\n")
    builder.append(type.dumpToString(indent + 2))
    for (item in this.items) {
        builder.append(item.dumpToString(indent + 2))
    }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun StmtNode.dumpToString(indent: Int): String {
    return when (this) {
        is ItemStmtNode -> this.dumpToString(indent)
        is LetStmtNode -> this.dumpToString(indent)
        is ExprStmtNode -> this.dumpToString(indent)
        is NullStmtNode -> this.dumpToString(indent)
    }
}

fun ItemStmtNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}ItemStmtNode {\n")
    builder.append(this.item.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun LetStmtNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}LetStmtNode {\n")
    builder.append(this.pattern.dumpToString(indent + 2))
    this.type?.let { builder.append(it.dumpToString(indent + 2)) }
    this.expr?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun ExprStmtNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}ExprStmtNode {\n")
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun NullStmtNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}NullStmtNode\n"
}

fun ExprNode.dumpToString(indent: Int): String {
    return when (this) {
        is BlockExprNode -> this.dumpToString(indent)
        is LoopExprNode -> this.dumpToString(indent)
        is WhileExprNode -> this.dumpToString(indent)
        is BreakExprNode -> this.dumpToString(indent)
        is ReturnExprNode -> this.dumpToString(indent)
        is ContinueExprNode -> this.dumpToString(indent)
        is IfExprNode -> this.dumpToString(indent)
        is FieldAccessExprNode -> this.dumpToString(indent)
        is MethodCallExprNode -> this.dumpToString(indent)
//        is MatchExprNode -> this.dumpToString(indent)
        is CallExprNode -> this.dumpToString(indent)
        is CondExprNode -> this.dumpToString(indent)
        is LiteralExprNode -> this.dumpToString(indent)
        is IdentifierExprNode -> this.dumpToString(indent)
        is PathExprNode -> this.dumpToString(indent)
        is ArrayExprNode -> this.dumpToString(indent)
        is IndexExprNode -> this.dumpToString(indent)
        is StructExprNode -> this.dumpToString(indent)
        is UnderscoreExprNode -> this.dumpToString(indent)
        is UnaryExprNode -> this.dumpToString(indent)
        is BinaryExprNode -> this.dumpToString(indent)
    }
}

fun BlockExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}BlockExprNode (hasConst=$hasConst) {\n")
    for (stmt in this.stmts) {
        builder.append(stmt.dumpToString(indent + 2))
    }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun LoopExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}LoopExprNode {\n")
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun WhileExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}WhileExprNode {\n")
    for (cond in this.conds) {
        builder.append(cond.dumpToString(indent + 2))
    }
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun BreakExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}BreakExprNode {\n")
    this.expr?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun ReturnExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}ReturnExprNode {\n")
    this.expr?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun ContinueExprNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}ContinueExprNode\n"
}

fun IfExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}IfExprNode {\n")
    for (cond in this.conds) {
        builder.append(cond.dumpToString(indent + 2))
    }
    builder.append(this.expr.dumpToString(indent + 2))
    this.elseExpr?.let { builder.append("${padding}  elseExpr: {\n${it.dumpToString(indent + 4)}${padding}  }\n") }
    this.elseIf?.let { builder.append("${padding}  elseIf: {\n${it.dumpToString(indent + 4)}${padding}  }\n") }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun FieldAccessExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}FieldAccessExprNode (id=$id) {\n")
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun MethodCallExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}MethodCallExprNode (pathSeg=${pathSeg.id ?: pathSeg.keyword}) {\n")
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}  params: [\n")
    for (param in this.params) {
        builder.append(param.dumpToString(indent + 4))
    }
    builder.append("${padding}  ]\n")
    builder.append("${padding}}\n")
    return builder.toString()
}

//fun MatchExprNode.dumpToString(indent: Int): String {
//    val builder = StringBuilder()
//    val padding = " ".repeat(indent)
//    builder.append("${padding}MatchExprNode {\n")
//    builder.append(this.scur.dumpToString(indent + 2))
//    builder.append("${padding}  arms: [\n")
//    for ((arm, expr) in this.arms) {
//        builder.append(arm.dumpToString(indent + 4))
//        builder.append(expr.dumpToString(indent + 4))
//    }
//    builder.append("${padding}  ]\n")
//    builder.append("${padding}}\n")
//    return builder.toString()
//}
//
//fun MatchExprNode.MatchArmNode.dumpToString(indent: Int): String {
//    val builder = StringBuilder()
//    val padding = " ".repeat(indent)
//    builder.append("${padding}MatchArmNode {\n")
//    builder.append(this.pattern.dumpToString(indent + 2))
//    this.guard?.let { builder.append(it.dumpToString(indent + 2)) }
//    builder.append("${padding}}\n")
//    return builder.toString()
//}

fun CallExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}CallExprNode {\n")
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}  params: [\n")
    for (param in this.params) {
        builder.append(param.dumpToString(indent + 4))
    }
    builder.append("${padding}  ]\n")
    builder.append("${padding}}\n")
    return builder.toString()
}

fun CondExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}CondExprNode {\n")
    this.pattern?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun LiteralExprNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}LiteralExprNode (value='${value}', type=$type)\n"
}

fun IdentifierExprNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}IdentifierExprNode (value='$value')\n"
}

fun PathExprNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}PathExprNode (seg1=${seg1.id ?: seg1.keyword}, seg2=${seg2?.id ?: seg2?.keyword})\n"
}

fun ArrayExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}ArrayExprNode {\n")
    this.elements?.let {
        builder.append("${padding}  elements: [\n")
        for (element in it) {
            builder.append(element.dumpToString(indent + 4))
        }
        builder.append("${padding}  ]\n")
    }
    this.repeatOp?.let { builder.append("${padding}  repeatOp: ${it.dumpToString(indent + 4)}") }
    this.lengthOp?.let { builder.append("${padding}  lengthOp: ${it.dumpToString(indent + 4)}") }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun IndexExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}IndexExprNode {\n")
    builder.append(this.first.dumpToString(indent + 2))
    builder.append(this.second.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun StructExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}StructExprNode {\n")
    builder.append(this.path.dumpToString(indent + 2))
    builder.append("${padding}  fields: [\n")
    for (field in this.fields) {
        builder.append(field.dumpToString(indent + 4))
    }
    builder.append("${padding}  ]\n")
    builder.append("${padding}}\n")
    return builder.toString()
}

fun StructExprNode.StructExprField.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}StructExprField (id=$id) {\n")
    this.expr?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun UnderscoreExprNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}UnderscoreExprNode\n"
}

fun UnaryExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}UnaryExprNode (op=${op}, hasMut=$hasMut) {\n")
    builder.append(this.rhs.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun BinaryExprNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}BinaryExprNode (op=${op}) {\n")
    builder.append(this.lhs.dumpToString(indent + 2))
    builder.append(this.rhs.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun PatternNode.dumpToString(indent: Int): String {
    return when (this) {
        is LiteralPatternNode -> this.dumpToString(indent)
        is IdentifierPatternNode -> this.dumpToString(indent)
        is RefPatternNode -> this.dumpToString(indent)
        is PathPatternNode -> this.dumpToString(indent)
        is WildcardPatternNode -> this.dumpToString(indent)
    }
}

fun LiteralPatternNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}LiteralPatternNode (hasMinus=$hasMinus) {\n")
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun IdentifierPatternNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}IdentifierPatternNode (hasRef=$hasRef, hasMut=$hasMut, id=$id) {\n")
    this.subPattern?.let { builder.append(it.dumpToString(indent + 2)) }
    builder.append("${padding}}\n")
    return builder.toString()
}

fun RefPatternNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}RefPatternNode (isDouble=$isDouble, hasMut=$hasMut) {\n")
    builder.append(this.pattern.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun PathPatternNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}PathPatternNode {\n")
    builder.append(this.path.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun WildcardPatternNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}WildcardPatternNode\n"
}

fun TypeNode.dumpToString(indent: Int): String {
    return when (this) {
        is TypePathNode -> this.dumpToString(indent)
        is RefTypeNode -> this.dumpToString(indent)
        is ArrayTypeNode -> this.dumpToString(indent)
        is UnitTypeNode -> this.dumpToString(indent)
    }
}

fun TypePathNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}TypePathNode (id=$id, type=$type)\n"
}

fun RefTypeNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}RefTypeNode (hasMut=$hasMut) {\n")
    builder.append(this.type.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}

fun ArrayTypeNode.dumpToString(indent: Int): String {
    val builder = StringBuilder()
    val padding = " ".repeat(indent)
    builder.append("${padding}ArrayTypeNode {\n")
    builder.append(this.type.dumpToString(indent + 2))
    builder.append(this.expr.dumpToString(indent + 2))
    builder.append("${padding}}\n")
    return builder.toString()
}


fun UnitTypeNode.dumpToString(indent: Int): String {
    val padding = " ".repeat(indent)
    return "${padding}UnitTypeNode\n"
}