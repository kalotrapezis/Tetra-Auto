package com.kalotrapezis.drive

/**
 * WordArt's text effects (Phase 2): an outline round the letters ([outline], its colour), a shadow below and right of
 * them, a glow round them ([glow], its colour). Word keeps them in the run (w14:glow, w14:shadow, w14:textOutline — Word
 * 2010's); LibreOffice has an outline (hollow letters, in their own colour) and a shadow, no glow.
 */
internal data class TextEffects(val outline: Int? = null, val shadow: Boolean = false, val glow: Int? = null) {
    val any get() = outline != null || shadow || glow != null

    /** Word's run properties for them, in the order Word wants (glow, shadow, outline), each saying its namespace. */
    fun docx(): String = buildString {
        fun c(rgb: Int) = "%06X".format(rgb and 0xFFFFFF)
        glow?.let { append("""<w14:glow xmlns:w14="$W14" w14:rad="101600"><w14:srgbClr w14:val="${c(it)}"><w14:alpha w14:val="40000"/></w14:srgbClr></w14:glow>""") }
        if (shadow) append("""<w14:shadow xmlns:w14="$W14" w14:blurRad="38100" w14:dist="38100" w14:dir="2700000" w14:sx="100000" w14:sy="100000" w14:kx="0" w14:ky="0" w14:algn="tl"><w14:srgbClr w14:val="000000"><w14:alpha w14:val="60000"/></w14:srgbClr></w14:shadow>""")
        outline?.let { append("""<w14:textOutline xmlns:w14="$W14" w14:w="12700" w14:cap="flat" w14:cmpd="sng" w14:algn="ctr"><w14:solidFill><w14:srgbClr w14:val="${c(it)}"/></w14:solidFill><w14:prstDash w14:val="solid"/><w14:round/></w14:textOutline>""") }
    }
    /** LibreOffice's text properties for them (the glow has none). */
    fun odt(): String = (if (outline != null) " style:text-outline=\"true\"" else "") + (if (shadow) " fo:text-shadow=\"1pt 1pt\"" else "")
    /** A name part for Writer's own WordArt style in an .odt (Tetra_20_art_RRGGBB…): what makes the style. */
    fun odtName() = (if (outline != null) "_o" else "") + (if (shadow) "_s" else "")

    companion object {
        const val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
        private fun color(xml: String) = Regex("""<w14:srgbClr\s+w14:val="([0-9A-Fa-f]{6})"""").find(xml)?.groupValues?.get(1)?.toInt(16)
        /** The effects a run's own settings hold (Word's w14 elements, or LibreOffice's attributes). */
        fun of(font: String?): TextEffects {
            if (font.isNullOrEmpty()) return TextEffects()
            val outline = Regex("""<w14:textOutline\b[\s\S]*?</w14:textOutline>""").find(font)?.value?.takeIf { "<w14:noFill" !in it }?.let { color(it) ?: 0 }
                ?: if ("style:text-outline=\"true\"" in font) 0 else null
            val shadow = Regex("""<w14:shadow\b""").containsMatchIn(font) || Regex("""fo:text-shadow="(?!none)[^"]+"""").containsMatchIn(font)
            val glow = Regex("""<w14:glow\b[\s\S]*?</w14:glow>""").find(font)?.value?.let { color(it) ?: 0xFFC000 }
            return TextEffects(outline, shadow, glow)
        }
    }
}

/**
 * Letters drawn with their [effects] (the pages, a text box in the editor): the glow and the shadow under them, their
 * [fill] colour, the outline over it. Put on each word, so lines still break between words. [unit]: the layout's units
 * per point.
 */
internal class EffectSpan(val effects: TextEffects, val fill: Int, val unit: Float) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return Math.round(paint.measureText(text, start, end))
    }
    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val p = android.text.TextPaint(paint)
        val s = text.subSequence(start, end).toString()
        val size = p.textSize
        p.style = android.graphics.Paint.Style.FILL; p.color = fill
        effects.glow?.let { g -> p.setShadowLayer(size * 0.12f, 0f, 0f, 0xCC000000.toInt() or g); canvas.drawText(s, x, y.toFloat(), p); canvas.drawText(s, x, y.toFloat(), p) }
        if (effects.shadow) { p.setShadowLayer(size * 0.04f, size * 0.05f, size * 0.05f, 0x99000000.toInt()); canvas.drawText(s, x, y.toFloat(), p) }
        p.clearShadowLayer()
        canvas.drawText(s, x, y.toFloat(), p)
        effects.outline?.let { o ->
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = maxOf(unit * 1f, size * 0.025f); p.strokeJoin = android.graphics.Paint.Join.ROUND
            p.color = 0xFF000000.toInt() or o
            canvas.drawText(s, x, y.toFloat(), p)
        }
    }
}

/**
 * WordArt's bends (Word's Text Effects › Transform; LibreOffice's Fontwork): the words laid along an arch, a circle, a
 * wave or a slant. Word's a:prstTxWarp in the WordArt's bodyPr (with fromWordArt); LibreOffice's fontwork shape type,
 * which it draws by its name.
 */
internal enum class TextWarp(val label: String, val word: String, val fontwork: String, @androidx.annotation.StringRes val labelRes: Int) {
    ArchUp("Arch up", "textArchUp", "fontwork-arch-up-curve", R.string.office_warp_arch_up), ArchDown("Arch down", "textArchDown", "fontwork-arch-down-curve", R.string.office_warp_arch_down),
    Circle("Circle", "textCircle", "fontwork-circle-curve", R.string.office_warp_circle), Wave("Wave", "textWave1", "fontwork-wave", R.string.office_warp_wave),
    SlantUp("Slant up", "textSlantUp", "fontwork-slant-up", R.string.office_warp_slant_up), SlantDown("Slant down", "textSlantDown", "fontwork-slant-down", R.string.office_warp_slant_down);

    /** How tall the letters are in a [w] × [h] box (before they are fitted to the bend's length). */
    fun letters(w: Float, h: Float) = when (this) { Circle -> minOf(w, h) * 0.2f; Wave -> h * 0.45f; SlantUp, SlantDown -> minOf(h * 0.4f, w * 0.15f); else -> h * 0.42f }

    /**
     * The line the words stand on in a [w] × [h] box (from 0,0), left to right as they are read, letters [m] tall —
     * placed so the letters stay inside the box, as Word's do.
     */
    fun line(w: Float, h: Float, m: Float = letters(w, h)): List<Pair<Float, Float>> {
        val n = 48
        return (0..n).map { i -> val t = i / n.toFloat()
            when (this) {
                // An arch: the top of an ellipse, short of its ends (their letters stood up and out of the box — the user:
                // "the T at the beginning is cut off"), its letters' tops at the box's top.
                ArchUp -> { val a = Math.PI * (0.88 - 0.76 * t); (w / 2 + (w / 2 - m * 0.5f) / Math.cos(Math.PI * 0.12) * Math.cos(a)).toFloat() to (h - m * 0.15f - (h - m * 1.15f) * (Math.sin(a) - Math.sin(Math.PI * 0.12)) / (1 - Math.sin(Math.PI * 0.12))).toFloat() }
                // A smile: the bottom of an ellipse, its ends a letter below the top, its middle at the bottom.
                ArchDown -> { val a = Math.PI * (0.88 - 0.76 * t); (w / 2 + (w / 2 - m * 0.5f) / Math.cos(Math.PI * 0.12) * Math.cos(a)).toFloat() to (m * 1.1f + (h * 0.97f - m * 1.1f) * (Math.sin(a) - Math.sin(Math.PI * 0.12)) / (1 - Math.sin(Math.PI * 0.12))).toFloat() }
                // Round the box's ellipse clockwise from its left, the letters outside it, inside the box.
                Circle -> { val a = Math.PI + 2 * Math.PI * t; (w / 2 + (w / 2 - m) * Math.cos(a)).toFloat() to (h / 2 + (h / 2 - m) * Math.sin(a)).toFloat() }
                Wave -> t * w to (h / 2 + m / 2 - (h - m) * 0.3f * Math.sin(2 * Math.PI * t)).toFloat()
                SlantUp -> t * w to (h * 0.97f - (h * 0.97f - m) * t)
                SlantDown -> t * w to (m + (h * 0.97f - m) * t)
            }
        }
    }

    companion object {
        /** A drawing's bend, as Word or LibreOffice says it; null: straight. */
        fun of(raw: String?): TextWarp? {
            raw ?: return null
            Regex("""<a:prstTxWarp prst="(\w+)"""").find(raw)?.groupValues?.get(1)?.let { p -> return entries.firstOrNull { it.word == p } }
            if ("draw:text-path=\"true\"" in raw) Regex("""draw:type="(fontwork-[\w-]+)"""").find(raw)?.groupValues?.get(1)?.let { t -> return entries.firstOrNull { it.fontwork == t } }
            return null
        }
        /** [xml] straight again: Word's prstTxWarp taken out; LibreOffice's rectangle back in place of the fontwork. */
        fun removed(xml: String, odt: Boolean): String =
            if (odt) xml.replace(Regex("""<draw:enhanced-geometry\b[^>]*draw:text-path="true"[^>]*/>"""), Regex.escapeReplacement("""<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="rectangle"/>"""))
            else xml.replace(Regex("""<a:prstTxWarp\b[\s\S]*?</a:prstTxWarp>|<a:prstTxWarp\b[^>]*/>"""), "")
        /** A WordArt's look as Writer wrote it ([raw]: Word's runs' w14 effects, or LibreOffice's Tetra_20_art style's name): its colour (RGB) and effects; null: not one of Writer's. */
        fun art(raw: String?): Pair<Int, TextEffects>? {
            raw ?: return null
            Regex("""Tetra_20_art_([0-9A-F]{6})(_o)?(_s)?""").find(raw)?.let { m ->
                val c = m.groupValues[1].toInt(16)
                return c to TextEffects(if (m.groupValues[2].isNotEmpty()) c else null, m.groupValues[3].isNotEmpty())
            }
            if ("<w:txbxContent>" !in raw || !Regex("""<w:sz w:val="${(PictureXml.ART_PT * 2).toInt()}"/>""").containsMatchIn(raw)) return null
            val c = Regex("""<w:color w:val="([0-9A-Fa-f]{6})"/>""").find(raw)?.groupValues?.get(1)?.toInt(16) ?: return null
            return c to TextEffects.of(raw)
        }
        /** [xml] (a WordArt Writer made) bent as [warp]: Word's prstTxWarp in its bodyPr; LibreOffice's fontwork geometry. */
        fun applied(xml: String, warp: TextWarp, odt: Boolean): String {
            if (odt) return xml.replace(Regex("""<draw:enhanced-geometry\b[^>]*/>|<draw:enhanced-geometry\b[^>]*[^/]>[\s\S]*?</draw:enhanced-geometry>"""),
                Regex.escapeReplacement("""<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:text-path="true" draw:text-path-mode="shape" draw:text-path-scale="shape" draw:type="${warp.fontwork}"/>"""))
            val body = Regex("""<wps:bodyPr\b([^>]*?)(/>|>([\s\S]*?)</wps:bodyPr>)""").find(xml) ?: return xml
            val attrs = body.groupValues[1].replace(Regex("""\sfromWordArt="[^"]*""""), "") + " fromWordArt=\"1\""
            val inner = body.groupValues[3].replace(Regex("""<a:prstTxWarp\b[\s\S]*?</a:prstTxWarp>|<a:prstTxWarp\b[^>]*/>"""), "")
            return xml.replaceRange(body.range, "<wps:bodyPr$attrs><a:prstTxWarp prst=\"${warp.word}\"><a:avLst/></a:prstTxWarp>$inner</wps:bodyPr>")
        }
    }
}
