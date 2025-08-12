import semantic.*

fun main() {
    val resourcePath = "comments.rs"
    val inputStream = object {}.javaClass.getResourceAsStream(resourcePath)
    if (inputStream == null) {
        println("Error: Cannot find resource '$resourcePath'")
        return
    }
    val rawText = inputStream.bufferedReader().readText()
    val preprocessor = RPreprocessor(rawText)
    val lexer = RLexer(preprocessor.process())
    println(preprocessor.dumpToString())
    lexer.process()
    println(lexer.dumpToString())
}