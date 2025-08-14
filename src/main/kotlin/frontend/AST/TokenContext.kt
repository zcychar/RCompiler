package frontend.AST

import frontend.Token
import frontend.TokenType
import utils.CompileError

class TokenContext(val input: MutableList<Token>) {
    private var position = 0


    fun peek():Token{
        if(position<input.size)return input[position]
        throw CompileError("Parser:Out_of_size peek requested")
    }

    fun consume(){
        if(position<input.size)position++
        throw CompileError("Parser:Out_of_size consume requested")
    }

    fun expect(type: TokenType): String {
        if(position>=input.size){
            throw CompileError("Parser:Out_of_size expect requested")
        }else if(input[position].type!=type){
            throw CompileError("Parser:Encounter unexpected token ${input[position]}")
        }
        return input[position].value
    }

    fun eof(): Boolean = position < input.size
}