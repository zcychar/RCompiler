import backend.ir.IrBackend
import frontend.RLexer
import frontend.RParser
import frontend.RPreprocessor
import frontend.semantic.RImplInjector
import frontend.semantic.RSemanticChecker
import frontend.semantic.RSymbolCollector
import frontend.semantic.RSymbolResolver
import frontend.semantic.toPrelude
import utils.CompileError
import utils.RImplInjectionDumper
import utils.RResolvedSymbolDumper
import utils.RSymbolTableDumper
import utils.TracedSemanticChecker
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val isDebugMode = args.contains("--debug")
    val filePath = args.firstOrNull { !it.startsWith("--") }

    val rawText: String = when {
        filePath == null || filePath == "-" -> generateSequence { readlnOrNull() }.joinToString("\n")
        else -> loadFileOrResource(filePath)
    }

    try {
        if (isDebugMode) println("----- 1. Preprocessing -----")
        val preprocessor = RPreprocessor(rawText)
        val processedText = preprocessor.process()

        if (isDebugMode) {
            println("\n----- 2. Lexing -----")
        }
        val lexer = RLexer(processedText)
        val tokens = lexer.process()

        if (isDebugMode) println("\n----- 3. Parsing -----")
        val parser = RParser(tokens)
        val crate = parser.process()

        if (isDebugMode) {
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

        val checker: RSemanticChecker =
            if (isDebugMode) TracedSemanticChecker(preludeScope, crate) else RSemanticChecker(preludeScope, crate)
        checker.process()

        // Drive IR backend after successful semantics.
        val backend = IrBackend()
        val irText = backend.generate(crate, preludeScope)

        if (isDebugMode) {
            println("\n✅ Compilation successful!")
            println(irText)
        } else {
            println(irText)
        }

    } catch (e: CompileError) {
        System.err.println("\n❌ Compilation failed:")
        System.err.println("   ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {

        System.err.println("\n💥 An internal compiler error occurred:")
        e.printStackTrace()
        exitProcess(1)
    }
}

private fun loadFileOrResource(path: String): String {
    val fsPath = java.nio.file.Paths.get(path)
    val resourcePath = java.nio.file.Paths.get("src/main/resources").resolve(path)
    val builtResourcePath = java.nio.file.Paths.get("build/resources/main").resolve(path)
    listOf(fsPath, resourcePath, builtResourcePath).firstOrNull { java.nio.file.Files.exists(it) }?.let {
        return java.nio.file.Files.readString(it)
    }
    val stream = object {}.javaClass.classLoader.getResourceAsStream(path)
        ?: object {}.javaClass.getResourceAsStream("/$path")
    if (stream != null) {
        return stream.bufferedReader().readText()
    }
    System.err.println("Error: File not found at path '$path'")
    val workingDir = java.nio.file.Paths.get("").toAbsolutePath()
    System.err.println("Working dir: $workingDir")
    System.err.println("Tried paths:\n  $fsPath\n  $resourcePath\n  $builtResourcePath")
    exitProcess(1)
}
