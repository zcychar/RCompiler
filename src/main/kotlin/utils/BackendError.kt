package utils

fun backendError(message: String): Nothing = CompileError.fail("Backend", message)
