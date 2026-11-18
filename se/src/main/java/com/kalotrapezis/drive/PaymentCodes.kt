package com.kalotrapezis.drive

/** RF creditor references (ISO 11649), the payment codes printed on Greek bills. */
object PaymentCodes {
    private val candidate = Regex("""RF\s?\d{2}(?:\s?[A-Z0-9]){1,21}""")

    /** First checksum-valid RF reference in [text], without spaces; tolerates the grouping spaces bills print. */
    fun findRf(text: String): String? = candidate.findAll(text.uppercase()).firstNotNullOfOrNull { match ->
        // The pattern can swallow a following word; try the longest valid prefix first.
        val compact = match.value.replace(" ", "")
        (compact.length downTo 5).asSequence().map { compact.substring(0, it) }.firstOrNull(::isValidRf)
    }

    fun isValidRf(code: String): Boolean {
        if (code.length !in 5..25 || !code.startsWith("RF") || !code.all { it.isDigit() || it in 'A'..'Z' }) return false
        val rearranged = code.substring(4) + code.substring(0, 4)
        var remainder = 0
        rearranged.forEach { char ->
            val digits = if (char.isDigit()) char.toString() else (char - 'A' + 10).toString()
            digits.forEach { remainder = (remainder * 10 + (it - '0')) % 97 }
        }
        return remainder == 1
    }

    fun format(code: String): String = code.chunked(4).joinToString(" ")
}
