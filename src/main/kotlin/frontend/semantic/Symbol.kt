package frontend.semantic

sealed interface  Symbol

data class FunctionSymbol(val name:String,val type:Type): Symbol

data class ConstItemSymbol(val name:String,val type:Type,val value:Any?): Symbol

data class StructSymbol(val name:String,val type:Type): Symbol

data class EnumVarSymbol(val name:String,val type:Type): Symbol
