package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Insert › Link on a cell (Words' link dialog): a web address, or a place in the workbook ("#Sheet2!A1"); null takes the
 * cell's link away. Excel's hyperlink (an outside relationship, or a location), LibreOffice's text:a round the cell's words.
 * A change to the file, as inserting rows is.
 */
internal object SheetLinks {
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun xlsx(source: File, target: File, sheetPart: String, row: Int, col: Int, url: String?) {
        ZipFile(source).use { zip ->
            fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            val relsPath = sheetPart.substringBeforeLast('/') + "/_rels/" + sheetPart.substringAfterLast('/') + ".rels"
            var x = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            var rels = read(relsPath) ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
            val ref = "${Sheets.colName(col)}${row + 1}"
            // the cell's own link gone (its outside relationship too)
            Regex("""<hyperlink\b[^>]*\bref="$ref"[^>]*/>""").findAll(x).toList().forEach { m ->
                Regex("""\br:id="([^"]+)"""").find(m.value)?.groupValues?.get(1)?.let { id -> rels = rels.replace(Regex("""<Relationship\b[^>]*\bId="$id"[^>]*/>"""), "") }
                x = x.replace(m.value, "") }
            if (url != null) {
                val link = if (url.startsWith("#")) """<hyperlink ref="$ref" location="${OfficeFormats.esc(url.drop(1))}"/>""" else {
                    val id = "rId" + ((Regex("""Id="rId(\d+)"""").findAll(rels).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1)
                    rels = rels.replace("</Relationships>", """<Relationship Id="$id" Type="$REL/hyperlink" Target="${OfficeFormats.esc(url)}" TargetMode="External"/></Relationships>""")
                    """<hyperlink xmlns:r="$REL" ref="$ref" r:id="$id"/>"""
                }
                x = if ("</hyperlinks>" in x) x.replace("</hyperlinks>", "$link</hyperlinks>") else {
                    // in the schema's place: after the rules and validations, before the page's settings and drawings
                    val at = Regex("""<(printOptions|pageMargins|pageSetup|headerFooter|rowBreaks|colBreaks|customProperties|cellWatches|ignoredErrors|smartTags|drawing|legacyDrawing|legacyDrawingHF|drawingHF|picture|oleObjects|controls|webPublishItems|tableParts|extLst)\b""").find(x)?.range?.first ?: x.lastIndexOf("</worksheet>")
                    x.substring(0, at) + "<hyperlinks>$link</hyperlinks>" + x.substring(at)
                }
            }
            x = x.replace(Regex("""<hyperlinks>\s*</hyperlinks>"""), "")
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray(), relsPath to rels.toByteArray()), null)
        }
    }

    fun ods(source: File, target: File, index: Int, row: Int, col: Int, url: String?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"; val XLINK = "http://www.w3.org/1999/xlink"
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val cell = SheetMerge.odsCell(doc, table, row, col)
            val p = (0 until cell.childNodes.length).mapNotNull { cell.childNodes.item(it) as? Element }.firstOrNull { it.localName == "p" && it.namespaceURI == TEXT }
                ?: doc.createElementNS(TEXT, "text:p").also { cell.appendChild(it) }
            // its words out of any link, then (a link) inside one
            p.getElementsByTagNameNS(TEXT, "a").let { l -> (0 until l.length).map { l.item(it) as Element } }.forEach { a -> while (a.firstChild != null) a.parentNode.insertBefore(a.firstChild, a); a.parentNode.removeChild(a) }
            if (url != null) {
                val a = doc.createElementNS(TEXT, "text:a"); a.setAttributeNS(XLINK, "xlink:type", "simple")
                // a place in the workbook as LibreOffice names one: #Sheet2.A1
                a.setAttributeNS(XLINK, "xlink:href", if (url.startsWith("#") && '!' in url) "#" + url.drop(1).substringBefore('!') + "." + url.substringAfter('!') else url)
                while (p.firstChild != null) a.appendChild(p.firstChild)
                p.appendChild(a)
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
