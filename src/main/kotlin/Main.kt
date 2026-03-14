import backend.ir.IrBackend
import frontend.RLexer
import frontend.RParser
import frontend.RPreprocessor
import frontend.semantic.*
import utils.*
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = CliOptions.parse(args)

    if (options.showHelp) {
        System.err.println(CliOptions.HELP_TEXT)
        exitProcess(0)
    }

    val filePath = options.inputPath

    val rawText: String = when {
        filePath == null || filePath == "-" -> generateSequence { readlnOrNull() }.joinToString("\n")
        else -> loadFileOrResource(filePath)
    }

    try {
        // ── Frontend ────────────────────────────────────────────────────
        if (options.debugParse) System.err.println("----- 1. Preprocessing -----")
        val preprocessor = RPreprocessor(rawText)
        val processedText = preprocessor.process()

        if (options.debugParse) System.err.println("\n----- 2. Lexing -----")
        val lexer = RLexer(processedText)
        val tokens = lexer.process()

        if (options.debugParse) System.err.println("\n----- 3. Parsing -----")
        val parser = RParser(tokens)
        val crate = parser.process()

        val debugSemantic = options.debugSemantic
        if (debugSemantic) System.err.println("\n----- 4. Semantic Analysis -----")
        val preludeScope = toPrelude()

        if (debugSemantic) System.err.println("\n--- Running Pass 1: Symbol Collector ---")
        val symbolCollector = RSymbolCollector(preludeScope, crate)
        symbolCollector.process()
        if (debugSemantic) {
            val collectorDumper = RSymbolTableDumper(crate)
            collectorDumper.dump()
        }

        if (debugSemantic) System.err.println("\n--- Running Pass 2: Symbol Resolver ---")
        val symbolResolver = RSymbolResolver(preludeScope, crate)
        symbolResolver.process()
        if (debugSemantic) {
            val resolverDumper = RResolvedSymbolDumper(crate)
            resolverDumper.dump()
        }

        if (debugSemantic) System.err.println("\n--- Running Pass 3: Impl Injector ---")
        val implInjector = RImplInjector(preludeScope, crate)
        implInjector.process()
        if (debugSemantic) {
            val injectionDumper = RImplInjectionDumper(crate)
            injectionDumper.dump()
        }

        if (debugSemantic) System.err.println("\n--- Running Pass 4: Semantic Checker ---")
        val checker: RSemanticChecker =
            if (debugSemantic) TracedSemanticChecker(preludeScope, crate) else RSemanticChecker(preludeScope, crate)
        checker.process()

        // ── Backend ─────────────────────────────────────────────────────
        try {
            val backend = IrBackend(enableOptimization = options.optimize)

            val outputText: String = when (options.emit) {
                EmitMode.IR -> {
                    val irText = backend.generate(crate, preludeScope)
                    if (options.debugIr) {
                        System.err.println("\n✅ IR generation successful!")
                        System.err.println(irText)
                    }
                    irText
                }
                EmitMode.ASM -> {
                    // When emitting assembly, also dump IR if its debug flag is on.
                    if (options.debugIr) {
                        val irText = backend.generate(crate, preludeScope)
                        System.err.println("\n✅ IR (before codegen):")
                        System.err.println(irText)
                    }
                    val asmText = backend.generateAsm(crate, preludeScope, debugDump = options.debugCodegen)
                    if (options.debugCodegen) {
                        System.err.println("\n✅ Codegen successful!")
                        System.err.println(asmText)
                    }
                    asmText
                }
            }

            // Write output to file or stdout.
            val outPath = options.outputPath
            if (outPath != null && outPath != "-") {
                val p = Paths.get(outPath)
                p.parent?.let { parent -> Files.createDirectories(parent) }
                Files.writeString(p, outputText)
                if (options.debugIr || options.debugCodegen) {
                    System.err.println("\n[debug] Output written to ${p.toAbsolutePath()}")
                }
            } else {
                println(outputText)
            }
        } catch (e: Exception) {
            return
        }

        exitProcess(0)
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
