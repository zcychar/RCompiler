import semantic.*
import utils.CompileError

fun main() {
    val resourcePath = "array1.rs"
    val inputStream = object {}.javaClass.getResourceAsStream(resourcePath)
    if (inputStream == null) {
        println("Error: Cannot find resource '$resourcePath'")
        return
    }
    val rawText = inputStream.bufferedReader().readText()
    try{
        val preprocessor = RPreprocessor(rawText)
        val lexer = RLexer(preprocessor.process())
        println(preprocessor.dumpToString())
        lexer.process()
        println(lexer.dumpToString())
    }catch(e: CompileError) {
        println(e.message)
    }

}