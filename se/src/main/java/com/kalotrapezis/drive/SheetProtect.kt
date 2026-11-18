package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

/**
 * Review › Protect Sheet and Unprotect Sheet (Excel's; LibreOffice's Tools › Protect Sheet): the sheet's locked cells
 * kept from changes, with a password or none. Excel's sheetProtection (its SHA-512 hash, as Excel writes; its old
 * 16-bit hash read too), LibreOffice's table:protected with its SHA-256 key. Unprotecting a sheet with a password asks
 * for it and checks it against the file's hash. A change to the file.
 */
internal object SheetProtect {
    private const val SPIN = 100_000
    private val b64 = Base64.getEncoder()

    /** Excel's SHA-512 hash of [password] with [salt], [spin] times round (ECMA-376's agile hash). */
    fun excelHash(password: String, salt: ByteArray, spin: Int = SPIN, algorithm: String = "SHA-512"): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        var h = md.digest(salt + password.toByteArray(Charsets.UTF_16LE))
        for (i in 0 until spin) h = md.digest(h + byteArrayOf(i.toByte(), (i shr 8).toByte(), (i shr 16).toByte(), (i shr 24).toByte()))
        return h
    }

    /** Excel's old 16-bit password hash, as hex ("CBEB"): what its legacy password attribute holds. */
    fun legacyHash(password: String): String {
        var h = 0
        for (ch in password.reversed()) { h = ((h shr 14) and 1) or ((h shl 1) and 0x7fff); h = h xor ch.code }
        h = ((h shr 14) and 1) or ((h shl 1) and 0x7fff)
        h = h xor password.length xor 0xCE4B
        return "%04X".format(h)
    }

    /** Whether [password] opens sheet protection [attrs] (Excel's sheetProtection's attributes, or LibreOffice's key and algorithm). */
    fun opens(attrs: Map<String, String>, password: String): Boolean {
        attrs["password"]?.let { return it.equals(legacyHash(password), true) }
        attrs["hashValue"]?.let { hash ->
            val algorithm = attrs["algorithmName"] ?: "SHA-512"
            val salt = Base64.getDecoder().decode(attrs["saltValue"].orEmpty())
            return runCatching { b64.encodeToString(excelHash(password, salt, attrs["spinCount"]?.toIntOrNull() ?: 0, algorithm)) == hash }.getOrDefault(false)
        }
        attrs["protection-key"]?.let { key ->
            val algorithm = when { attrs["protection-key-digest-algorithm"].orEmpty().endsWith("sha256") -> "SHA-256"; else -> "SHA-1" }
            val md = MessageDigest.getInstance(algorithm)
            // LibreOffice hashes the words' UTF-8; older files' SHA-1 may be of UTF-16
            return listOf(Charsets.UTF_8, Charsets.UTF_16LE).any { b64.encodeToString(md.digest(password.toByteArray(it))) == key }
        }
        return true // no password
    }

    private val BOOK_PROTECTION = Regex("""<workbookProtection\b[^>]*/>""")

    /** Excel's Protect Workbook (its structure: sheets not added, deleted, renamed, moved or hidden), in workbook part [part]. */
    fun bookXlsx(source: File, target: File, part: String, protect: Boolean, password: String) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(part)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_not_workbook)
            BOOK_PROTECTION.find(x)?.let { m -> if (!protect && !opens(attrs(m.value).filterKeys { it.startsWith("workbook") }
                .mapKeys { (k, _) -> k.removePrefix("workbook").replaceFirstChar { it.lowercase() } }, password)) sheetProblem(R.string.tables_err_book_password) }
            x = x.replace(BOOK_PROTECTION, "")
            if (protect) {
                val hash = if (password.isEmpty()) "" else {
                    val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    """ workbookAlgorithmName="SHA-512" workbookHashValue="${b64.encodeToString(excelHash(password, salt))}" workbookSaltValue="${b64.encodeToString(salt)}" workbookSpinCount="$SPIN""""
                }
                // in the schema's place: before the workbook's views, else its sheets
                val at = Regex("""<bookViews\b|<sheets\b""").find(x)?.range?.first ?: sheetProblem(R.string.tables_err_no_sheets)
                x = x.substring(0, at) + """<workbookProtection$hash lockStructure="1"/>""" + x.substring(at)
            }
            SheetSave.rezip(zip, target, mapOf(part to x.toByteArray()), null)
        }
    }

    /** LibreOffice's Tools › Protect Spreadsheet Structure: office:spreadsheet's structure-protected, with its SHA-256 key. */
    fun bookOds(source: File, target: File, protect: Boolean, password: String) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T
            val book = doc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:office:1.0", "spreadsheet").item(0) as? Element ?: sheetProblem(R.string.tables_err_not_spreadsheet)
            if (!protect && book.getAttributeNS(T, "structure-protected") == "true" && !opens(listOf("protection-key", "protection-key-digest-algorithm")
                    .mapNotNull { a -> book.getAttributeNS(T, a).takeIf { it.isNotEmpty() }?.let { a to it } }.toMap(), password)) sheetProblem(R.string.tables_err_book_password)
            listOf("structure-protected", "protection-key", "protection-key-digest-algorithm").forEach { book.removeAttributeNS(T, it) }
            if (protect) {
                book.setAttributeNS(T, "table:structure-protected", "true")
                if (password.isNotEmpty()) {
                    book.setAttributeNS(T, "table:protection-key", b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))))
                    book.setAttributeNS(T, "table:protection-key-digest-algorithm", "http://www.w3.org/2000/09/xmldsig#sha256")
                }
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }

    private val SHEET_PROTECTION = Regex("""<sheetProtection\b[^>]*/>""")
    private fun attrs(tag: String) = Regex("""(\w+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    /** Excel: sheet part [sheetPart] protected ([protect], with [password] or none) or not (the password, if it has one, checked). */
    fun xlsx(source: File, target: File, sheetPart: String, protect: Boolean, password: String) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            SHEET_PROTECTION.find(x)?.let { m -> if (!protect && !opens(attrs(m.value), password)) sheetProblem(R.string.tables_err_sheet_password) }
            x = x.replace(SHEET_PROTECTION, "")
            if (protect) {
                val hash = if (password.isEmpty()) "" else {
                    val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    """ algorithmName="SHA-512" hashValue="${b64.encodeToString(excelHash(password, salt))}" saltValue="${b64.encodeToString(salt)}" spinCount="$SPIN""""
                }
                // in the schema's place: after the sheet's data (and its calculation settings)
                val at = Regex("""<sheetCalcPr\b[^>]*/>""").find(x)?.range?.last?.plus(1) ?: Regex("""</sheetData>|<sheetData\s*/>""").find(x)?.range?.last?.plus(1) ?: sheetProblem(R.string.tables_err_no_sheet_data)
                x = x.substring(0, at) + """<sheetProtection$hash sheet="1" objects="1" scenarios="1"/>""" + x.substring(at)
            }
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    /** LibreOffice: sheet [index] protected ([protect], with [password] or none) or not (the password, if it has one, checked). */
    fun ods(source: File, target: File, index: Int, protect: Boolean, password: String) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T
            val table = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            if (!protect && table.getAttributeNS(T, "protected") == "true" && !opens(listOf("protection-key", "protection-key-digest-algorithm")
                    .mapNotNull { a -> table.getAttributeNS(T, a).takeIf { it.isNotEmpty() }?.let { a to it } }.toMap(), password)) sheetProblem(R.string.tables_err_sheet_password)
            listOf("protected", "protection-key", "protection-key-digest-algorithm").forEach { table.removeAttributeNS(T, it) }
            if (protect) {
                table.setAttributeNS(T, "table:protected", "true")
                if (password.isNotEmpty()) {
                    table.setAttributeNS(T, "table:protection-key", b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))))
                    table.setAttributeNS(T, "table:protection-key-digest-algorithm", "http://www.w3.org/2000/09/xmldsig#sha256")
                }
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
