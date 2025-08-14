import frontend.*
import utils.CompileError

fun main(args: Array<String>) {

    val resourcePath = if(args.isEmpty()) readln() else args[0]
    val inputStream = object {}.javaClass.getResourceAsStream(resourcePath)
    val rawText = inputStream.bufferedReader().readText()
    try{
        println("-----preprocessed-----")
        val preprocessor = RPreprocessor(rawText)
        val lexer = RLexer(preprocessor.process())
        println(preprocessor.dumpToString())
        println("--------tokens---------")
        lexer.process()
        println(lexer.dumpToString())
    }catch(e: CompileError) {
        println(e.message)
    }

}