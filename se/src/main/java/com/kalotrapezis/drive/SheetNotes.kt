package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * A note on a cell (Excel's Review › New Note, LibreOffice's comment), made, changed or taken away ([text] null): Excel's
 * comments part with the VML note shape Excel draws it by (both made when the sheet has none), LibreOffice's
 * office:annotation in the cell. Words' comment dialog asks for it. A change to the file.
 */
internal object SheetNotes {
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun esc(t: String) = OfficeFormats.esc(t)

    fun xlsx(source: File, target: File, sheetPart: String, row: Int, col: Int, author: String, text: String?) {
        ZipFile(source).use { zip ->
            fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            val out = LinkedHashMap<String, ByteArray?>()
            val relsPath = sheetPart.substringBeforeLast('/') + "/_rels/" + sheetPart.substringAfterLast('/') + ".rels"
            var rels = read(relsPath) ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
            var types = read("[Content_Types].xml") ?: sheetProblem(R.string.tables_err_not_workbook)
            var sheet = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            fun target(type: String) = Regex("""<Relationship\b[^>]*/>""").findAll(rels).map { it.value }.firstOrNull { "/$type\"" in it }
                ?.let { Regex("""Target="([^"]+)"""").find(it)?.groupValues?.get(1) }?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("../") }
            fun freeId() = "rId" + ((Regex("""Id="rId(\d+)"""").findAll(rels).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1)
            val ref = "${Sheets.colName(col)}${row + 1}"
            // the comments part: the sheet's, else a new one
            val commentsPath = target("comments") ?: generateSequence(1) { it + 1 }.map { "xl/comments$it.xml" }.first { zip.getEntry(it) == null }.also { p ->
                rels = rels.replace("</Relationships>", """<Relationship Id="${freeId()}" Type="$REL/comments" Target="../${p.removePrefix("xl/")}"/></Relationships>""")
                types = types.replace("</Types>", """<Override PartName="/$p" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml"/></Types>""") }
            var comments = read(commentsPath) ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><comments xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><authors></authors><commentList></commentList></comments>"""
            comments = comments.replace(Regex("""<comment\b[^>]*\bref="$ref"[^>]*>[\s\S]*?</comment>"""), "")
            if (text != null) {
                val authors = Regex("""<author>([^<]*)</author>""").findAll(comments).map { it.groupValues[1] }.toList()
                val id = authors.indexOf(esc(author)).takeIf { it >= 0 } ?: authors.size.also { comments = comments.replace(Regex("""<authors\s*/>"""), "<authors></authors>").replace("</authors>", "<author>${esc(author)}</author></authors>") }
                val body = """<comment ref="$ref" authorId="$id"><text><r><rPr><b/><sz val="9"/></rPr><t xml:space="preserve">${esc(author)}:</t></r><r><rPr><sz val="9"/></rPr><t xml:space="preserve">${"\n"}${esc(text)}</t></r></text></comment>"""
                comments = comments.replace(Regex("""<commentList\s*/>"""), "<commentList></commentList>").replace("</commentList>", "$body</commentList>")
            }
            out[commentsPath] = comments.toByteArray()
            // Excel 365's threaded comment on the cell (read with its replies) goes with it: the note takes its place, else Excel
            // would show the old thread over it. shortcut: no replies written (no Excel here to check them); upgrade when one is asked for
            target("threadedComment")?.let { p -> read(p)?.let { t ->
                out[p] = t.replace(Regex("""<threadedComment\b[^>]*\bref="$ref"[^>]*?(?:/>|>[\s\S]*?</threadedComment>)"""), "").toByteArray() } }
            // the VML drawing Excel shows notes by: a note shape per comment
            val vmlPath = target("vmlDrawing") ?: generateSequence(1) { it + 1 }.map { "xl/drawings/vmlDrawing$it.vml" }.first { zip.getEntry(it) == null }.also { p ->
                val id = freeId()
                rels = rels.replace("</Relationships>", """<Relationship Id="$id" Type="$REL/vmlDrawing" Target="../${p.removePrefix("xl/")}"/></Relationships>""")
                if (!Regex("""<Default Extension="vml"""", RegexOption.IGNORE_CASE).containsMatchIn(types)) types = types.replace("</Types>", """<Default Extension="vml" ContentType="application/vnd.openxmlformats-officedocument.vmlDrawing"/></Types>""")
                sheet = sheet.replace(Regex("""<legacyDrawing\b[^>]*/>"""), "")
                val at = Regex("""<(legacyDrawingHF|drawingHF|picture|oleObjects|controls|webPublishItems|tableParts|extLst)\b""").find(sheet)?.range?.first ?: sheet.lastIndexOf("</worksheet>")
                sheet = sheet.substring(0, at) + """<legacyDrawing xmlns:r="$REL" r:id="$id"/>""" + sheet.substring(at)
                out[sheetPart] = sheet.toByteArray() }
            var vml = read(vmlPath) ?: """<xml xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel"><o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="1"/></o:shapelayout><v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe"><v:stroke joinstyle="miter"/><v:path gradientshapeok="t" o:connecttype="rect"/></v:shapetype></xml>"""
            vml = vml.replace(Regex("""<v:shape\b(?:(?!</v:shape>)[\s\S])*?<x:Row>$row</x:Row>\s*<x:Column>$col</x:Column>[\s\S]*?</v:shape>"""), "")
            if (text != null) {
                val n = (Regex("""_x0000_s(\d+)""").findAll(vml).maxOfOrNull { it.groupValues[1].toInt() } ?: 1024) + 1
                vml = vml.replace("</xml>", """<v:shape id="_x0000_s$n" type="#_x0000_t202" style="position:absolute;margin-left:80pt;margin-top:2pt;width:108pt;height:59pt;z-index:$n;visibility:hidden" fillcolor="#ffffe1" o:insetmode="auto"><v:fill color2="#ffffe1"/><v:shadow on="t" color="black" obscured="t"/><v:path o:connecttype="none"/><v:textbox style="mso-direction-alt:auto"><div style="text-align:left"></div></v:textbox><x:ClientData ObjectType="Note"><x:MoveWithCells/><x:SizeWithCells/><x:Anchor>${col + 1}, 15, ${maxOf(0, row - 1)}, 10, ${col + 3}, 15, ${row + 3}, 4</x:Anchor><x:AutoFill>False</x:AutoFill><x:Row>$row</x:Row><x:Column>$col</x:Column></x:ClientData></v:shape></xml>""")
            }
            out[vmlPath] = vml.toByteArray(); out[relsPath] = rels.toByteArray(); out["[Content_Types].xml"] = types.toByteArray()
            SheetSave.rezip(zip, target, out, null)
        }
    }

    fun ods(source: File, target: File, index: Int, row: Int, col: Int, author: String, text: String?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"; val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"; val DC = "http://purl.org/dc/elements/1.1/"
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val cell = SheetMerge.odsCell(doc, table, row, col)
            (0 until cell.childNodes.length).mapNotNull { cell.childNodes.item(it) as? Element }.filter { it.localName == "annotation" }.forEach { cell.removeChild(it) }
            if (text != null) {
                val a = doc.createElementNS(OFFICE, "office:annotation")
                a.appendChild(doc.createElementNS(DC, "dc:creator").also { it.textContent = author })
                a.appendChild(doc.createElementNS(DC, "dc:date").also { it.textContent = java.time.LocalDateTime.now().withNano(0).toString() })
                text.split('\n').forEach { line -> a.appendChild(doc.createElementNS(TEXT, "text:p").also { it.textContent = line }) }
                cell.insertBefore(a, cell.firstChild)
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
