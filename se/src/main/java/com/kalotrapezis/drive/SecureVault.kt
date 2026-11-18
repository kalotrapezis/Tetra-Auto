package com.kalotrapezis.drive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

internal data class VaultImport(
    val photoKey: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val takenMillis: Long,
    val isVideo: Boolean,
)

internal data class VaultItem(
    val id: Long,
    val fileName: String,
    val photoKey: String,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val takenMillis: Long,
    val sizeBytes: Long,
    val sha256: String,
    val isVideo: Boolean,
)

/** Files are private to this app and Android encrypts internal storage. */
internal class SecureVault(private val context: Context) : SQLiteOpenHelper(context, "hidden_vault.db", null, 1) {
    private val preferences = context.getSharedPreferences("hidden_vault", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "hidden_media").apply {
        mkdirs()
        listFiles { _, name -> name.endsWith(".part") }?.forEach(File::delete)
    }

    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE vault_media (
            id INTEGER PRIMARY KEY,
            file_name TEXT NOT NULL UNIQUE,
            photo_key TEXT NOT NULL,
            display_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            taken_millis INTEGER NOT NULL,
            size_bytes INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            is_video INTEGER NOT NULL
        )""".trimIndent(),
    )

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun warningAccepted(): Boolean = preferences.getBoolean("uninstall_warning_accepted", false)

    fun acceptWarning() { preferences.edit().putBoolean("uninstall_warning_accepted", true).apply() }

    fun items(): List<VaultItem> = readableDatabase.query("vault_media", null, null, null, null, null, "taken_millis DESC")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(VaultItem(
            cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
            cursor.getString(cursor.getColumnIndexOrThrow("photo_key")),
            cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
            cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
            cursor.getString(cursor.getColumnIndexOrThrow("relative_path")),
            cursor.getLong(cursor.getColumnIndexOrThrow("taken_millis")),
            cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
            cursor.getString(cursor.getColumnIndexOrThrow("sha256")),
            cursor.getInt(cursor.getColumnIndexOrThrow("is_video")) != 0,
        )) } }

    fun stage(imports: List<VaultImport>): List<VaultItem> {
        val created = mutableListOf<VaultItem>()
        try {
            imports.forEach { source ->
                val extension = source.displayName.substringAfterLast('.', "bin").filter(Char::isLetterOrDigit).take(10).ifBlank { "bin" }
                val fileName = "${UUID.randomUUID()}.$extension"
                val part = File(directory, "$fileName.part")
                val target = File(directory, fileName)
                val sourceDigest = MessageDigest.getInstance("SHA-256")
                val size = context.contentResolver.openInputStream(source.uri).use { input ->
                    requireNotNull(input) { context.getString(R.string.home_vault_cannot_read, source.displayName) }
                    part.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            sourceDigest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            total += read
                        }
                        output.fd.sync()
                        total
                    }
                }
                val sourceHash = sourceDigest.digest().toHex()
                require(size > 0 && digest(part) == sourceHash) { context.getString(R.string.home_vault_copy_failed, source.displayName) }
                require(part.renameTo(target)) { context.getString(R.string.home_vault_not_finished) }
                val id = try { writableDatabase.insertOrThrow("vault_media", null, ContentValues().apply {
                    put("file_name", fileName)
                    put("photo_key", source.photoKey)
                    put("display_name", source.displayName)
                    put("mime_type", source.mimeType)
                    put("relative_path", source.relativePath)
                    put("taken_millis", source.takenMillis)
                    put("size_bytes", size)
                    put("sha256", sourceHash)
                    put("is_video", if (source.isVideo) 1 else 0)
                }) } catch (failure: Throwable) {
                    target.delete()
                    throw failure
                }
                created += VaultItem(id, fileName, source.photoKey, source.displayName, source.mimeType, source.relativePath, source.takenMillis, size, sourceHash, source.isVideo)
            }
            return created
        } catch (failure: Throwable) {
            remove(created)
            throw failure
        }
    }

    fun restore(items: List<VaultItem>) {
        items.forEach { item ->
            val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val restored = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, item.relativePath)
                put(MediaStore.MediaColumns.DATE_TAKEN, item.takenMillis)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })) { context.getString(R.string.home_vault_not_created, item.displayName) }
            try {
                context.contentResolver.openOutputStream(restored, "w").use { output ->
                    requireNotNull(output) { context.getString(R.string.home_vault_cannot_restore, item.displayName) }
                    FileInputStream(file(item)).use { it.copyTo(output) }
                }
                val verified = context.contentResolver.openInputStream(restored).use { input ->
                    requireNotNull(input)
                    val md = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        md.update(buffer, 0, read)
                    }
                    md.digest().toHex() == item.sha256
                }
                require(verified) { context.getString(R.string.home_vault_restore_failed, item.displayName) }
                context.contentResolver.update(restored, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                remove(listOf(item))
            } catch (failure: Throwable) {
                context.contentResolver.delete(restored, null, null)
                throw failure
            }
        }
    }

    fun remove(items: List<VaultItem>) {
        items.forEach { item ->
            file(item).delete()
            writableDatabase.delete("vault_media", "id = ?", arrayOf(item.id.toString()))
        }
    }

    fun contentUri(item: VaultItem): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file(item))

    private fun file(item: VaultItem) = File(directory, item.fileName)
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().toHex()
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
