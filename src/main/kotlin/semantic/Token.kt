package semantic

enum class TokenType(val value: String?) {
    KW_As("as"),
    KW_Break("break"),
    KW_Const("const"),
    KW_Continue("continue"),
    KW_Crate("crate"),
    KW_Else("else"),
    KW_Enum("enum"),
    KW_False("false"),
    KW_Fn("fn"),
    KW_For("for"),
    KW_If("if"),
    KW_Impl("impl"),
    KW_In("in"),
    KW_Let("let"),
    KW_Loop("loop"),
    KW_Match("match"),
    KW_Mod("mod"),
    KW_Move("move"),
    KW_Mut("mut"),
    KW_Ref("ref"),
    KW_Return("return"),
    KW_Self("self"),
    KW_SelfUpper("Self"),
    KW_Static("static"),
    KW_Struct("struct"),
    KW_Super("super"),
    KW_Trait("trait"),
    KW_True("true"),
    KW_Type("type"),
    KW_Unsafe("unsafe"),
    KW_Use("use"),
    KW_Where("where"),
    KW_While("while"),
    KW_Dyn("dyn"),
    KW_Abstract("abstract"),
    KW_Become("become"),
    KW_Box("box"),
    KW_Do("do"),
    KW_Final("final"),
    KW_Macro("macro"),
    KW_Override("override"),
    KW_Priv("priv"),
    KW_Typeof("typeof"),
    KW_Unsized("unsized"),
    KW_Virtual("virtual"),
    KW_Yield("yield"),
    KW_Try("try"),
    KW_Gen("gen"),

    ID("[a-zA-Z][_a-zA-Z0-9]*"),
    //identifier-no longer than 64 characters

    CHAR("""'(?: [^'\\\n\r\t] | \\(?:'|"|x[0-7][0-9a-fA-F]|n|r|t|\\|0) ) '(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    STRING("""" (?: [^"\\\r] | \\(?:'|"|x[0-7][0-9a-fA-F]|n|r|t|\\|0) | \\\n )* "(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    RAW_STRING("""r(#{0,255})"[\x00-\x0C\x0E-\x7F]*?"\1(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    BYTE("""b' (?: [\x00-\x09\x0B\x0C\x0E-\x26\x28-\x5B\x5D-\x7F] | \\(?:x[0-7][0-9a-fA-F]|n|r|t|\\|0|'|") ) '(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    BYTE_STRING("""b" (?: [\x00-\x0C\x0E-\x21\x23-\x5B\x5D-\x7F] | \\(?:x[0-7][0-9a-fA-F]|n|r|t|\\|0|'|") | \\\n )* "(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    RAW_BYTE_STRING("""br(#{0,255})"[\x00-\x0C\x0E-\x7F]*?"\1(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    C_STRING("""c" (?: [^"\\\r\x00] | \\(?:x(?!00)[0-7][0-9a-fA-F]|n|r|t|\\|'|") | \\\n )* "(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    RAW_C_STRING("""cr(#{0,255})"[\x01-\x0C\x0E-\x7F]*?"\1(?:[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    INTEGER("""(?:0b[01][01_]*|0o[0-7][0-7_]*|0x[0-9a-fA-F][0-9a-fA-F_]*|[0-9][0-9_]*)(?:(?!e|E)[a-zA-Z_][a-zA-Z0-9_]*)?"""),
    FLOAT("""(?:[0-9][0-9_]*\.(?![._a-zA-Z])|[0-9][0-9_]*\.[0-9][0-9_]*(?:(?!e|E)[a-zA-Z_][a-zA-Z0-9_]*)?)"""),


    None(null);


}

class Token(val type: TokenType, val value: String) {
    override fun toString(): String {
        return "Token(type=$type, value='$value')"
    }
}

