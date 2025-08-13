package utils

fun Char.isWord(): Boolean {
    return this == '_' || this.isDigit() || this.isLetter()
}