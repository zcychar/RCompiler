package frontend

/**
 * Preprocessor removes all // and nested /* */ pairs in code, passes a clear string to lexer
 * also,it deletes all string_continue escapes in strings
 */
class RPreprocessor(private val input: String) {
    private val data = StringBuilder()
    private var idx = 0
    fun process(): String {
        var nest_comment = 0
        var in_single_comment = false
        var in_string = false
        var in_char = false
        var escaped = false
        while (idx < input.length) {
            val ch = input[idx]
            val nextch = if (idx < input.length - 1) input[idx + 1] else null
            when {
                in_single_comment -> {
                    idx++
                    if (ch == '\n') {
                        if (!data.endsWith(' ') && !data.isEmpty()) data.append(' ')
                        in_single_comment = false
                    }
                    continue
                }

                in_char -> {
                    data.append(ch)
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '\'') {
                        in_char = false
                    }
                    idx++
                    continue
                }

                in_string -> {
                    if (ch == '\\' && nextch == '\n') {
                        idx += 2
                        continue
                    }
                    data.append(if (ch == '\n') "\\n" else ch)
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '\"') {
                        in_string = false
                    }
                    idx++
                    continue
                }

                nest_comment > 0 -> {
                    if (ch == '/' && nextch == '*') {
                        idx += 2
                        nest_comment++
                        continue
                    }
                    if (ch == '*' && nextch == '/') {
                        idx += 2
                        nest_comment--
                        continue
                    }
                    idx++
                    continue
                }
            }
            if (skipRawString()) continue
            when {
                ch == '/' && nextch == '/' -> {
                    in_single_comment = true
                    idx++
                }

                ch == '/' && nextch == '*' -> {
                    nest_comment++
                    idx++
                }

                ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t' -> {
                    if (!data.endsWith(' ') && !data.isEmpty()) data.append(' ')
                }

                ch == '\'' -> {
                    data.append(ch)
                    in_char = true
                }

                ch == '"' -> {
                    data.append(ch)
                    in_string = true
                }

                else -> data.append(ch)
            }
            idx++
        }
        return data.toString()
    }

    private fun skipRawString(): Boolean {
        val nextch = if (idx < input.length - 1) input[idx + 1] else null
        val nextnextch = if (idx < input.length - 2) input[idx + 2] else null

        val skip = { x: Int ->
            Boolean
            var prefix_end = idx + x
            while (prefix_end < input.length && input[prefix_end] == '#') prefix_end++
            if (input[prefix_end] == '\"') {
                val prefix_length = prefix_end - idx - x
                val suffix = '\"' + "#".repeat(prefix_length)
                val suffix_begin = input.indexOf(suffix, prefix_end + 1)
                if (suffix_begin != -1) {
                    data.append(input.substring(idx until suffix_begin + suffix.length))
                    idx = suffix_begin + suffix.length
                    true
                } else false
            }else false
        }

        if (input[idx] == 'r' && (nextch == '#' || nextch == '\"')) return skip(1)
        if (input[idx] == 'c' && nextch == 'r' && (nextnextch == '#' || nextnextch == '\"')) return skip(2)
        return false
    }

    fun dumpToString(): String = data.toString()
}