import frontend.*
import semantic.SemanticAnalyzer
import semantic.SemanticAnalysisDumper
import utils.CompileError
import utils.dumpToString

fun main(args: Array<String>) {

    val resourcePath = if(args.isEmpty()) readln() else args[0]
    val inputStream = object {}.javaClass.getResourceAsStream(resourcePath)
    val rawText = inputStream?.bufferedReader()!!.readText()
    try{
        println("-----preprocessed-----")
        val preprocessor = RPreprocessor(rawText)
        val lexer = RLexer(preprocessor.process())
        println(preprocessor.dumpToString())
        println("--------tokens---------")
        val parser= RParser(lexer.process())
        println(lexer.dumpToString())
        val ast = parser.process()
        println("--------AST---------")
        println(parser.dumpToString())
        
        // Add semantic analysis
        println("----semantic analysis----")
        val semanticAnalyzer = SemanticAnalyzer()
        val analysisResult = semanticAnalyzer.analyze(ast)
        println(SemanticAnalysisDumper.dumpAnalysisResult(analysisResult))
        
        if (analysisResult.hasErrors) {
            println("Compilation failed due to semantic errors.")
        } else {
            println("Semantic analysis completed successfully!")
        }
        
    }catch(e: CompileError) {
        println(e.message)
    }

}