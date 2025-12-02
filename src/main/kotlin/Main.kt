import frontend.RPreprocessor
import frontend.RLexer
import frontend.RParser
import frontend.semantic.*
import backend.ir.IrBackend
import utils.*
import utils.CompileError
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val isDebugMode = args.contains("--debug")
  val filePath = args.firstOrNull { !it.startsWith("--") } ?: readln()

//  if (filePath == null) {
//    println("Usage: java -jar YourCompiler.jar <file_path> [--debug]")
//    return
//  }

  val inputStream = object {}.javaClass.getResourceAsStream(filePath)
  if (inputStream == null) {
    println("Error: File not found at path '$filePath'")
    return
  }

    val rawText = inputStream.bufferedReader().readText()

  try {
    if (isDebugMode) println("----- 1. Preprocessing -----")
    val preprocessor = RPreprocessor(rawText)
    val processedText = preprocessor.process()

    if (isDebugMode) {
//      println(preprocessor.dumpToString())
      println("\n----- 2. Lexing -----")
    }
    val lexer = RLexer(processedText)
    val tokens = lexer.process()

    if (isDebugMode) {
//      println(lexer.dumpToString())
      println("\n----- 3. Parsing -----")
    }
    val parser = RParser(tokens)
    val crate = parser.process()

    if (isDebugMode) {
      println(parser.dumpToString())
      println("\n----- 4. Semantic Analysis -----")
    }

    val preludeScope = toPrelude()

    if (isDebugMode) println("\n--- Running Pass 1: Symbol Collector ---")
    val symbolCollector = RSymbolCollector(preludeScope, crate)
    symbolCollector.process()
    if (isDebugMode) {
      val collectorDumper = RSymbolTableDumper(crate)
      collectorDumper.dump()
    }

    if (isDebugMode) println("\n--- Running Pass 2: Symbol Resolver ---")
    val symbolResolver = RSymbolResolver(preludeScope, crate)
    symbolResolver.process()
    if (isDebugMode) {
      val resolverDumper = RResolvedSymbolDumper(crate)
      resolverDumper.dump()
    }
    if (isDebugMode) println("\n--- Running Pass 3: Impl Injector ---")
    val implInjector = RImplInjector(preludeScope, crate)
    implInjector.process()
    if (isDebugMode) {
      val injectionDumper = RImplInjectionDumper(crate)
      injectionDumper.dump()
    }

    if (isDebugMode) println("\n--- Running Pass 4: Semantic Checker ---")

    val checker: RSemanticChecker = if (isDebugMode) {
      TracedSemanticChecker(preludeScope, crate)
    } else {
      RSemanticChecker(preludeScope, crate)
    }
    checker.process()

    // Drive IR backend after successful semantics.
    val backend = IrBackend()
    val irPath = backend.generate(crate, preludeScope)

    println("\n✅ Compilation successful!")
    if (isDebugMode) {
      println("IR written to $irPath")
    }

  } catch (e: CompileError) {

    println("\n❌ Compilation failed:")
    println("   ${e.message}")
    exitProcess(-1)
  } catch (e: Exception) {

    println("\n💥 An internal compiler error occurred:")
    e.printStackTrace()
    exitProcess(-1)
  }
}
