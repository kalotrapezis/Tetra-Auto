package com.kalotrapezis.drive

import java.io.File

/**
 * Templates (asked 16 Oct: themes for a school or a company, made by Claude and brought into Tetra): the .potx and .pptx
 * files in Tetra's Documents/Templates (it syncs with the computer's), offered as themes in Slides › Design and as
 * the start of a new presentation.
 */
internal object SlidesTemplates {
    fun dir(): File = File(TetraFolder.root(android.os.Environment.getExternalStorageDirectory()), "Documents/Templates")
    fun files(): List<File> = dir().listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in setOf("potx", "pptx") }.sortedBy { it.name.lowercase() }

    /** File › Save as template: the deck [deck] (a .pptx) as a .potx [target], its main part said to be a template's, as PowerPoint saves one. */
    fun template(deck: File, target: File) {
        java.util.zip.ZipFile(deck).use { zip ->
            val types = zip.getEntry("[Content_Types].xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes()) } } ?: error("Not a presentation.")
            SheetSave.rezip(zip, target, mapOf("[Content_Types].xml" to types.replace("presentationml.presentation.main+xml", "presentationml.template.main+xml").toByteArray()), stored = null)
        }
    }
    /** A new presentation [target] from [template]: a .potx's main part said to be a presentation's, as PowerPoint does on "New from template". */
    fun make(template: File, target: File) {
        if (!template.extension.equals("potx", true)) { template.copyTo(target, overwrite = true); return }
        java.util.zip.ZipFile(template).use { zip ->
            val types = zip.getEntry("[Content_Types].xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes()) } } ?: error("Not a template.")
            SheetSave.rezip(zip, target, mapOf("[Content_Types].xml" to types.replace("presentationml.template.main+xml", "presentationml.presentation.main+xml").toByteArray()), stored = null)
        }
    }
}
