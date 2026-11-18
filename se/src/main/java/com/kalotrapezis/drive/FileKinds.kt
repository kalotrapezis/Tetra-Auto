package com.kalotrapezis.drive

import org.json.JSONObject

/**
 * Files' categories (the user, 24 Oct: "icons for file types … so the user can see at a glance what file it is"): a
 * file's icon by its extension, from file-kinds.json — the one table the desktop copies too.
 */
internal object FileKinds {
    /** Each extension (lower case) and the icon of its category ("docs-word", "Archives" …). */
    val iconOf: Map<String, String> by lazy {
        val kinds = JSONObject(FileKinds::class.java.getResourceAsStream("/file-kinds.json")!!.bufferedReader().use { it.readText() }).getJSONObject("kinds")
        buildMap { kinds.keys().forEach { k -> val kind = kinds.getJSONObject(k); val list = kind.getJSONArray("extensions")
            for (i in 0 until list.length()) put(list.getString(i), kind.getString("icon")) } }
    }

    /** The category icon's drawable for a file's [name]; null: none (its own icon). */
    fun icon(name: String): Int? = iconOf[name.substringAfterLast('.', "").lowercase()]?.let(::drawable)

    fun drawable(icon: String): Int? = when (icon) { // made by tools/file-icons.py
        "docs-word" -> R.drawable.kind_docs_word
        "excel" -> R.drawable.kind_excel
        "slides" -> R.drawable.kind_slides
        "pdf" -> R.drawable.kind_pdf
        "images" -> R.drawable.kind_images
        "video" -> R.drawable.kind_video
        "music" -> R.drawable.kind_music
        "docs-txt" -> R.drawable.kind_docs_txt
        "Archives" -> R.drawable.kind_archives
        "bash-scripts" -> R.drawable.kind_bash_scripts
        "ebooks" -> R.drawable.kind_ebooks
        "html-pages" -> R.drawable.kind_html_pages
        "programs-executables" -> R.drawable.kind_programs_executables
        else -> null
    }
}
