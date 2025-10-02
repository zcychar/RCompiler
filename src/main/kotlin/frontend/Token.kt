package frontend

sealed interface TokenType

data class Token(val type: TokenType, val value: String) {
  override fun toString(): String {
    return "Token(type=$type, value='$value')"
  }
}

enum class Keyword(val id: String) : TokenType {
  AS("as"),
  BREAK("break"),
  CONST("const"),
  CONTINUE("continue"),
  CRATE("crate"),
  ELSE("else"),
  ENUM("enum"),
  FALSE("false"),
  FN("fn"),
  FOR("for"),
  IF("if"),
  IMPL("impl"),
  IN("in"),
  LET("let"),
  LOOP("loop"),
  MATCH("match"),
  MOD("mod"),
  MOVE("move"),
  MUT("mut"),
  REF("ref"),
  RETURN("return"),
  SELF("self"),
  SELF_UPPER("Self"), // For 'Self'
  STATIC("static"),
  STRUCT("struct"),
  SUPER("super"),
  TRAIT("trait"),
  TRUE("true"),
  TYPE("type"),
  UNSAFE("unsafe"),
  USE("use"),
  WHERE("where"),
  WHILE("while"),
  DYN("dyn"),
  ABSTRACT("abstract"),
  BECOME("become"),
  BOX("box"),
  DO("do"),
  FINAL("final"),
  MACRO("macro"),
  OVERRIDE("override"),
  PRIV("priv"),
  TYPEOF("typeof"),
  UNSIZED("unsized"),
  VIRTUAL("virtual"),
  YIELD("yield"),
  TRY("try"),
  GEN("gen");


  companion object {
    private val byId = Keyword.entries.associateBy { it.id }

    fun fromId(id: String): Keyword? = byId[id]
  }
}

enum class Punctuation(val id: String) : TokenType {
  EQUAL("="),                  // =
  LESS("<"),                   // <
  LESS_EQUAL("<="),            // <=
  EQUAL_EQUAL("=="),           // ==
  NOT_EQUAL("!="),             // !=
  GREATER_EQUAL(">="),         // >=
  GREATER(">"),                // >
  AND_AND("&&"),               // &&
  OR_OR("||"),                 // ||
  BANG("!"),                   // !
  TILDE("~"),                  // ~
  PLUS("+"),                   // +
  MINUS("-"),                  // -
  STAR("*"),                   // *
  SLASH("/"),                  // /
  PERCENT("%"),                // %
  CARET("^"),                  // ^
  AMPERSAND("&"),              // &
  PIPE("|"),                   // |
  LESS_LESS("<<"),             // <<
  GREATER_GREATER(">>"),       // >>
  PLUS_EQUAL("+="),            // +=
  MINUS_EQUAL("-="),           // -=
  STAR_EQUAL("*="),            // *=
  SLASH_EQUAL("/="),           // /=
  PERCENT_EQUAL("%="),         // %=
  CARET_EQUAL("^="),           // ^=
  AND_EQUAL("&="),             // &=
  OR_EQUAL("|="),              // |=
  LESS_LESS_EQUAL("<<="),      // <<=
  GREATER_GREATER_EQUAL(">>="),// >>=
  AT("@"),                     // @
  DOT("."),                    // .
  DOT_DOT(".."),               // ..
  DOT_DOT_DOT("..."),          // ...
  DOT_DOT_EQUAL("..="),        // ..=
  COMMA(","),                  // ,
  SEMICOLON(";"),              // ;
  COLON(":"),                  // :
  COLON_COLON("::"),           // ::
  RIGHT_ARROW("->"),           // ->
  LEFT_ARROW("<-"),            // <-
  FAT_ARROW("=>"),             // =>
  HASH("#"),                   // #
  DOLLAR("$"),                 // $
  QUESTION("?"),               // ?
  UNDERSCORE("_"),             // _
  LEFT_BRACE("{"),             // {
  RIGHT_BRACE("}"),            // }
  LEFT_BRACKET("["),           // [
  RIGHT_BRACKET("]"),          // ]
  LEFT_PAREN("("),             // (
  RIGHT_PAREN(")");            // )

  companion object {
    private val byId = Punctuation.entries.associateBy { it.id }

    fun fromId(id: String): Punctuation? = byId[id]
  }
}

enum class Literal : TokenType {
  CHAR,
  STRING,
  C_STRING,
  RAW_STRING,
  RAW_C_STRING,
  INTEGER,
}

data object Identifier : TokenType
data object ErrorToken : TokenType

val unaryOp = setOf(
  Punctuation.MINUS, Punctuation.BANG,
)

val binaryOp = setOf(
  Punctuation.PLUS, Punctuation.MINUS, Punctuation.STAR, Punctuation.SLASH, Punctuation.PERCENT,
  Punctuation.EQUAL_EQUAL, Punctuation.NOT_EQUAL, Punctuation.LESS, Punctuation.LESS_EQUAL,
  Punctuation.GREATER, Punctuation.GREATER_EQUAL,
  Punctuation.AND_AND, Punctuation.OR_OR,
  Punctuation.AMPERSAND, Punctuation.PIPE, Punctuation.CARET,
  Punctuation.LESS_LESS, Punctuation.GREATER_GREATER,
  Punctuation.EQUAL, Punctuation.PLUS_EQUAL, Punctuation.MINUS_EQUAL, Punctuation.STAR_EQUAL,
  Punctuation.SLASH_EQUAL, Punctuation.PERCENT_EQUAL, Punctuation.CARET_EQUAL,
  Punctuation.AND_EQUAL, Punctuation.OR_EQUAL, Punctuation.LESS_LESS_EQUAL, Punctuation.GREATER_GREATER_EQUAL,
  Punctuation.RIGHT_ARROW, Punctuation.LEFT_ARROW, Punctuation.FAT_ARROW,
)

val assignOp = setOf(

  Punctuation.EQUAL, Punctuation.PLUS_EQUAL, Punctuation.MINUS_EQUAL, Punctuation.STAR_EQUAL,
  Punctuation.SLASH_EQUAL, Punctuation.PERCENT_EQUAL, Punctuation.CARET_EQUAL,
  Punctuation.AND_EQUAL, Punctuation.OR_EQUAL, Punctuation.LESS_LESS_EQUAL, Punctuation.GREATER_GREATER_EQUAL,
)

val precedence = mapOf<TokenType, Pair<Int, Int>>(
  Keyword.AS to Pair(121, 120),
  Punctuation.PLUS to Pair(101, 100),
  Punctuation.MINUS to Pair(101, 100),
  Punctuation.STAR to Pair(111, 110),
  Punctuation.SLASH to Pair(111, 110),
  Punctuation.PERCENT to Pair(111, 110),
  Punctuation.EQUAL_EQUAL to Pair(51, 50),
  Punctuation.NOT_EQUAL to Pair(51, 50),
  Punctuation.LESS to Pair(51, 50),
  Punctuation.LESS_EQUAL to Pair(51, 50),
  Punctuation.GREATER to Pair(51, 50),
  Punctuation.GREATER_EQUAL to Pair(51, 50),
  Punctuation.AND_AND to Pair(41, 40),
  Punctuation.OR_OR to Pair(31, 30),
  Punctuation.AMPERSAND to Pair(81, 80),
  Punctuation.PIPE to Pair(61, 60),
  Punctuation.CARET to Pair(71, 70),
  Punctuation.LESS_LESS to Pair(91, 90),
  Punctuation.GREATER_GREATER to Pair(91, 90),
  Punctuation.EQUAL to Pair(10, 11),
  Punctuation.PLUS_EQUAL to Pair(10, 11),
  Punctuation.MINUS_EQUAL to Pair(10, 11),
  Punctuation.STAR_EQUAL to Pair(10, 11),
  Punctuation.SLASH_EQUAL to Pair(10, 11),
  Punctuation.PERCENT_EQUAL to Pair(10, 11),
  Punctuation.CARET_EQUAL to Pair(10, 11),
  Punctuation.AND_EQUAL to Pair(10, 11),
  Punctuation.OR_EQUAL to Pair(10, 11),
  Punctuation.LESS_LESS_EQUAL to Pair(10, 11),
  Punctuation.GREATER_GREATER_EQUAL to Pair(10, 11),
  Punctuation.DOT_DOT to Pair(21, 20),
  Punctuation.DOT_DOT_EQUAL to Pair(21, 20)
)

fun getInfixPrecedence(op: TokenType?): Int {
  return when {
    (op as TokenType) in binaryOp -> precedence[op]?.first ?: 0
    op == Punctuation.DOT -> 200
    op == Punctuation.LEFT_PAREN -> 200
    op == Punctuation.LEFT_BRACKET -> 200
    op == Punctuation.LEFT_BRACE -> 200
    op == Keyword.AS -> 200
    else -> 0
  }
}

