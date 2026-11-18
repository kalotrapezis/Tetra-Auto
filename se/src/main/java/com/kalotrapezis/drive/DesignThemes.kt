package com.kalotrapezis.drive

/**
 * Design › Colours and Design › Fonts (Word's theme galleries): a Word file's theme (word/theme/theme1.xml) — its colour
 * scheme (two darks, two lights, six accents, the links') and its font scheme (headings', body's). Word draws theme
 * colours from it (shapes, charts, SmartArt, headings that say w:themeColor); Writer does too ([ShapePicture.theme]).
 */
internal enum class ThemeColors(val label: String, @androidx.annotation.StringRes val labelRes: Int, val dk2: Int, val lt2: Int, val accents: List<Int>, val link: Int, val followed: Int) {
    Office("Office", R.string.office_theme_office, 0x44546A, 0xE7E6E6, listOf(0x4472C4, 0xED7D31, 0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47), 0x0563C1, 0x954F72),
    Office2007("Office 2007 – 2010", R.string.office_theme_office2007, 0x1F497D, 0xEEECE1, listOf(0x4F81BD, 0xC0504D, 0x9BBB59, 0x8064A2, 0x4BACC6, 0xF79646), 0x0000FF, 0x800080),
    Blue("Blue", R.string.office_theme_blue, 0x17406D, 0xDBEFF9, listOf(0x0F6FC6, 0x009DD9, 0x0BD0D9, 0x10CF9B, 0x7CCA62, 0xA5C249), 0xF49100, 0x85DFD0),
    Green("Green", R.string.office_theme_green, 0x455F51, 0xE3DED1, listOf(0x549E39, 0x8AB833, 0xC0CF3A, 0x029676, 0x4AB5C4, 0x0989B1), 0x6B9F25, 0xBA6906),
    Red("Red", R.string.office_theme_red, 0x323232, 0xE5C243, listOf(0xA5300F, 0xD55816, 0xE19825, 0xB19C7D, 0x7F5F52, 0xB27D49), 0x6B9F25, 0xB26B02),
    Violet("Violet", R.string.office_theme_violet, 0x373545, 0xDCD8DC, listOf(0xAD84C6, 0x8784C7, 0x5D739A, 0x6997AF, 0x84ACB6, 0x6FAA89), 0x69A020, 0x8C8C8C),
    Grayscale("Grayscale", R.string.office_theme_grayscale, 0x000000, 0xF8F8F8, listOf(0xDDDDDD, 0xB2B2B2, 0x969696, 0x808080, 0x5F5F5F, 0x4D4D4D), 0x5F5F5F, 0x919191);

    /** The scheme as names → colours (with Word's fixed darks and lights). */
    val map: Map<String, Int> get() = mapOf("dk1" to 0x000000, "lt1" to 0xFFFFFF, "dk2" to dk2, "lt2" to lt2, "hlink" to link, "folHlink" to followed) +
        accents.mapIndexed { i, c -> "accent${i + 1}" to c }
}

/** Word's theme font pairs: headings' (major), body's (minor). */
internal enum class ThemeFonts(val label: String, val heading: String, val body: String) {
    Office("Office (Calibri Light, Calibri)", "Calibri Light", "Calibri"),
    Office2007("Office 2007 (Cambria, Calibri)", "Cambria", "Calibri"),
    Arial("Arial", "Arial", "Arial"),
    Times("Times New Roman", "Times New Roman", "Times New Roman"),
    Georgia("Georgia", "Georgia", "Georgia"),
    Garamond("Garamond (Garamond, Trebuchet MS)", "Garamond", "Trebuchet MS"),
    Verdana("Verdana", "Verdana", "Verdana"),
}

internal object DocTheme {
    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
    private fun themePath(parts: Map<String, ByteArray>) = documentPath(parts).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + "theme/theme1.xml"

    /** A Word file's theme colours by name (accent1…, dk2…); Office's when it has no theme. */
    fun colorsOf(parts: Map<String, ByteArray>): Map<String, Int> {
        val xml = parts[themePath(parts)]?.let { String(it, Charsets.UTF_8) } ?: return ThemeColors.Office.map
        val scheme = Regex("""<a:clrScheme\b[\s\S]*?</a:clrScheme>""").find(xml)?.value ?: return ThemeColors.Office.map
        return Regex("""<a:(dk1|lt1|dk2|lt2|accent[1-6]|hlink|folHlink)>([\s\S]*?)</a:\1>""").findAll(scheme).mapNotNull { m ->
            (Regex("""srgbClr val="([0-9A-Fa-f]{6})"""").find(m.groupValues[2])?.groupValues?.get(1) ?: Regex("""lastClr="([0-9A-Fa-f]{6})"""").find(m.groupValues[2])?.groupValues?.get(1))
                ?.toInt(16)?.let { m.groupValues[1] to it }
        }.toMap().let { ThemeColors.Office.map + it }
    }

    /**
     * [set] as the file's theme colours; colours that name the theme (w:color themeColor, its shade or tint) given its
     * new value too, so the pages and Word agree. A file without a theme gets Office's, with these colours.
     */
    fun withColors(source: Map<String, ByteArray>, set: ThemeColors): LinkedHashMap<String, ByteArray> {
        val parts = ensureTheme(source)
        val path = themePath(parts)
        var xml = String(parts.getValue(path), Charsets.UTF_8)
        val scheme = """<a:clrScheme name="${set.label}"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
            """<a:dk2><a:srgbClr val="${hex(set.dk2)}"/></a:dk2><a:lt2><a:srgbClr val="${hex(set.lt2)}"/></a:lt2>""" +
            set.accents.mapIndexed { i, c -> "<a:accent${i + 1}><a:srgbClr val=\"${hex(c)}\"/></a:accent${i + 1}>" }.joinToString("") +
            """<a:hlink><a:srgbClr val="${hex(set.link)}"/></a:hlink><a:folHlink><a:srgbClr val="${hex(set.followed)}"/></a:folHlink></a:clrScheme>"""
        xml = xml.replace(Regex("""<a:clrScheme\b[\s\S]*?</a:clrScheme>"""), Regex.escapeReplacement(scheme))
        parts[path] = xml.toByteArray()
        // Word's w:color with a theme colour: its value worked out again (Word's own rule: shade multiplies, tint lightens).
        val theme = set.map
        val names = mapOf("text1" to "dk1", "background1" to "lt1", "text2" to "dk2", "background2" to "lt2", "hyperlink" to "hlink", "followedHyperlink" to "folHlink")
        for (name in parts.keys.filter { it.startsWith(path.substringBefore("theme/")) && it.endsWith(".xml") && "/theme/" !in it }) {
            val text = String(parts.getValue(name), Charsets.UTF_8)
            if ("w:themeColor=" !in text) continue
            parts[name] = Regex("""<w:color\b[^>]*/>""").replace(text) { m ->
                val tc = Regex("""w:themeColor="(\w+)"""").find(m.value)?.groupValues?.get(1) ?: return@replace m.value
                var c = theme[names[tc] ?: tc] ?: return@replace m.value
                fun ch(f: (Float) -> Float) {
                    fun one(v: Int) = Math.round(f(v / 255f).coerceIn(0f, 1f) * 255)
                    c = (one((c shr 16) and 0xFF) shl 16) or (one((c shr 8) and 0xFF) shl 8) or one(c and 0xFF)
                }
                Regex("""w:themeShade="([0-9A-Fa-f]{2})"""").find(m.value)?.groupValues?.get(1)?.toInt(16)?.let { s -> ch { v -> v * s / 255f } }
                Regex("""w:themeTint="([0-9A-Fa-f]{2})"""").find(m.value)?.groupValues?.get(1)?.toInt(16)?.let { t -> ch { v -> 1 - (1 - v) * t / 255f } }
                m.value.replace(Regex("""w:val="[^"]*""""), "w:val=\"${hex(c)}\"")
            }.toByteArray()
        }
        return parts
    }

    /** [set] as a Word file's theme fonts (headings' and body's). */
    fun withFonts(source: Map<String, ByteArray>, set: ThemeFonts): LinkedHashMap<String, ByteArray> {
        val parts = ensureTheme(source)
        val path = themePath(parts)
        var xml = String(parts.getValue(path), Charsets.UTF_8)
        for ((tag, face) in listOf("majorFont" to set.heading, "minorFont" to set.body))
            xml = xml.replace(Regex("""(<a:$tag>[\s\S]*?<a:latin typeface=")[^"]*""""), "$1${OfficeFormats.esc(face)}\"")
        parts[path] = xml.replace(Regex("""<a:fontScheme name="[^"]*""""), "<a:fontScheme name=\"${OfficeFormats.esc(set.label)}\"").toByteArray()
        return parts
    }

    /** The file with a theme part (Office's, as Word makes it) when it has none: its relationship and content type too. */
    private fun ensureTheme(source: Map<String, ByteArray>): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        val path = themePath(parts)
        if (path in parts) return parts
        parts[path] = OFFICE_THEME.toByteArray()
        val docPath = documentPath(parts)
        val rels = relsPath(docPath)
        val r = parts[rels]?.let { String(it, Charsets.UTF_8) } ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
        val id = generateSequence(1) { it + 1 }.map { "rIdTetraTheme$it" }.first { "\"$it\"" !in r }
        parts[rels] = r.replace("</Relationships>", """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/></Relationships>""").toByteArray()
        val types = String(parts.getValue("[Content_Types].xml"), Charsets.UTF_8)
        parts["[Content_Types].xml"] = types.replace("</Types>", """<Override PartName="/$path" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>""").toByteArray()
        return parts
    }

    /** Office's theme, its colours, fonts and the three of each format Word requires. */
    private val OFFICE_THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme"><a:themeElements>""" +
        """<a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>""" +
        """<a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
        """<a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"><a:tint val="60000"/></a:schemeClr></a:solidFill><a:solidFill><a:schemeClr val="phClr"><a:shade val="80000"/></a:schemeClr></a:solidFill></a:fillStyleLst>""" +
        """<a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>""" +
        """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>""" +
        """<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"><a:tint val="95000"/></a:schemeClr></a:solidFill><a:solidFill><a:schemeClr val="phClr"><a:shade val="80000"/></a:schemeClr></a:solidFill></a:bgFillStyleLst></a:fmtScheme>""" +
        """</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""
}
