package semantic


class RLexer{

    val regMap= TokenType.entries.associateWith { it.value?.let { pattern -> Regex(pattern) } };

}