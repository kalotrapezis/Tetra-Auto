package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Insert › Chart, Picture and Text box in Tables: a chart (Words' chart editor, Words' [ChartWriter]) put on a sheet at a
 * cell, [cols] × [rows] cells big — Excel's chart part in the sheet's drawing (a two-cell anchor), LibreOffice's chart
 * object in the cell; a picture (Words' picker) or a text box the same way (Excel's pic and txBox shapes, LibreOffice's
 * frame with its image and rectangle shape with its words).
 * shortcut: the chart holds its own numbers (Excel's numLit, LibreOffice's own table), not the cells' — changing the cells
 * does not change it; point its series at the cells when charts are edited from the sheet.
 */
internal object SheetChart {
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun xlsx(source: File, target: File, sheetPart: String, row: Int, col: Int, rows: Int, cols: Int, ch: DocChart) =
        anchored(source, target, sheetPart, { zip, out, types ->
            val chartPath = generateSequence(1) { it + 1 }.map { "xl/charts/chart$it.xml" }.first { zip.getEntry(it) == null }
            out[chartPath] = ChartWriter.drawingml(ch).toByteArray()
            types.override(chartPath, "application/vnd.openxmlformats-officedocument.drawingml.chart+xml")
            "chart" to "../charts/${chartPath.substringAfterLast('/')}"
        }) { n, id -> """<xdr:twoCellAnchor editAs="oneCell">${mark("from", row, col)}${mark("to", row + rows, col + cols)}<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="$n" name="Chart $n"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>""" +
            """<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="$REL" r:id="$id"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>""" }

    /** Insert › Picture (Words' picture picker): [bytes] ([ext]: "png", "jpg") at cell [row], [col], [widthPt] × [heightPt] — Excel's picture in the sheet's drawing. */
    fun pictureXlsx(source: File, target: File, sheetPart: String, row: Int, col: Int, widthPt: Float, heightPt: Float, bytes: ByteArray, ext: String, dxPt: Float = 0f, dyPt: Float = 0f) =
        anchored(source, target, sheetPart, { zip, out, types ->
            val media = generateSequence(1) { it + 1 }.map { "xl/media/image$it.$ext" }.first { zip.getEntry(it) == null && it !in out }
            out[media] = bytes; types.default(ext, if (ext == "png") "image/png" else "image/jpeg")
            "image" to "../media/${media.substringAfterLast('/')}"
        }) { n, id -> val cx = (widthPt * 12700).toLong(); val cy = (heightPt * 12700).toLong()
            """<xdr:oneCellAnchor>${mark("from", row, col, dxPt, dyPt)}<xdr:ext cx="$cx" cy="$cy"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="$n" name="Picture $n"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>""" +
            """<xdr:blipFill><a:blip xmlns:r="$REL" r:embed="$id"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>""" }

    /** Insert › Text box: [text] in a box at cell [row], [col], [widthPt] × [heightPt] — Excel's text box shape. */
    fun textBoxXlsx(source: File, target: File, sheetPart: String, row: Int, col: Int, widthPt: Float, heightPt: Float, text: String) =
        anchored(source, target, sheetPart, null) { n, _ -> val cx = (widthPt * 12700).toLong(); val cy = (heightPt * 12700).toLong()
            val paras = text.split('\n').joinToString("") { """<a:p><a:r><a:rPr lang="en-US" sz="1100"/><a:t>${OfficeFormats.esc(it)}</a:t></a:r></a:p>""" }
            """<xdr:oneCellAnchor>${mark("from", row, col)}<xdr:ext cx="$cx" cy="$cy"/><xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="$n" name="TextBox $n"/><xdr:cNvSpPr txBox="1"/></xdr:nvSpPr>""" +
            """<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln w="9525"><a:solidFill><a:srgbClr val="808080"/></a:solidFill></a:ln></xdr:spPr>""" +
            """<xdr:txBody><a:bodyPr vertOverflow="clip" horzOverflow="clip" wrap="square" rtlCol="0" anchor="t"/><a:lstStyle/>$paras</xdr:txBody></xdr:sp><xdr:clientData/></xdr:oneCellAnchor>""" }

    /**
     * Insert › WordArt (Words' looks and bends): [text] in [color] (0xRRGGBB), bold, [sizePt], with Words' [effects] on its
     * letters and [warp] — Excel's WordArt: a text box with no fill or line, the letters' outline (a:ln) and glow and shadow
     * (a:effectLst) in the run, the bend as prstTxWarp, as PowerPoint and Excel write them.
     */
    fun wordArtXlsx(source: File, target: File, sheetPart: String, row: Int, col: Int, widthPt: Float, heightPt: Float, text: String, color: Int, sizePt: Float,
                    effects: TextEffects, warp: TextWarp?) =
        anchored(source, target, sheetPart, null) { n, _ -> val cx = (widthPt * 12700).toLong(); val cy = (heightPt * 12700).toLong()
            fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
            val ln = "<a:ln w=\"12700\">" + (effects.outline?.let { "<a:solidFill><a:srgbClr val=\"${hex(it)}\"/></a:solidFill>" } ?: "<a:noFill/>") + "</a:ln>"
            val fx = if (effects.glow == null && !effects.shadow) "" else "<a:effectLst>" +
                (effects.glow?.let { "<a:glow rad=\"101600\"><a:srgbClr val=\"${hex(it)}\"><a:alpha val=\"40000\"/></a:srgbClr></a:glow>" } ?: "") +
                (if (effects.shadow) "<a:outerShdw blurRad=\"38100\" dist=\"38100\" dir=\"2700000\" algn=\"tl\" rotWithShape=\"0\"><a:srgbClr val=\"000000\"><a:alpha val=\"60000\"/></a:srgbClr></a:outerShdw>" else "") + "</a:effectLst>"
            val rPr = "<a:rPr lang=\"en-US\" sz=\"${Math.round(sizePt * 100)}\" b=\"1\">$ln<a:solidFill><a:srgbClr val=\"${hex(color)}\"/></a:solidFill>$fx</a:rPr>"
            val paras = text.split('\n').joinToString("") { "<a:p><a:pPr algn=\"ctr\"/><a:r>$rPr<a:t>${OfficeFormats.esc(it)}</a:t></a:r></a:p>" }
            val bend = warp?.let { "<a:prstTxWarp prst=\"${it.word}\"><a:avLst/></a:prstTxWarp>" } ?: ""
            """<xdr:oneCellAnchor>${mark("from", row, col)}<xdr:ext cx="$cx" cy="$cy"/><xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="$n" name="WordArt $n"/><xdr:cNvSpPr txBox="1"/></xdr:nvSpPr>""" +
            """<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></xdr:spPr>""" +
            """<xdr:txBody><a:bodyPr wrap="none" rtlCol="0" anchor="ctr" fromWordArt="1">$bend</a:bodyPr><a:lstStyle/>$paras</xdr:txBody></xdr:sp><xdr:clientData/></xdr:oneCellAnchor>""" }

    /** Insert › Shape (Words' shapes dialog): preset [kind] ("rect", "rightArrow"…) at cell [row], [col], its [fill] and [line] — Excel's shape. */
    fun shapeXlsx(source: File, target: File, sheetPart: String, row: Int, col: Int, widthPt: Float, heightPt: Float, kind: String, fill: Int?, line: Int?) =
        anchored(source, target, sheetPart, null) { n, _ -> val cx = (widthPt * 12700).toLong(); val cy = (heightPt * 12700).toLong()
            fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
            val fillXml = fill?.let { """<a:solidFill><a:srgbClr val="${hex(it)}"/></a:solidFill>""" } ?: "<a:noFill/>"
            val arrows = when (kind) { "lineArrow" -> """<a:tailEnd type="triangle"/>"""; "lineArrows" -> """<a:headEnd type="triangle"/><a:tailEnd type="triangle"/>"""; else -> "" }
            val lineXml = """<a:ln w="12700">${line?.let { """<a:solidFill><a:srgbClr val="${hex(it)}"/></a:solidFill>""" } ?: "<a:noFill/>"}$arrows</a:ln>"""
            """<xdr:oneCellAnchor>${mark("from", row, col)}<xdr:ext cx="$cx" cy="$cy"/><xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="$n" name="Shape $n"/><xdr:cNvSpPr/></xdr:nvSpPr>""" +
            """<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="${if (kind.startsWith("lineArrow")) "line" else kind}"><a:avLst/></a:prstGeom>$fillXml$lineXml</xdr:spPr></xdr:sp><xdr:clientData/></xdr:oneCellAnchor>""" }

    /** A corner at cell [r], [c], [dxPt] and [dyPt] in from its top left. */
    private fun mark(tag: String, r: Int, c: Int, dxPt: Float = 0f, dyPt: Float = 0f) =
        "<xdr:$tag><xdr:col>$c</xdr:col><xdr:colOff>${(dxPt * 12700).toLong()}</xdr:colOff><xdr:row>$r</xdr:row><xdr:rowOff>${(dyPt * 12700).toLong()}</xdr:rowOff></xdr:$tag>"

    /** The workbook's content types, changed as parts are added. */
    private class Types(var xml: String) {
        fun override(path: String, type: String) { xml = xml.replace("</Types>", """<Override PartName="/$path" ContentType="$type"/></Types>""") }
        fun default(ext: String, type: String) { if (!Regex("""<Default Extension="$ext"""", RegexOption.IGNORE_CASE).containsMatchIn(xml)) xml = xml.replace("</Types>", """<Default Extension="$ext" ContentType="$type"/></Types>""") }
    }

    /**
     * Excel: [source] into [target] with an anchor in sheet part [sheetPart]'s drawing (its own, or one made): [part] adds what
     * the anchor shows and gives the drawing's relationship to it (type, target; null: none), [anchor] makes the anchor from
     * its shape's id and that relationship's id.
     */
    private fun anchored(source: File, target: File, sheetPart: String, part: ((ZipFile, MutableMap<String, ByteArray?>, Types) -> Pair<String, String>)?, anchor: (Int, String?) -> String) {
        ZipFile(source).use { zip ->
            fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            val out = LinkedHashMap<String, ByteArray?>()
            fun relsOf(p: String) = p.substringBeforeLast('/') + "/_rels/" + p.substringAfterLast('/') + ".rels"
            fun freeId(rels: String) = "rId" + ((Regex("""Id="rId(\d+)"""").findAll(rels).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1)
            fun emptyRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
            val types = Types(read("[Content_Types].xml") ?: sheetProblem(R.string.tables_err_not_workbook))
            val rel = part?.invoke(zip, out, types)
            var sheet = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            var sheetRels = read(relsOf(sheetPart)) ?: emptyRels()
            // the sheet's drawing: its own, or a new one named in the sheet
            val own = Regex("""<drawing\b[^>]*\br:id="([^"]+)"""").find(sheet)?.groupValues?.get(1)
                ?.let { id -> Regex("""<Relationship\b[^>]*\bId="$id"[^>]*\bTarget="([^"]+)"""").find(sheetRels)?.groupValues?.get(1) ?: Regex("""<Relationship\b[^>]*\bTarget="([^"]+)"[^>]*\bId="$id"""").find(sheetRels)?.groupValues?.get(1) }
                ?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("../") }
            val drawingPath = own ?: generateSequence(1) { it + 1 }.map { "xl/drawings/drawing$it.xml" }.first { zip.getEntry(it) == null }
            var drawing = (if (own != null) read(own) else null)
                ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"></xdr:wsDr>"""
            if (own == null) {
                val id = freeId(sheetRels)
                sheetRels = sheetRels.replace("</Relationships>", """<Relationship Id="$id" Type="$REL/drawing" Target="../drawings/${drawingPath.substringAfterLast('/')}"/></Relationships>""")
                // in the schema's place: before the legacy drawings, pictures, objects, controls, tables and extensions
                val at = Regex("""<(legacyDrawing|legacyDrawingHF|drawingHF|picture|oleObjects|controls|webPublishItems|tableParts|extLst)\b""").find(sheet)?.range?.first ?: sheet.lastIndexOf("</worksheet>")
                sheet = sheet.substring(0, at) + """<drawing xmlns:r="$REL" r:id="$id"/>""" + sheet.substring(at)
                types.override(drawingPath, "application/vnd.openxmlformats-officedocument.drawing+xml")
                out[sheetPart] = sheet.toByteArray(); out[relsOf(sheetPart)] = sheetRels.toByteArray()
            }
            var drawingRels = read(relsOf(drawingPath)) ?: emptyRels()
            val relId = rel?.let { (type, to) -> freeId(drawingRels).also { id -> drawingRels = drawingRels.replace("</Relationships>", """<Relationship Id="$id" Type="$REL/$type" Target="$to"/></Relationships>""") } }
            val n = (Regex("""<xdr:cNvPr\b[^>]*\bid="(\d+)"""").findAll(drawing).maxOfOrNull { it.groupValues[1].toInt() } ?: 1) + 1
            drawing = drawing.replace(Regex("""<xdr:wsDr\b([^>]*)/>"""), "<xdr:wsDr$1></xdr:wsDr>").replace("</xdr:wsDr>", "${anchor(n, relId)}</xdr:wsDr>")
            out[drawingPath] = drawing.toByteArray(); if (relId != null) out[relsOf(drawingPath)] = drawingRels.toByteArray(); out["[Content_Types].xml"] = types.xml.toByteArray()
            SheetSave.rezip(zip, target, out, null)
        }
    }

    /** LibreOffice: sheet [index]'s cell [row], [col] holding the chart's object, [widthPt] × [heightPt]. */
    fun ods(source: File, target: File, index: Int, row: Int, col: Int, widthPt: Float, heightPt: Float, ch: DocChart) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
            val table = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val cell = SheetMerge.odsCell(doc, table, row, col)
            val name = generateSequence(1) { it + 1 }.map { "Object tetra-$it" }.first { zip.getEntry("$it/content.xml") == null }
            fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", pt / 28.346457f)
            val frame = OfficeFormats.parse("""<draw:frame xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" draw:z-index="0" draw:name="Chart tetra" svg:x="0cm" svg:y="0cm" svg:width="${cm(widthPt)}" svg:height="${cm(heightPt)}"><draw:object xlink:href="./$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>""".toByteArray()).documentElement
            cell.insertBefore(doc.importNode(frame, true), cell.firstChild)
            var manifest = zip.getEntry("META-INF/manifest.xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_manifest)
            manifest = manifest.replace("</manifest:manifest>", """<manifest:file-entry manifest:full-path="$name/" manifest:version="1.3" manifest:media-type="application/vnd.oasis.opendocument.chart"/><manifest:file-entry manifest:full-path="$name/content.xml" manifest:media-type="text/xml"/></manifest:manifest>""")
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc), "$name/content.xml" to ChartWriter.odf(ch, widthPt, heightPt).toByteArray(), "META-INF/manifest.xml" to manifest.toByteArray()), "mimetype")
        }
    }

    /** LibreOffice: a picture in cell [row], [col] of sheet [index]: a frame with its image (Pictures/), [widthPt] × [heightPt]. */
    fun pictureOds(source: File, target: File, index: Int, row: Int, col: Int, widthPt: Float, heightPt: Float, bytes: ByteArray, ext: String, dxPt: Float = 0f, dyPt: Float = 0f) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val path = generateSequence(1) { it + 1 }.map { "Pictures/tetra-$it.$ext" }.first { zip.getEntry(it) == null }
            fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", pt / 28.346457f)
            val frame = OfficeFormats.parse("""<draw:frame xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" draw:z-index="0" draw:name="Picture tetra" svg:x="${cm(dxPt)}" svg:y="${cm(dyPt)}" svg:width="${cm(widthPt)}" svg:height="${cm(heightPt)}"><draw:image xlink:href="$path" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>""".toByteArray()).documentElement
            val cell = SheetMerge.odsCell(doc, table, row, col)
            cell.insertBefore(doc.importNode(frame, true), cell.firstChild)
            var manifest = zip.getEntry("META-INF/manifest.xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_manifest)
            manifest = manifest.replace("</manifest:manifest>", """<manifest:file-entry manifest:full-path="$path" manifest:media-type="${if (ext == "png") "image/png" else "image/jpeg"}"/></manifest:manifest>""")
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc), path to bytes, "META-INF/manifest.xml" to manifest.toByteArray()), "mimetype")
        }
    }

    /** LibreOffice: a text box in cell [row], [col] of sheet [index]: a rectangle shape holding [text], as LibreOffice's are. */
    fun textBoxOds(source: File, target: File, index: Int, row: Int, col: Int, widthPt: Float, heightPt: Float, text: String) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", pt / 28.346457f)
            val paras = text.split('\n').joinToString("") { "<text:p>${OfficeFormats.esc(it)}</text:p>" }
            val shape = OfficeFormats.parse("""<draw:custom-shape xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" draw:z-index="0" draw:name="TextBox tetra" svg:x="0cm" svg:y="0cm" svg:width="${cm(widthPt)}" svg:height="${cm(heightPt)}">$paras<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="rectangle" draw:enhanced-path="M 0 0 L 21600 0 21600 21600 0 21600 0 0 Z N"/></draw:custom-shape>""".toByteArray()).documentElement
            val cell = SheetMerge.odsCell(doc, table, row, col)
            cell.insertBefore(doc.importNode(shape, true), cell.firstChild)
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }

    /**
     * LibreOffice: WordArt in cell [row], [col] of sheet [index] — straight, a shape with no fill or line holding the words in
     * a text style of their own (colour, size, bold, outline, shadow; LibreOffice has no glow); bent, its fontwork, which
     * paints the letters in the shape's fill and outlines them in its line, as Impress writes one.
     */
    fun wordArtOds(source: File, target: File, index: Int, row: Int, col: Int, widthPt: Float, heightPt: Float, text: String, color: Int, sizePt: Float,
                   effects: TextEffects, warp: TextWarp?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val S = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"; val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"; val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
            val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
            val autos = doc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:office:1.0", "automatic-styles").item(0) as? Element ?: sheetProblem(R.string.tables_err_no_styles)
            val taken = (0 until autos.childNodes.length).mapNotNull { (autos.childNodes.item(it) as? Element)?.getAttributeNS(S, "name") }.toMutableSet()
            fun style(prefix: String, family: String, kind: String, set: (Element) -> Unit) = generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in taken }.also { name -> taken += name
                autos.appendChild(doc.createElementNS(S, "style:style").also { st -> st.setAttributeNS(S, "style:name", name); st.setAttributeNS(S, "style:family", family)
                    st.appendChild(doc.createElementNS(S, "style:$kind").also(set)) }) }
            fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)
            val graphic = style("grT", "graphic", "graphic-properties") { p ->
                if (warp != null) { p.setAttributeNS(DRAW, "draw:fill", "solid"); p.setAttributeNS(DRAW, "draw:fill-color", hex(color))
                    if (effects.outline != null) { p.setAttributeNS(DRAW, "draw:stroke", "solid"); p.setAttributeNS(SVG, "svg:stroke-color", hex(effects.outline)) } else p.setAttributeNS(DRAW, "draw:stroke", "none") }
                else { p.setAttributeNS(DRAW, "draw:fill", "none"); p.setAttributeNS(DRAW, "draw:stroke", "none") }
                p.setAttributeNS(DRAW, "draw:textarea-horizontal-align", "center"); p.setAttributeNS(DRAW, "draw:textarea-vertical-align", "middle")
                // its size as made (LibreOffice's shapes otherwise shrink to their words, and move)
                p.setAttributeNS(DRAW, "draw:auto-grow-width", "false"); p.setAttributeNS(DRAW, "draw:auto-grow-height", "false") }
            val words = style("T", "text", "text-properties") { p -> p.setAttributeNS(FO, "fo:color", hex(color)); p.setAttributeNS(FO, "fo:font-size", "${sizePt}pt"); p.setAttributeNS(FO, "fo:font-weight", "bold")
                if (effects.outline != null) p.setAttributeNS(S, "style:text-outline", "true"); if (effects.shadow) p.setAttributeNS(FO, "fo:text-shadow", "1pt 1pt") }
            fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", pt / 28.346457f)
            val paras = text.split('\n').joinToString("") { "<text:p><text:span text:style-name=\"$words\">${OfficeFormats.esc(it)}</text:span></text:p>" }
            val geometry = if (warp == null) """<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="rectangle"/>"""
                else """<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:text-path="true" draw:text-path-mode="path" draw:text-path-scale="path" draw:type="${warp.fontwork}"/>"""
            val shape = OfficeFormats.parse("""<draw:custom-shape xmlns:draw="$DRAW" xmlns:svg="$SVG" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" draw:style-name="$graphic" draw:z-index="0" draw:name="WordArt tetra" svg:x="0cm" svg:y="0cm" svg:width="${cm(widthPt)}" svg:height="${cm(heightPt)}">$paras$geometry</draw:custom-shape>""".toByteArray()).documentElement
            val cell = SheetMerge.odsCell(doc, table, row, col)
            cell.insertBefore(doc.importNode(shape, true), cell.firstChild)
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }

    /** LibreOffice: a shape in cell [row], [col] of sheet [index] — Words' geometry for [kind], a graphic style of its own for its fill and line. */
    fun shapeOds(source: File, target: File, index: Int, row: Int, col: Int, widthPt: Float, heightPt: Float, kind: String, fill: Int?, line: Int?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val S = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"; val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"; val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
            val autos = doc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:office:1.0", "automatic-styles").item(0) as? Element ?: sheetProblem(R.string.tables_err_no_styles)
            val taken = (0 until autos.childNodes.length).mapNotNull { (autos.childNodes.item(it) as? Element)?.getAttributeNS(S, "name") }.toSet()
            val name = generateSequence(1) { it + 1 }.map { "grT$it" }.first { it !in taken }
            autos.appendChild(doc.createElementNS(S, "style:style").also { st -> st.setAttributeNS(S, "style:name", name); st.setAttributeNS(S, "style:family", "graphic")
                st.appendChild(doc.createElementNS(S, "style:graphic-properties").also { p ->
                    if (fill != null) { p.setAttributeNS(DRAW, "draw:fill", "solid"); p.setAttributeNS(DRAW, "draw:fill-color", "#%06x".format(fill and 0xFFFFFF)) } else p.setAttributeNS(DRAW, "draw:fill", "none")
                    if (line != null) { p.setAttributeNS(DRAW, "draw:stroke", "solid"); p.setAttributeNS(SVG, "svg:stroke-color", "#%06x".format(line and 0xFFFFFF)); p.setAttributeNS(SVG, "svg:stroke-width", "0.035cm") } else p.setAttributeNS(DRAW, "draw:stroke", "none") }) })
            fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", pt / 28.346457f)
            val geometry = ShapeGeometry.odfGeometry(kind, widthPt, heightPt)?.takeIf { kind != "line" } ?: """<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="${ShapePicture.odfType(kind)}"/>"""
            val shape = OfficeFormats.parse("""<draw:custom-shape xmlns:draw="$DRAW" xmlns:svg="$SVG" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" draw:style-name="$name" draw:z-index="0" draw:name="Shape tetra" svg:x="0cm" svg:y="0cm" svg:width="${cm(widthPt)}" svg:height="${cm(heightPt)}"><text:p/>$geometry</draw:custom-shape>""".toByteArray()).documentElement
            val cell = SheetMerge.odsCell(doc, table, row, col)
            cell.insertBefore(doc.importNode(shape, true), cell.firstChild)
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
