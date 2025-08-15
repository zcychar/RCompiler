package frontend.AST

import frontend.Punctuation

val Punctuation.prefixPower: Int?
    get() = when (this) {
        Punctuation.MINUS -> 200
        Punctuation.BANG -> 200
        else ->   null
    }

val Punctuation.infixPower:Pair<Int,Int>?
    get() = when (this){
        Punctuation.EQUAL -> 20 to 10
        else -> null
    }