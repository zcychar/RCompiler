import semantic.*

fun main() {
    val a= Keyword.BREAK
    print("${Keyword.valueOf("break")},${a.name}")
//    val resourcePath = "comments.rs"
//    val inputStream = object {}.javaClass.getResourceAsStream(resourcePath)
//    if (inputStream == null) {
//        println("Error: Cannot find resource '$resourcePath'")
//        return
//    }
//    val rawText = inputStream.bufferedReader().readText()
//    print(RPreprocessor.process(rawText))
}