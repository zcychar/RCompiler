import frontend.*
import frontend.semantic.RSymbolCollector
import utils.CompileError
import utils.dumpToString

fun main(args: Array<String>) {

    val resourcePath = if (args.isEmpty()) readln() else args[0]
    val inputStream = object {}.javaClass.getResourceAsStream(resourcePath)
    val rawText = inputStream?.bufferedReader()!!.readText()
    try {
        println("-----preprocessed-----")
        val preprocessor = RPreprocessor(rawText)
        val lexer = RLexer(preprocessor.process())
        println(preprocessor.dumpToString())
        println("--------tokens---------")
        val parser = RParser(lexer.process())
        println(lexer.dumpToString())
        val crate = parser.process()
        println(parser.dumpToString())
    } catch (e: CompileError) {
        println(e.message)
    }

}