package frontend.semantic

import utils.CompileError

fun semanticError(message: String): Nothing = CompileError.fail("Semantic", message)
