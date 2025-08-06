package parser


class RLexer{

    val regMap= TokenType.entries.associateWith { it.value?.let { pattern -> Regex(pattern) } };

    fun  getToken() :Token{



    }
}