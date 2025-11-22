package utils

class CompileError(message: String) : Exception(message) {
    companion object {
        fun fail(stage: String, message: String): Nothing {
            val sanitizedStage = stage.trim().ifEmpty { "Error" }
            val trimmedMessage = message.trim().removeSuffix(".")
            val sanitizedMessage = if (trimmedMessage.isNotEmpty()) {
                trimmedMessage.replaceFirstChar { ch ->
                    if (ch.isLetter()) ch.lowercaseChar() else ch
                }
            } else {
                trimmedMessage
            }
            throw CompileError("$sanitizedStage: $sanitizedMessage")
        }
    }
}
