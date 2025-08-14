import frontend.*
import utils.CompileError

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Error: Please provide a path to a source file.")
        return
    }
    val resourcePath = "string2.rs"
    val inputStream = object {}.javaClass.getResourceAsStream(resourcePath)
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