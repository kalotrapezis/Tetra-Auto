package com.kalotrapezis.drive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal data class PhotoState(val favorite: Boolean = false)
internal data class PhotoLocation(
    val latitude: Double,
    val longitude: Double,
    val placeName: String? = null,
    val placeResolved: Boolean = false,
)
internal enum class PhotoSearchQuality { Fast, Advanced }

/** `here` of its photos are on this device and `elsewhere` are not — kept by the computer after a Move (25 September). */
internal data class PhotoCollection(val id: Long, val name: String, val storedCount: Int, val uuid: String? = null, val here: Int = storedCount, val elsewhere: Int = 0)
internal data class FaceSample(val photoKey: String, val bounds: android.graphics.Rect)
/** [targetName]: the name the kept person had before it took the combined one's (a "Person 41" given way to a name). */
internal data class FaceMergeUndo(val sourceName: String, val sampleIds: List<Long>, val sourceUuid: String? = null, val targetId: Long? = null, val targetName: String? = null)
/** The head a combined group was shown by, so History can draw it after the group itself is gone. */
internal data class FaceHead(val photoKey: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class FaceMerge(val id: Long, val sourceName: String, val sourceUuid: String?, val sampleIds: List<Long>, val mergedAt: Long, val head: FaceHead?, val targetId: Long? = null, val targetName: String? = null)
internal data class PendingReview(
    val photoKey: String,
    val question: String,
    val candidateGroupId: Long? = null,
    val faceSampleId: Long? = null,
    val faceSample: FaceSample? = null,
    /** the person a face question asks about; null: the document question */
    val name: String? = null,
)
/** [here]: false when every photo of this person has left this phone (a Move): the cover is then the computer's crop of [coverUuid]. */
internal data class FaceGroup(val id: Long, val name: String, val count: Int, val photoKey: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val coverUuid: String? = null, val here: Boolean = true)
internal data class FavoriteRecord(val photoKey: String, val favorite: Boolean, val updatedAt: Long)
internal data class CollectionRecord(val uuid: String, val name: String, val deleted: Boolean, val updatedAt: Long, val hiddenFromGallery: Boolean = false)
internal data class CollectionItemRecord(val collectionUuid: String, val photoKey: String, val deleted: Boolean, val updatedAt: Long)
internal data class PersonRecord(val uuid: String, val name: String, val updatedAt: Long, val coverUuid: String? = null, val hidden: Boolean = false)
/**
 * One answered question, as the other device can recognise it: the face and the person it was asked about, both
 * by the uuids that already cross. A question nobody has answered stays here — it is this device's own
 * uncertainty, worked out from what it holds — but an answer is a decision, and decisions travel.
 */
/** One face of a person, as the picker shows it. */
internal data class FaceOfPerson(val uuid: String, val photoKey: String, val bounds: Rect, val quality: Float, val chosen: Boolean)

internal data class ReviewRecord(val faceUuid: String, val personUuid: String, val state: String, val updatedAt: Long)

internal data class FaceRecord(val uuid: String, val photoKey: String, val bounds: android.graphics.Rect, val embedding: ByteArray, val quality: Float, val personUuid: String?, val updatedAt: Long)
internal data class DocumentRecord(val photoKey: String, val type: String?, val confidence: Float, val userVerified: Boolean, val updatedAt: Long)
internal fun FaceGroup.bounds(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)

/** Private metadata only. It never changes the MediaStore item or its bytes. */
internal class PhotoMetadataStore(context: Context) : SQLiteOpenHelper(context, "photo_metadata.db", null, 23) {
    private val appContext = context.applicationContext

    private companion object {
        /**
         * Tidying away people nobody has left any faces for — **guesses only**.
         *
         * A name is not a thing this app may throw away. A person you have named and whose photos have all gone
         * to the Trash, or one whose name arrived from another device a moment before their faces, has an empty
         * group for a while; deleting it loses the name for good, and the next sync or the next restore brings
         * the faces back to nobody. An empty person is simply not shown (`groupsFrom` needs a face to draw), so
         * keeping one costs a row and nothing else. Only "Person 41" is swept up, because that is a guess and
         * guesses are what a rescan exists to redo.
         */
        const val FORGET_EMPTY_GUESSES = "DELETE FROM face_groups WHERE name GLOB 'Person [0-9]*' " +
            "AND id NOT IN (SELECT DISTINCT group_id FROM face_samples WHERE group_id IS NOT NULL)"
        /** Your answer per folder of this phone (FolderRules). A folder's files are this device's, so it does not sync; its album does. */
        const val DEVICE_FOLDERS = "CREATE TABLE IF NOT EXISTS device_folders (name TEXT PRIMARY KEY COLLATE NOCASE, included INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
        const val MERGE_HISTORY = "CREATE TABLE IF NOT EXISTS face_merges (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "target_id INTEGER NOT NULL, source_name TEXT NOT NULL, source_uuid TEXT, sample_ids TEXT NOT NULL, merged_at INTEGER NOT NULL, target_name TEXT)"
    }
    private val context = context.applicationContext
    private val galleryPreferences = this.context.getSharedPreferences("photo_gallery", Context.MODE_PRIVATE)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(MERGE_HISTORY)
        db.execSQL(DEVICE_FOLDERS)
        db.execSQL("CREATE TABLE photo_state (photo_key TEXT PRIMARY KEY, favorite INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE collections (id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE collection_membership (collection_id INTEGER NOT NULL, photo_key TEXT NOT NULL, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(collection_id, photo_key), FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE)")
        createAiTables(db)
        createFaceGroupingTables(db)
        createLabelTables(db)
        createLocationTable(db)
        addSyncIds(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.inTransaction {
            execSQL("CREATE TABLE photo_state_v2 (photo_key TEXT PRIMARY KEY, favorite INTEGER NOT NULL DEFAULT 0)")
            execSQL("INSERT INTO photo_state_v2(photo_key, favorite) SELECT photo_key, favorite FROM photo_state")
            execSQL("DROP TABLE photo_state")
            execSQL("ALTER TABLE photo_state_v2 RENAME TO photo_state")
            createAiTables(this)
        }
        if (oldVersion < 3) createFaceTables(db)
        if (oldVersion == 3) db.execSQL("ALTER TABLE face_samples ADD COLUMN group_id INTEGER")
        if (oldVersion < 4) createFaceGroupingTables(db)
        if (oldVersion == 4) db.execSQL("ALTER TABLE face_reviews ADD COLUMN face_sample_id INTEGER")
        if (oldVersion < 6) createLabelTables(db)
        if (oldVersion < 7) db.execSQL("UPDATE photo_ai_record SET type = NULL, confidence = 0, review_state = 'none' WHERE user_verified = 0 AND photo_key NOT IN (SELECT photo_key FROM photo_ai_label WHERE lower(label) = 'paper')")
        if (oldVersion < 8) {
            if (oldVersion >= 3) db.execSQL("ALTER TABLE face_samples ADD COLUMN quality REAL NOT NULL DEFAULT 1.0")
            db.inTransaction {
                delete("face_reviews", null, null)
                delete("face_samples", null, null)
                delete("face_groups", null, null)
            }
        }
        if (oldVersion < 9) db.inTransaction {
            // Reject the old loose grouping index; media files and other local metadata stay untouched.
            delete("face_reviews", null, null)
            delete("face_samples", null, null)
            delete("face_groups", null, null)
        }
        if (oldVersion < 10) db.inTransaction {
            // Landmark-aligned embeddings cannot be mixed with the former box-crop embeddings.
            delete("face_reviews", null, null)
            delete("face_samples", null, null)
            delete("face_groups", null, null)
        }
        if (oldVersion < 11) createLocationTable(db)
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE photo_location ADD COLUMN place_name TEXT")
            db.execSQL("ALTER TABLE photo_location ADD COLUMN place_resolved INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 13) addSyncIds(db)
        // ponytail: sync (SYNC_PLAN.md phase 6a) needs updated_at for last-write-wins; only
        // photo_ai_record does for now — faces/people get the same treatment in phase 6c.
        if (oldVersion < 14) db.inTransaction {
            execSQL("ALTER TABLE photo_ai_record ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            execSQL("UPDATE photo_ai_record SET updated_at = ${System.currentTimeMillis()} WHERE updated_at = 0")
        }
        // Phase 6b/6c: favorites, collections and people/faces sync the same way documents did — last-write-wins
        // by updated_at, removals as tombstones so they can travel instead of silently reappearing.
        if (oldVersion < 15) db.inTransaction {
            val now = System.currentTimeMillis()
            for (table in listOf("photo_state", "collections", "collection_membership", "face_groups", "face_samples")) {
                execSQL("ALTER TABLE $table ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                execSQL("UPDATE $table SET updated_at = $now")
            }
            for (table in listOf("collections", "collection_membership")) execSQL("ALTER TABLE $table ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        // "Hide this album from Gallery" belongs to the album, not to this phone, so it moves out of preferences
        // and onto the collection, where it travels with it (SYNC_PLAN.md "Which settings sync").
        if (oldVersion < 17) db.execSQL(MERGE_HISTORY)
        // An answer to Help organize is a decision, and decisions travel; a question is local. So a review needs
        // a time, to be told apart from one answered on another device a minute later.
        if (oldVersion < 18) db.execSQL("ALTER TABLE face_reviews ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        // When a face's photo was taken: evidence about who is in it that the pixels do not carry (SAME_DAY_BONUS).
        // Faces recorded before this have 0, which is a day of its own and shares itself with nothing.
        if (oldVersion < 19) db.execSQL("ALTER TABLE face_samples ADD COLUMN taken_at INTEGER NOT NULL DEFAULT 0")
        // The face a person is shown by, when somebody has chosen one. Null means "the best one we can find".
        if (oldVersion < 20) db.execSQL("ALTER TABLE face_groups ADD COLUMN cover_uuid TEXT")
        // Forgotten: a TV presenter, a stranger in the background. Kept, so their next photo still finds them and
        // does not come back as a new person — just never shown, searched or asked about.
        if (oldVersion < 21) db.execSQL("ALTER TABLE face_groups ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 22) db.execSQL(DEVICE_FOLDERS)
        // The kept person's own name before a combine gave it the other's, so undo can give it back (24 Oct).
        if (oldVersion in 17..22) db.execSQL("ALTER TABLE face_merges ADD COLUMN target_name TEXT")
        if (oldVersion < 16) db.inTransaction {
            execSQL("ALTER TABLE collections ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            val hidden = context.getSharedPreferences("photo_gallery", Context.MODE_PRIVATE).getStringSet("hidden_albums", emptySet()).orEmpty()
            hidden.forEach { uuid -> update("collections", ContentValues().apply { put("hidden", 1) }, "uuid = ?", arrayOf(uuid)) }
        }
    }

    /**
     * Permanent ids for sync (SYNC_PLAN.md): people, collections and faces keep their local integer ids and get a
     * UUID that is the same on every device. Existing rows are only extended, never rewritten.
     */
    private fun addSyncIds(db: SQLiteDatabase) = db.inTransaction {
        for (table in listOf("face_groups", "collections", "face_samples")) {
            execSQL("ALTER TABLE $table ADD COLUMN uuid TEXT")
            val ids = rawQuery("SELECT id FROM $table", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            ids.forEach { id -> update(table, ContentValues().apply { put("uuid", UUID.randomUUID().toString()) }, "id = ?", arrayOf(id.toString())) }
            execSQL("CREATE UNIQUE INDEX ${table}_uuid ON $table(uuid)")
        }
    }

    /**
     * After "Save" replaces a photo, its size and so its key change. Favorite, collections and location follow the
     * photo; faces and labels are left to re-analysis because crop or rotation moved them. A photo moved to another
     * folder (`sameContent`) changes key too, since the key includes the folder, but its pixels did not: everything
     * follows it, faces and labels included.
     */
    fun rekeyPhoto(oldKey: String, newKey: String, sameContent: Boolean = false) {
        if (oldKey == newKey) return
        val tables = listOf("photo_state", "collection_membership", "photo_location") +
            if (sameContent) listOf("photo_ai_record", "photo_ai_label", "face_samples", "face_reviews") else emptyList()
        writableDatabase.inTransaction {
            for (table in tables) {
                execSQL("UPDATE OR IGNORE $table SET photo_key = ? WHERE photo_key = ?", arrayOf(newKey, oldKey))
                delete(table, "photo_key = ?", arrayOf(oldKey)) // only rows that already existed for the new key are left
            }
        }
    }

    fun states(keys: Collection<String>): Map<String, PhotoState> {
        if (keys.isEmpty()) return emptyMap()
        return readableDatabase.query(
            "photo_state", arrayOf("photo_key", "favorite"),
            "photo_key IN (${keys.joinToString { "?" }})", keys.toTypedArray(), null, null, null,
        ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), PhotoState(cursor.getInt(1) != 0)) } }
    }

    fun collections(): List<PhotoCollection> = readableDatabase.rawQuery(
        "SELECT c.id, c.name, COUNT(m.photo_key), c.uuid FROM collections c LEFT JOIN collection_membership m ON m.collection_id = c.id AND m.deleted = 0 WHERE c.deleted = 0 GROUP BY c.id ORDER BY c.name COLLATE NOCASE",
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(PhotoCollection(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3))) } }

    fun collectionKeys(collectionId: Long): Set<String> = readableDatabase.query(
        "collection_membership", arrayOf("photo_key"), "collection_id = ? AND deleted = 0", arrayOf(collectionId.toString()), null, null, null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun createCollection(rawName: String): PhotoCollection = writableDatabase.inTransaction {
        val name = PhotoMetadataRules.collectionName(rawName)
        val now = System.currentTimeMillis()
        val buried = rawQuery("SELECT id, uuid FROM collections WHERE name = ? COLLATE NOCASE AND deleted = 1", arrayOf(name)).use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
        if (buried != null) { // the name was never freed by the tombstone, so reuse the row (and its UUID, so other devices see one collection)
            update("collections", ContentValues().apply { put("deleted", 0); put("updated_at", now) }, "id = ?", arrayOf(buried.first.toString()))
            return@inTransaction PhotoCollection(buried.first, name, 0, buried.second)
        }
        val uuid = UUID.randomUUID().toString()
        val id = insertOrThrow("collections", null, ContentValues().apply { put("name", name); put("uuid", uuid); put("updated_at", now) })
        PhotoCollection(id, name, 0, uuid)
    }

    fun addToCollection(collectionId: Long, keys: Collection<String>) = setMembership(collectionId, keys, member = true)

    /**
     * Lower-cased folder name → whether its photos are shown here. A folder with no entry has never been asked about.
     *
     * The folder's album is what travels: an album of the folder's name means some device said yes to it. On a
     * device that syncs photos **both ways** that yes wins over a No given here — a folder that is On anywhere in a
     * two-way chain is On everywhere in it (asked 2026-09-25). A device that only sends keeps its own answers.
     */
    fun folderChoices(): Map<String, Boolean> = buildMap {
        val albums = readableDatabase.rawQuery("SELECT name FROM collections WHERE deleted = 0", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0).lowercase()) } }
        albums.forEach { put(it, true) }
        val chain = photosBothWays()
        readableDatabase.rawQuery("SELECT name, included FROM device_folders", null).use { c -> while (c.moveToNext()) {
            val name = c.getString(0).lowercase()
            val on = c.getInt(1) != 0
            if (on || !(chain && name in albums)) put(name, on)
        } }
    }

    /** Whether this device is paired and its photos go both ways (the rules the computer last gave, SyncStore). */
    private fun photosBothWays(): Boolean {
        val prefs = appContext.getSharedPreferences("sync_pairing", Context.MODE_PRIVATE)
        if (prefs.getString("token", null) == null) return false
        val photos = prefs.getString("connections", null)?.split(';')?.firstOrNull { it.startsWith("photos,") } ?: return true // default: both
        return photos.split(',').getOrNull(1) == "both"
    }

    fun setFolderIncluded(name: String, included: Boolean) {
        writableDatabase.insertWithOnConflict("device_folders", null, ContentValues().apply {
            put("name", name); put("included", if (included) 1 else 0); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * An included folder is an album named after it, kept full as photos arrive. A photo you took out of the
     * album keeps its tombstone and is not put back; the album's membership syncs like any other album's.
     */
    fun fillFolderAlbum(name: String, keys: Collection<String>) = writableDatabase.inTransaction {
        val id = rawQuery("SELECT id FROM collections WHERE name = ? COLLATE NOCASE AND deleted = 0", arrayOf(name)).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: createCollection(name).id
        val now = System.currentTimeMillis()
        keys.forEach { key ->
            insertWithOnConflict("collection_membership", null, ContentValues().apply {
                put("collection_id", id); put("photo_key", key); put("updated_at", now); put("deleted", 0)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun removeFromCollection(collectionId: Long, keys: Collection<String>) = setMembership(collectionId, keys, member = false)

    /**
     * A folder album holds that folder's photos only: one whose file is now in another folder leaves it (2026-10-01 —
     * a Viber photo moved to Camera came back into the hidden Viber album from an older record, and so vanished
     * from the gallery). The removal is newer than whatever put it there, so it wins on the computer too.
     */
    fun pruneFolderAlbum(name: String, keysElsewhere: Collection<String>) {
        val id = collectionIdNamed(name) ?: return
        val members = collectionKeys(id)
        keysElsewhere.filter { it in members }.takeIf { it.isNotEmpty() }?.let { removeFromCollection(id, it) }
    }

    fun collectionIdNamed(name: String): Long? =
        readableDatabase.rawQuery("SELECT id FROM collections WHERE name = ? COLLATE NOCASE AND deleted = 0", arrayOf(name)).use { if (it.moveToFirst()) it.getLong(0) else null }

    /** A tombstone, not a delete: the removal has to reach the computer, and photos stay in the library. */
    fun deleteCollection(collectionId: Long) = writableDatabase.inTransaction {
        val now = System.currentTimeMillis()
        update("collections", ContentValues().apply { put("deleted", 1); put("updated_at", now) }, "id = ?", arrayOf(collectionId.toString()))
        update("collection_membership", ContentValues().apply { put("deleted", 1); put("updated_at", now) }, "collection_id = ? AND deleted = 0", arrayOf(collectionId.toString()))
    }

    private fun setMembership(collectionId: Long, keys: Collection<String>, member: Boolean) = writableDatabase.inTransaction {
        val now = System.currentTimeMillis()
        keys.distinct().forEach { key ->
            insertWithOnConflict("collection_membership", null, ContentValues().apply {
                put("collection_id", collectionId)
                put("photo_key", key)
                put("deleted", if (member) 0 else 1)
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun setFavorite(keys: Collection<String>, favorite: Boolean) = setState(keys, "favorite", favorite)

    fun locations(keys: Collection<String>): Map<String, PhotoLocation?> {
        if (keys.isEmpty()) return emptyMap()
        return readableDatabase.query(
            "photo_location", arrayOf("photo_key", "latitude", "longitude", "place_name", "place_resolved"),
            "photo_key IN (${keys.joinToString { "?" }})", keys.toTypedArray(), null, null, null,
        ).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), if (cursor.isNull(1) || cursor.isNull(2)) null else PhotoLocation(cursor.getDouble(1), cursor.getDouble(2), cursor.getString(3), cursor.getInt(4) != 0))
        } }
    }

    fun recordLocation(photoKey: String, location: PhotoLocation?) {
        writableDatabase.insertWithOnConflict("photo_location", null, ContentValues().apply {
            put("photo_key", photoKey)
            if (location == null) {
                putNull("latitude")
                putNull("longitude")
            } else {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("place_name", location.placeName)
                put("place_resolved", if (location.placeResolved) 1 else 0)
            }
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordPlaceName(photoKey: String, placeName: String?) {
        writableDatabase.update("photo_location", ContentValues().apply {
            put("place_name", placeName)
            put("place_resolved", 1)
        }, "photo_key = ?", arrayOf(photoKey))
    }

    fun forgetPhotos(keys: Collection<String>) = writableDatabase.inTransaction {
        val unique = keys.distinct()
        if (unique.isEmpty()) return@inTransaction
        val placeholders = unique.joinToString { "?" }
        val arguments = unique.toTypedArray()
        execSQL("DELETE FROM face_reviews WHERE photo_key IN ($placeholders) OR face_sample_id IN (SELECT id FROM face_samples WHERE photo_key IN ($placeholders))", arguments + arguments)
        // A photo leaving the gallery does not un-recognise the person in it. The faces of somebody you have
        // named stay — they are not shown, because the photo that held them is gone (`groupsFrom` only draws
        // what is still there), but they remain what she is recognised by, so a photo restored from the Trash
        // or arriving from another device comes back *to her* instead of starting a stranger. Faces of people
        // nobody has named are guesses about a photo that no longer exists, and go with it.
        execSQL(
            "DELETE FROM face_samples WHERE photo_key IN ($placeholders) AND (group_id IS NULL OR group_id IN " +
                "(SELECT id FROM face_groups WHERE name GLOB 'Person [0-9]*'))",
            arguments,
        )
        execSQL(FORGET_EMPTY_GUESSES)
        execSQL("DELETE FROM face_reviews WHERE candidate_group_id NOT IN (SELECT id FROM face_groups)")
        listOf("photo_ai_label", "photo_ai_record", "collection_membership", "photo_state", "photo_location").forEach { table ->
            delete(table, "photo_key IN ($placeholders)", arguments)
        }
    }

    fun classifiedKeys(type: String): Set<String> = keysFor("SELECT photo_key FROM photo_ai_record WHERE type = ?", arrayOf(type))

    fun reviewKeys(): Set<String> = keysFor(
        "SELECT photo_key FROM photo_ai_record WHERE review_state = 'pending' UNION SELECT r.photo_key FROM face_reviews r JOIN face_samples s ON s.id = r.face_sample_id JOIN face_groups g ON g.id = r.candidate_group_id WHERE $LIVE_REVIEW",
        emptyArray(),
    )

    fun nextReview(): PendingReview? = pendingReviews().firstOrNull()

    /** Every open question, documents first, then faces. */
    fun pendingReviews(): List<PendingReview> = readableDatabase.query(
        "photo_ai_record", arrayOf("photo_key"), "review_state = 'pending'", null, null, null, null,
    ).use { c -> buildList { while (c.moveToNext()) add(PendingReview(c.getString(0), "Is this a document?")) } } +
        readableDatabase.rawQuery(
            "SELECT r.photo_key, r.candidate_group_id, r.face_sample_id, s.left_edge, s.top_edge, s.right_edge, s.bottom_edge, g.name FROM face_reviews r JOIN face_samples s ON s.id = r.face_sample_id JOIN face_groups g ON g.id = r.candidate_group_id WHERE $LIVE_REVIEW ORDER BY r.updated_at", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(PendingReview(
                    photoKey = c.getString(0),
                    question = "Is this ${c.getString(7)}?",
                    name = c.getString(7),
                    candidateGroupId = c.getLong(1),
                    faceSampleId = c.getLong(2),
                    faceSample = FaceSample(c.getString(0), android.graphics.Rect(c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6))),
                ))
            }
        }

    fun resolveReview(review: PendingReview, accepted: Boolean) = writableDatabase.inTransaction {
        if (review.candidateGroupId == null) update("photo_ai_record", ContentValues().apply {
            put("type", if (accepted) "document" else null as String?)
            put("user_verified", 1)
            put("review_state", "none")
            put("updated_at", System.currentTimeMillis())
        }, "photo_key = ?", arrayOf(review.photoKey)) else {
            if (accepted) update("face_samples", ContentValues().apply {
                put("group_id", review.candidateGroupId); put("updated_at", System.currentTimeMillis())
            }, "id = ?", arrayOf(review.faceSampleId.toString()))
            update("face_reviews", ContentValues().apply {
                put("state", if (accepted) "accepted" else "rejected"); put("updated_at", System.currentTimeMillis())
            }, "face_sample_id = ? AND candidate_group_id = ?", arrayOf(review.faceSampleId.toString(), review.candidateGroupId.toString()))
        }
    }

    fun skipReview(review: PendingReview) = writableDatabase.inTransaction {
        if (review.candidateGroupId == null) update("photo_ai_record", ContentValues().apply { put("review_state", "none") }, "photo_key = ?", arrayOf(review.photoKey))
        else update("face_reviews", ContentValues().apply {
            put("state", "skipped"); put("updated_at", System.currentTimeMillis())
        }, "face_sample_id = ? AND candidate_group_id = ?", arrayOf(review.faceSampleId.toString(), review.candidateGroupId.toString()))
    }

    /** A guess ("Person 41") with no face left: gone, with what pointed at it. Names stay (FORGET_EMPTY_GUESSES). */
    fun forgetEmptyGuesses() = writableDatabase.inTransaction {
        execSQL(FORGET_EMPTY_GUESSES)
        execSQL("DELETE FROM face_reviews WHERE candidate_group_id NOT IN (SELECT id FROM face_groups)")
        execSQL("DELETE FROM face_merges WHERE target_id NOT IN (SELECT id FROM face_groups)")
    }

    fun peopleKeys(): Set<String> = keysFor("SELECT DISTINCT photo_key FROM face_samples", emptyArray())

    /**
     * The people to show, and the face to show them by.
     *
     * A group is only as alive as its photos: when every photo of a person has been deleted there is nothing left
     * to show, so the group does not appear (the rows stay, because Android's own Trash holds a deleted photo for
     * thirty days and restoring it should bring the person back, name and all). A **named** person whose photos all
     * went to the computer ([onComputer]: the receipts' keys) stays (the user, 24 Oct: after the Move the phone
     * showed 39 of its 92 people); a guess ("Person 41") with nothing here does not.
     *
     * The cover is the **best face**, not the first one found. Since the computer's faces now arrive with their
     * quality score, measured by the same formula, the better portrait wins wherever it was found — which is
     * usually the computer, because it detects at a larger size.
     */
    fun faceGroups(livePhotoKeys: Set<String>? = null, forgotten: Boolean = false, onComputer: Set<String> = emptySet()): List<FaceGroup> =
        groupsFrom(livePhotoKeys, null, forgotten, onComputer)
        .sortedWith(compareBy<FaceGroup> { isGeneratedPersonName(it.name) }.thenBy { it.name.lowercase(Locale.ROOT) })

    fun faceGroup(groupId: Long, livePhotoKeys: Set<String>? = null): FaceGroup? = groupsFrom(livePhotoKeys, groupId).firstOrNull()

    private fun groupsFrom(livePhotoKeys: Set<String>?, onlyGroup: Long?, forgotten: Boolean = false, onComputer: Set<String> = emptySet()): List<FaceGroup> = readableDatabase.rawQuery(
        "SELECT g.id, g.name, s.photo_key, s.left_edge, s.top_edge, s.right_edge, s.bottom_edge, s.quality, " +
            "CASE WHEN g.cover_uuid IS NOT NULL AND g.cover_uuid = s.uuid THEN 1 ELSE 0 END, s.uuid " +
            "FROM face_groups g JOIN face_samples s ON s.group_id = g.id" + if (onlyGroup != null) " WHERE g.id = ?" else " WHERE g.hidden = ?",
        arrayOf(onlyGroup?.toString() ?: if (forgotten) "1" else "0"),
    ).use { cursor ->
        class Sample(val key: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val quality: Float, val chosen: Boolean, val uuid: String?)
        val names = HashMap<Long, String>()
        val samples = HashMap<Long, MutableList<Sample>>()
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            names[id] = cursor.getString(1)
            samples.getOrPut(id) { mutableListOf() }.add(Sample(cursor.getString(2), cursor.getInt(3), cursor.getInt(4), cursor.getInt(5),
                cursor.getInt(6), cursor.getFloat(7), cursor.getInt(8) == 1, cursor.getString(9)))
        }
        samples.mapNotNull { (id, all) ->
            val name = names.getValue(id)
            val live = if (livePhotoKeys == null) all else all.filter { it.key in livePhotoKeys }
            val list = live.ifEmpty { if (isGeneratedPersonName(name)) return@mapNotNull null else all.filter { it.key in onComputer } }
            // A face somebody chose is the face, whatever the scores say. Otherwise the best one wins.
            val cover = list.firstOrNull { it.chosen } ?: list.maxByOrNull { it.quality } ?: return@mapNotNull null
            FaceGroup(id, name, list.distinctBy { it.key }.size, cover.key, cover.left, cover.top, cover.right, cover.bottom, cover.uuid, here = live.isNotEmpty())
        }
    }

    fun faceGroupKeys(groupId: Long): Set<String> = keysFor("SELECT DISTINCT photo_key FROM face_samples WHERE group_id = ?", arrayOf(groupId.toString()))

    fun labelsByPhoto(keys: Collection<String>): Map<String, List<String>> = valuesByPhoto(
        "SELECT photo_key, label FROM photo_ai_label WHERE photo_key IN (${keys.joinToString { "?" }}) ORDER BY label COLLATE NOCASE", keys,
    )

    fun peopleNamesByPhoto(keys: Collection<String>): Map<String, List<String>> = valuesByPhoto(
        "SELECT DISTINCT s.photo_key, g.name FROM face_samples s JOIN face_groups g ON g.id = s.group_id AND g.hidden = 0 WHERE s.photo_key IN (${keys.joinToString { "?" }}) ORDER BY g.name COLLATE NOCASE", keys,
    )

    /** Every face of this person, newest first, for choosing which one they are shown by. */
    fun faceGroupFaces(groupId: Long): List<FaceOfPerson> = readableDatabase.rawQuery(
        "SELECT s.uuid, s.photo_key, s.left_edge, s.top_edge, s.right_edge, s.bottom_edge, s.quality, " +
            "CASE WHEN g.cover_uuid = s.uuid THEN 1 ELSE 0 END FROM face_samples s JOIN face_groups g ON g.id = s.group_id " +
            "WHERE s.group_id = ? AND s.uuid IS NOT NULL ORDER BY s.taken_at DESC, s.id DESC",
        arrayOf(groupId.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(FaceOfPerson(
                c.getString(0), c.getString(1), Rect(c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5)), c.getFloat(6), c.getInt(7) == 1,
            ))
        }
    }

    /** Choosing the face a person is shown by is a decision, so it is kept and it travels like one. */
    fun setFaceGroupCover(groupId: Long, faceUuid: String?) {
        writableDatabase.update("face_groups", ContentValues().apply {
            put("cover_uuid", faceUuid)
            put("updated_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(groupId.toString()))
    }

    /** Forget, or bring back. It is a decision about the person, so it travels like their name. */
    fun setFaceGroupHidden(groupId: Long, hidden: Boolean) {
        writableDatabase.update("face_groups", ContentValues().apply { put("hidden", if (hidden) 1 else 0); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(groupId.toString()))
    }

    fun renameFaceGroup(groupId: Long, name: String) {
        val cleaned = PhotoMetadataRules.collectionName(name)
        writableDatabase.update("face_groups", ContentValues().apply { put("name", cleaned); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(groupId.toString()))
    }

    /** Keeps the open group and moves every face from the selected duplicate into it. */
    fun mergeFaceGroups(sourceGroupId: Long, targetGroupId: Long): FaceMergeUndo {
        require(sourceGroupId != targetGroupId) { "Choose two different people groups." }
        return writableDatabase.inTransaction {
            val sourceName = rawQuery("SELECT name FROM face_groups WHERE id = ?", arrayOf(sourceGroupId.toString())).use { cursor ->
                require(cursor.moveToFirst()) { "The selected person no longer exists." }
                cursor.getString(0)
            }
            val sampleIds = rawQuery("SELECT id FROM face_samples WHERE group_id = ?", arrayOf(sourceGroupId.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
            val sourceUuid = rawQuery("SELECT uuid FROM face_groups WHERE id = ?", arrayOf(sourceGroupId.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
            // A name beats a guess whichever way round the two were combined (the user, 24 Oct: "Σταύρος Μ" put into
            // "Person 608" left 43 faces nameless): the kept person takes the combined one's name.
            val targetName = rawQuery("SELECT name FROM face_groups WHERE id = ?", arrayOf(targetGroupId.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
            val renamed = targetName != null && isGeneratedPersonName(targetName) && !isGeneratedPersonName(sourceName)
            if (renamed) update("face_groups", ContentValues().apply { put("name", sourceName); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(targetGroupId.toString()))
            update("face_reviews", ContentValues().apply { put("state", "resolved") }, "face_sample_id IN (SELECT id FROM face_samples WHERE group_id = ?)", arrayOf(sourceGroupId.toString()))
            update("face_reviews", ContentValues().apply { put("candidate_group_id", targetGroupId) }, "candidate_group_id = ?", arrayOf(sourceGroupId.toString()))
            update("face_samples", ContentValues().apply { put("group_id", targetGroupId); put("updated_at", System.currentTimeMillis()) }, "group_id = ?", arrayOf(sourceGroupId.toString()))
            delete("face_groups", "id = ?", arrayOf(sourceGroupId.toString()))
            // Kept, not just offered for eight seconds: combining is the one action here that quietly destroys a
            // grouping, and the person it was wrong about is unreachable afterwards unless we remember them.
            insertWithOnConflict("face_merges", null, ContentValues().apply {
                put("source_name", sourceName)
                put("source_uuid", sourceUuid)
                put("target_id", targetGroupId)
                put("sample_ids", sampleIds.joinToString(","))
                put("merged_at", System.currentTimeMillis())
                if (renamed) put("target_name", targetName)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            FaceMergeUndo(sourceName, sampleIds, sourceUuid, targetGroupId, targetName.takeIf { renamed })
        }
    }

    fun undoFaceMerge(undo: FaceMergeUndo) = writableDatabase.inTransaction {
        if (undo.sampleIds.isEmpty()) return@inTransaction
        // The same UUID comes back, so other devices see one continuous person rather than a new one.
        val restoredGroupId = insertOrThrow("face_groups", null, ContentValues().apply {
            put("name", undo.sourceName)
            put("uuid", undo.sourceUuid ?: UUID.randomUUID().toString())
            put("updated_at", System.currentTimeMillis())
        })
        update("face_samples", ContentValues().apply { put("group_id", restoredGroupId); put("updated_at", System.currentTimeMillis()) }, "id IN (${undo.sampleIds.joinToString { "?" }})", undo.sampleIds.map(Long::toString).toTypedArray())
        delete("face_merges", "sample_ids = ?", arrayOf(undo.sampleIds.joinToString(",")))
        if (undo.targetId != null && undo.targetName != null) update("face_groups", ContentValues().apply { put("name", undo.targetName); put("updated_at", System.currentTimeMillis()) },
            "id = ? AND name = ?", arrayOf(undo.targetId.toString(), undo.sourceName))
    }

    /**
     * Every group that was combined into this person, newest first, each still showing the head and the name it
     * had — usually a bare "Person 41" — so a combine that was wrong can be taken back long after the moment it
     * was made. The faces are still in the library; only which person they belong to changed.
     */
    /** What was combined into this person — or, with no person, everything combined anywhere. */
    fun mergeHistory(groupId: Long? = null): List<FaceMerge> = readableDatabase.rawQuery(
        "SELECT id, source_name, source_uuid, sample_ids, merged_at, target_id, target_name FROM face_merges" + (if (groupId != null) " WHERE target_id = ?" else "") + " ORDER BY merged_at DESC",
        groupId?.let { arrayOf(it.toString()) },
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val ids = cursor.getString(3).split(',').mapNotNull(String::toLongOrNull)
                if (ids.isEmpty()) continue
                val head = readableDatabase.rawQuery(
                    "SELECT photo_key, left_edge, top_edge, right_edge, bottom_edge FROM face_samples WHERE id IN (${ids.joinToString { "?" }}) ORDER BY quality DESC LIMIT 1",
                    ids.map(Long::toString).toTypedArray(),
                ).use { h -> if (h.moveToFirst()) FaceHead(h.getString(0), h.getInt(1), h.getInt(2), h.getInt(3), h.getInt(4)) else null }
                add(FaceMerge(cursor.getLong(0), cursor.getString(1), cursor.getString(2), ids, cursor.getLong(4), head, cursor.getLong(5), cursor.getString(6)))
            }
        }
    }

    fun restoreMerge(merge: FaceMerge) = undoFaceMerge(FaceMergeUndo(merge.sourceName, merge.sampleIds, merge.sourceUuid, merge.targetId, merge.targetName))

    /**
     * Take these photos' faces out of this person and give them a person of their own.
     *
     * The classifier's own joins are not combines and leave no history, so this is the only way back out of one.
     * The faces are not thrown away — they become a new "Person N" standing beside the others, which is exactly
     * what Combine puts back if the removal itself was wrong.
     */
    fun detachPhotosFromGroup(groupId: Long, photoKeys: Collection<String>): Boolean = writableDatabase.inTransaction {
        if (photoKeys.isEmpty()) return@inTransaction false
        val marks = photoKeys.joinToString { "?" }
        val args = (listOf(groupId.toString()) + photoKeys).toTypedArray()
        val sampleIds = rawQuery("SELECT id FROM face_samples WHERE group_id = ? AND photo_key IN ($marks)", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        if (sampleIds.isEmpty()) return@inTransaction false
        // Everything this group still holds must not be emptied: a person with no faces at all is not a person.
        val remaining = rawQuery("SELECT COUNT(*) FROM face_samples WHERE group_id = ? AND id NOT IN (${sampleIds.joinToString { "?" }})",
            (listOf(groupId.toString()) + sampleIds.map(Long::toString)).toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        if (remaining == 0) return@inTransaction false
        val ids = sampleIds.joinToString { "?" }
        val idArgs = sampleIds.map(Long::toString).toTypedArray()
        delete("face_reviews", "face_sample_id IN ($ids)", idArgs)
        update("face_samples", ContentValues().apply {
            put("group_id", createFaceGroup())
            put("updated_at", System.currentTimeMillis())
        }, "id IN ($ids)", idArgs)
        true
    }

    /**
     * Throws away the groups nobody has named, and the faces in them, so a rescan can group them again — with
     * whatever the thresholds are now. People you have named are left exactly as they are, together with their
     * faces: a rescan is for redoing the guessing, never for undoing a decision.
     *
     * Faces the computer sent come back on the next sync, since it still holds them.
     */
    fun forgetUnnamedFaces() = writableDatabase.inTransaction {
        execSQL("DELETE FROM face_reviews WHERE face_sample_id IN (SELECT s.id FROM face_samples s LEFT JOIN face_groups g ON g.id = s.group_id WHERE g.id IS NULL OR g.name GLOB 'Person [0-9]*')")
        execSQL("DELETE FROM face_samples WHERE id IN (SELECT s.id FROM face_samples s LEFT JOIN face_groups g ON g.id = s.group_id WHERE g.id IS NULL OR g.name GLOB 'Person [0-9]*')")
        execSQL(FORGET_EMPTY_GUESSES)
        execSQL("DELETE FROM face_reviews WHERE candidate_group_id NOT IN (SELECT id FROM face_groups)")
        execSQL("DELETE FROM face_merges WHERE target_id NOT IN (SELECT id FROM face_groups)")
    }

    /** Every photo already read by the model as it stands — one query, rather than one per photo in the gallery. */
    fun analyzedKeys(): Set<String> = keysFor(
        "SELECT photo_key FROM photo_ai_record WHERE model_version = ?", arrayOf(analysisModelVersion()),
    )

    fun recordClassification(photoKey: String, documentConfidence: Float, faces: List<DetectedFace>, labels: List<String>, modelVersion: String = analysisModelVersion(), takenMillis: Long = 0) = writableDatabase.inTransaction {
        val document = documentConfidence >= 0.70f
        val review = documentConfidence in 0.40f..<0.70f
        val verifiedDocument = rawQuery("SELECT user_verified, type FROM photo_ai_record WHERE photo_key = ?", arrayOf(photoKey)).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) true to cursor.getString(1) else false to null
        }
        insertWithOnConflict("photo_ai_record", null, ContentValues().apply {
            put("photo_key", photoKey)
            put("source_fingerprint", photoKey)
            put("type", if (verifiedDocument.first) verifiedDocument.second else if (document) "document" else null as String?)
            put("confidence", documentConfidence)
            put("model_version", modelVersion)
            put("user_verified", if (verifiedDocument.first) 1 else 0)
            put("review_state", if (!verifiedDocument.first && review) "pending" else "none")
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        delete("photo_ai_label", "photo_key = ?", arrayOf(photoKey))
        (if (faces.isNotEmpty()) labels + "Portrait" else labels).distinct().forEach { label ->
            insertOrThrow("photo_ai_label", null, ContentValues().apply {
                put("photo_key", photoKey)
                put("label", label)
            })
        }
        val candidates = faceCandidates()
        val day = dayOf(takenMillis)
        faces.forEach { face ->
            // The day is evidence, not proof: a face seen on the same day as someone already known starts a
            // little closer to them, which is what catches the same person across two photos of one moment.
            fun scoreOf(c: FaceCandidate) = cosineSimilarity(face.embedding, c.embedding) + if (c.day == day) SAME_DAY_BONUS else 0f
            val match = candidates.maxByOrNull(::scoreOf)
            val similarity = match?.let(::scoreOf) ?: -1f
            val reliable = isReliableFace(face.quality, face.yaw, face.roll)
            val groupId = when {
                similarity >= SAME_PERSON -> match!!.groupId
                !reliable -> null
                else -> createFaceGroup()
            }
            if (groupId == null) return@forEach
            if (overlappingFace(photoKey, face.bounds) != null) return@forEach // the computer already sent this face
            val sampleId = insertWithOnConflict("face_samples", null, ContentValues().apply {
                put("photo_key", photoKey)
                put("left_edge", face.bounds.left)
                put("top_edge", face.bounds.top)
                put("right_edge", face.bounds.right)
                put("bottom_edge", face.bounds.bottom)
                put("embedding", face.embedding.toBytes())
                put("group_id", groupId)
                put("quality", face.quality)
                put("uuid", UUID.randomUUID().toString())
                put("updated_at", System.currentTimeMillis())
                put("taken_at", takenMillis)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (sampleId != -1L && reliable && similarity in REVIEW_FROM..<SAME_PERSON) insertWithOnConflict("face_reviews", null, ContentValues().apply {
                put("photo_key", photoKey)
                put("face_sample_id", sampleId)
                put("candidate_group_id", match!!.groupId)
                put("state", "pending")
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** Every classified photo, for the sync push (SYNC_PLAN.md phase 6a). */
    fun documentRecords(): List<DocumentRecord> = readableDatabase.rawQuery(
        "SELECT photo_key, type, confidence, user_verified, updated_at FROM photo_ai_record WHERE type IS NOT NULL",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(DocumentRecord(c.getString(0), c.getString(1), c.getFloat(2), c.getInt(3) != 0, c.getLong(4))) } }

    /** From the sync pull: applied only if newer than what's stored locally (last-write-wins). */
    fun applyIncomingDocument(photoKey: String, type: String?, confidence: Float, userVerified: Boolean, updatedAt: Long) = writableDatabase.inTransaction {
        val localUpdatedAt = rawQuery("SELECT updated_at FROM photo_ai_record WHERE photo_key = ?", arrayOf(photoKey)).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        if (updatedAt <= localUpdatedAt) return@inTransaction
        insertWithOnConflict("photo_ai_record", null, ContentValues().apply {
            put("photo_key", photoKey)
            put("source_fingerprint", photoKey)
            put("type", type)
            put("confidence", confidence)
            put("user_verified", if (userVerified) 1 else 0)
            put("review_state", "none")
            put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // --- Phase 6b/6c sync: everything else the user made (favorites, collections, labels, people, faces).
    // Exports carry the local photo_key and collection/person UUIDs; SyncClient maps photo_key → SHA-256.

    /** Every favorite. Unfavoriting is a value change, not a row removal, so it travels too. */
    fun favoriteRecords(): List<FavoriteRecord> = readableDatabase.rawQuery(
        "SELECT photo_key, favorite, updated_at FROM photo_state", null,
    ).use { c -> buildList { while (c.moveToNext()) add(FavoriteRecord(c.getString(0), c.getInt(1) != 0, c.getLong(2))) } }

    fun collectionRecords(): List<CollectionRecord> = readableDatabase.rawQuery(
        "SELECT uuid, name, deleted, updated_at, hidden FROM collections WHERE uuid IS NOT NULL", null,
    ).use { c -> buildList { while (c.moveToNext()) add(CollectionRecord(c.getString(0), c.getString(1), c.getInt(2) != 0, c.getLong(3), c.getInt(4) != 0)) } }

    fun collectionItemRecords(): List<CollectionItemRecord> = readableDatabase.rawQuery(
        "SELECT c.uuid, m.photo_key, m.deleted, m.updated_at FROM collection_membership m JOIN collections c ON c.id = m.collection_id WHERE c.uuid IS NOT NULL",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(CollectionItemRecord(c.getString(0), c.getString(1), c.getInt(2) != 0, c.getLong(3))) } }

    /** Only groups that still hold a face: a merged-away person needs no tombstone, it simply has none left. */
    fun personRecords(): List<PersonRecord> = readableDatabase.rawQuery(
        "SELECT g.uuid, g.name, MAX(g.updated_at, IFNULL(MAX(s.updated_at), 0)), g.cover_uuid, g.hidden FROM face_groups g JOIN face_samples s ON s.group_id = g.id WHERE g.uuid IS NOT NULL GROUP BY g.id",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(PersonRecord(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4) != 0)) } }

    // ponytail: the whole set goes over on every sync — last-write-wins makes that safe and self-healing, and
    // 387 faces are ~260 KB of base64. Switch to an updated_at cursor if a library ever outgrows one request.
    fun faceRecords(): List<FaceRecord> = readableDatabase.rawQuery(
        "SELECT s.uuid, s.photo_key, s.left_edge, s.top_edge, s.right_edge, s.bottom_edge, s.embedding, s.quality, g.uuid, s.updated_at FROM face_samples s LEFT JOIN face_groups g ON g.id = s.group_id WHERE s.uuid IS NOT NULL",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(FaceRecord(
        c.getString(0), c.getString(1), android.graphics.Rect(c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5)),
        c.getBlob(6), c.getFloat(7), if (c.isNull(8)) null else c.getString(8), c.getLong(9),
    )) } }

    /** Labels are the search tags Photos shows. They are derived, never hand-deleted, so they only ever merge. */
    /** Answered questions, for the sync push. Pending ones are not sent: they are local uncertainty, not news. */
    fun reviewRecords(): List<ReviewRecord> = readableDatabase.rawQuery(
        "SELECT s.uuid, g.uuid, r.state, r.updated_at FROM face_reviews r " +
            "JOIN face_samples s ON s.id = r.face_sample_id JOIN face_groups g ON g.id = r.candidate_group_id " +
            "WHERE s.uuid IS NOT NULL AND g.uuid IS NOT NULL", null,
    ).use { c -> buildList { while (c.moveToNext()) add(ReviewRecord(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) } }

    /**
     * A question answered on another device stops being asked here. The pair (face, person) is the question's
     * name — both already cross — so nothing new had to be invented to identify one.
     *
     * Only the *state* travels. Where the face ended up is the face's own record, which arrives on its own and
     * carries the same rule as everything else: a guess never overwrites a decision.
     */
    fun applyIncomingReview(faceUuid: String, personUuid: String, state: String, updatedAt: Long) = writableDatabase.inTransaction {
        if (state !in setOf("pending", "accepted", "rejected", "resolved", "skipped")) return@inTransaction
        val sample = rawQuery("SELECT id, photo_key FROM face_samples WHERE uuid = ?", arrayOf(faceUuid))
            .use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null } ?: return@inTransaction
        val group = rawQuery("SELECT id FROM face_groups WHERE uuid = ?", arrayOf(personUuid))
            .use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@inTransaction
        val mine = rawQuery("SELECT state, updated_at FROM face_reviews WHERE face_sample_id = ? AND candidate_group_id = ?",
            arrayOf(sample.first.toString(), group.toString())).use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }
        if (mine != null && mine.first != "pending" && state == "pending") return@inTransaction
        if (mine != null && mine.first != "pending" && mine.second >= updatedAt) return@inTransaction
        if (mine != null && mine.first == "pending" && state == "pending" && mine.second >= updatedAt) return@inTransaction
        val whenApplied = if (mine?.first == "pending" && state != "pending") maxOf(updatedAt, mine.second + 1, System.currentTimeMillis()) else updatedAt
        if (state == "accepted") update("face_samples", ContentValues().apply {
            put("group_id", group); put("updated_at", whenApplied)
        }, "id = ?", arrayOf(sample.first.toString()))
        insertWithOnConflict("face_reviews", null, ContentValues().apply {
            put("photo_key", sample.second)
            put("face_sample_id", sample.first)
            put("candidate_group_id", group)
            put("state", state)
            put("updated_at", whenApplied)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allLabels(): Map<String, List<String>> = readableDatabase.rawQuery("SELECT photo_key, label FROM photo_ai_label", null)
        .use { c -> buildMap<String, MutableList<String>> { while (c.moveToNext()) getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1) } }

    fun applyIncomingFavorite(photoKey: String, favorite: Boolean, updatedAt: Long) = writableDatabase.inTransaction {
        if (updatedAt <= localUpdatedAt("photo_state", "photo_key", photoKey)) return@inTransaction
        insertWithOnConflict("photo_state", null, ContentValues().apply {
            put("photo_key", photoKey); put("favorite", if (favorite) 1 else 0); put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun applyIncomingCollection(uuid: String, name: String, deleted: Boolean, updatedAt: Long, hiddenFromGallery: Boolean = false) = writableDatabase.inTransaction {
        val local = rawQuery("SELECT id, updated_at FROM collections WHERE uuid = ?", arrayOf(uuid)).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
        if (local != null) {
            if (updatedAt <= local.second) return@inTransaction
            update("collections", ContentValues().apply {
                put("name", name); put("deleted", if (deleted) 1 else 0); put("hidden", if (hiddenFromGallery) 1 else 0); put("updated_at", updatedAt)
            }, "id = ?", arrayOf(local.first.toString()))
            return@inTransaction
        }
        if (deleted) return@inTransaction // nothing here to bury
        // The name column is UNIQUE: a collection of the same name made on both devices is the same collection.
        val sameName = rawQuery("SELECT id FROM collections WHERE name = ? COLLATE NOCASE", arrayOf(name)).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (sameName != null) update("collections", ContentValues().apply { put("uuid", uuid); put("deleted", 0); put("updated_at", updatedAt) }, "id = ?", arrayOf(sameName.toString()))
        else insertWithOnConflict("collections", null, ContentValues().apply {
            put("name", name); put("uuid", uuid); put("deleted", if (deleted) 1 else 0)
            put("hidden", if (hiddenFromGallery) 1 else 0); put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun applyIncomingCollectionItem(collectionUuid: String, photoKey: String, deleted: Boolean, updatedAt: Long) = writableDatabase.inTransaction {
        val collectionId = rawQuery("SELECT id FROM collections WHERE uuid = ?", arrayOf(collectionUuid)).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@inTransaction
        val local = rawQuery("SELECT updated_at FROM collection_membership WHERE collection_id = ? AND photo_key = ?", arrayOf(collectionId.toString(), photoKey)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (updatedAt <= local) return@inTransaction
        insertWithOnConflict("collection_membership", null, ContentValues().apply {
            put("collection_id", collectionId); put("photo_key", photoKey); put("deleted", if (deleted) 1 else 0); put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** People from the computer keep their UUID, including automatic groups, so face membership can converge. */
    fun applyIncomingPerson(uuid: String, name: String, updatedAt: Long, coverUuid: String? = null, hidden: Boolean = false) = writableDatabase.inTransaction {
        val local = rawQuery("SELECT id, updated_at FROM face_groups WHERE uuid = ?", arrayOf(uuid)).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
            ?: return@inTransaction run {
                // A group without a local face stays invisible until its faces arrive.
                insertWithOnConflict("face_groups", null, ContentValues().apply {
                    put("uuid", uuid)
                    put("name", name)
                    put("updated_at", updatedAt)
                    put("cover_uuid", coverUuid)
                    put("hidden", if (hidden) 1 else 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                Unit
            }
        if (updatedAt <= local.second) return@inTransaction
        update("face_groups", ContentValues().apply { put("hidden", if (hidden) 1 else 0) }, "id = ?", arrayOf(local.first.toString()))
        // The rule faces already had, and people did not: "Person 41" is what an algorithm called someone it had
        // not been told about, and it never replaces what a human typed, however recently it was written.
        if (isGeneratedPersonName(name) && !isGeneratedPersonName(personName(local.first))) return@inTransaction
        update("face_groups", ContentValues().apply {
            put("name", name)
            put("updated_at", updatedAt)
            // The face somebody chose to show this person by is a decision too, and travels with the name.
            if (coverUuid != null) put("cover_uuid", coverUuid)
        }, "id = ?", arrayOf(local.first.toString()))
    }

    /**
     * A face the phone already knows, moved to another person on the computer. Faces the computer found on its
     * own are not inserted: their box is in the desktop's coordinates and the phone re-detects them itself.
     */
    /**
     * A face from the computer. One it already knew is only re-pointed at a person; one this phone has never
     * seen is inserted whole, because the computer's detector finds faces ML Kit misses and dropping them would
     * mean that photo never appears under that person here (SYNC_PLAN.md 6m).
     *
     * `bounds` arrive as fractions of the upright photo and are given here in the pixels of the bitmap the
     * analyser works in, which is the only coordinate system the rest of this store knows. A face whose person
     * has not arrived yet is skipped, not guessed at: the next sync brings it.
     */
    fun applyIncomingFace(
        uuid: String,
        personUuid: String?,
        updatedAt: Long,
        photoKey: String? = null,
        bounds: Rect? = null,
        embedding: ByteArray? = null,
        quality: Float = 1f,
    ) = writableDatabase.inTransaction {
        val groupId = personUuid?.let { p -> rawQuery("SELECT id FROM face_groups WHERE uuid = ?", arrayOf(p)).use { if (it.moveToFirst()) it.getLong(0) else null } }
        val local = (if (photoKey != null && bounds != null) overlappingFace(photoKey, bounds)?.let { id ->
                rawQuery("SELECT id, updated_at FROM face_samples WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
            } else null) ?: rawQuery(
                "SELECT id, updated_at FROM face_samples WHERE uuid = ?" + if (photoKey == null) "" else " AND photo_key = ?",
                if (photoKey == null) arrayOf(uuid) else arrayOf(uuid, photoKey),
            )
                .use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
        if (local != null) {
            // The computer's grouping is the one every device adopts, and any question about it arrives as a
            // card after the faces. One exception: a guess ("Person 41") never replaces a name typed here —
            // the computer takes this phone's name on the push, so this only happens before it has.
            if (groupId != null && isGeneratedPersonName(personName(groupId)) &&
                groupName(local.first).let { it.isNotBlank() && !isGeneratedPersonName(it) }) return@inTransaction
            if (groupId != null) update("face_samples", ContentValues().apply {
                val uuidTaken = rawQuery("SELECT 1 FROM face_samples WHERE uuid = ? AND id != ?", arrayOf(uuid, local.first.toString()))
                    .use { it.moveToFirst() }
                if (!uuidTaken) put("uuid", uuid) // one canonical UUID; another physical copy keeps its own
                put("group_id", groupId); put("updated_at", updatedAt)
            }, "id = ?", arrayOf(local.first.toString()))
            return@inTransaction
        }
        if (photoKey == null || bounds == null || embedding == null || bounds.isEmpty) return@inTransaction
        if (overlappingFace(photoKey, bounds) != null) return@inTransaction // this phone found the same face itself

        // A face from a person this phone has never heard of. It used to be dropped, which threw away exactly
        // what the computer is better at: it detects at a larger size and finds faces this phone's detector
        // misses. But its *grouping* is not what we want either — the phone is where people are grouped and
        // named — so the face is taken and put through this phone's own rules, against this phone's own people.
        // A close enough match joins that person; anything else becomes a new one, and the uncertain band in
        // between becomes a question for Help organize, exactly as a face found here would.
        val vector = embedding.toFloatArray()
        // The same day counts here too, when this phone knows when the photo was taken — it does whenever it
        // has read that photo itself, which is the usual case for one the computer found an extra face in.
        val day = dayOf(takenAtOf(photoKey))
        fun scoreOf(c: FaceCandidate) = cosineSimilarity(vector, c.embedding) + if (c.day == day) SAME_DAY_BONUS else 0f
        val match = if (groupId != null) null else faceCandidates().maxByOrNull(::scoreOf)
        val similarity = match?.let(::scoreOf) ?: -1f
        val home = groupId ?: when {
            similarity >= SAME_PERSON -> match!!.groupId
            else -> createFaceGroup()
        }
        val sampleId = insertWithOnConflict("face_samples", null, ContentValues().apply {
            put("photo_key", photoKey)
            put("left_edge", bounds.left)
            put("top_edge", bounds.top)
            put("right_edge", bounds.right)
            put("bottom_edge", bounds.bottom)
            put("embedding", embedding)
            put("group_id", home)
            put("taken_at", takenAtOf(photoKey))
            put("quality", quality)
            put("uuid", uuid)
            put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (sampleId != -1L && groupId == null && match != null && similarity in REVIEW_FROM..<SAME_PERSON) {
            insertWithOnConflict("face_reviews", null, ContentValues().apply {
                put("photo_key", photoKey)
                put("face_sample_id", sampleId)
                put("candidate_group_id", match.groupId)
                put("state", "pending")
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /**
     * Raise the difference between two groupings as **one** card, not fifty. Two people who disagree about a
     * face usually disagree about every face of that person, and a question per face would bury the library in
     * questions that are all the same question. One pending card per pair of people shows the disagreement;
     * putting the two together, if that is the answer, is Combine on the People page.
     */
    private fun SQLiteDatabase.ask(sampleId: Long, photoKey: String?, mine: Long, theirs: Long, updatedAt: Long) {
        if (photoKey == null) return
        val already = rawQuery(
            "SELECT 1 FROM face_reviews r JOIN face_samples s ON s.id = r.face_sample_id " +
                "WHERE s.group_id = ? AND r.candidate_group_id = ? AND r.state = 'pending' LIMIT 1",
            arrayOf(mine.toString(), theirs.toString()),
        ).use { it.moveToFirst() }
        if (already) return
        insertWithOnConflict("face_reviews", null, ContentValues().apply {
            put("photo_key", photoKey)
            put("face_sample_id", sampleId)
            put("candidate_group_id", theirs)
            put("state", "pending")
            put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** When this photo was taken, as some other face on it already recorded. 0 when nothing here knows. */
    private fun SQLiteDatabase.takenAtOf(photoKey: String): Long = rawQuery(
        "SELECT MAX(taken_at) FROM face_samples WHERE photo_key = ?", arrayOf(photoKey),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    private fun SQLiteDatabase.groupOf(sampleId: Long): Long? = rawQuery(
        "SELECT group_id FROM face_samples WHERE id = ?", arrayOf(sampleId.toString()),
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private fun SQLiteDatabase.sampleKey(sampleId: Long): String? = rawQuery(
        "SELECT photo_key FROM face_samples WHERE id = ?", arrayOf(sampleId.toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun SQLiteDatabase.personName(groupId: Long): String =
        rawQuery("SELECT name FROM face_groups WHERE id = ?", arrayOf(groupId.toString())).use { if (it.moveToFirst()) it.getString(0) else "" }

    /** The name of the group a face sits in now, or "" when it sits in none. */
    private fun SQLiteDatabase.groupName(sampleId: Long): String = rawQuery(
        "SELECT g.name FROM face_samples s JOIN face_groups g ON g.id = s.group_id WHERE s.id = ?", arrayOf(sampleId.toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else "" }

    /** Two boxes on one photo that overlap this much are the same face, whichever detector found it first. */
    private fun SQLiteDatabase.overlappingFace(photoKey: String, bounds: Rect): Long? = rawQuery(
        "SELECT id, left_edge, top_edge, right_edge, bottom_edge FROM face_samples WHERE photo_key = ?", arrayOf(photoKey),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val other = Rect(cursor.getInt(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4))
            if (faceOverlap(bounds, other) >= SAME_FACE_OVERLAP) return cursor.getLong(0)
        }
        null
    }

    fun applyIncomingLabels(photoKey: String, labels: List<String>) = writableDatabase.inTransaction {
        labels.forEach { label -> insertWithOnConflict("photo_ai_label", null, ContentValues().apply { put("photo_key", photoKey); put("label", label) }, SQLiteDatabase.CONFLICT_IGNORE) }
    }

    private fun SQLiteDatabase.localUpdatedAt(table: String, keyColumn: String, key: String): Long =
        rawQuery("SELECT updated_at FROM $table WHERE $keyColumn = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else -1L }

    fun peopleAnalysisEnabled(): Boolean = galleryPreferences.getBoolean("people_analysis_enabled", false)

    fun searchQuality(): PhotoSearchQuality = if (galleryPreferences.getString("search_quality", PhotoSearchQuality.Fast.name) == PhotoSearchQuality.Advanced.name) PhotoSearchQuality.Advanced else PhotoSearchQuality.Fast

    fun setSearchQuality(quality: PhotoSearchQuality) { galleryPreferences.edit().putString("search_quality", quality.name).apply() }

    fun documentsAnalysisEnabled(): Boolean = galleryPreferences.getBoolean("documents_analysis_enabled", false)

    fun setPeopleAnalysisEnabled(enabled: Boolean) { galleryPreferences.edit().putBoolean("people_analysis_enabled", enabled).apply() }

    fun setDocumentsAnalysisEnabled(enabled: Boolean) { galleryPreferences.edit().putBoolean("documents_analysis_enabled", enabled).apply() }

    private fun analysisModelVersion(): String = "local-v7-${searchQuality().name.lowercase(Locale.ROOT)}"

    fun hidesScreenshotsFromGallery(): Boolean = galleryPreferences.getBoolean("hide_screenshots", false)

    /** My albums whose photos stay out of the Gallery view (by album UUID, so renames and sync keep it). */
    fun albumsHiddenFromGallery(): Set<String> = keysFor("SELECT uuid FROM collections WHERE hidden = 1 AND uuid IS NOT NULL", emptyArray())

    fun setAlbumHiddenFromGallery(uuid: String, hide: Boolean) {
        writableDatabase.update("collections", ContentValues().apply {
            put("hidden", if (hide) 1 else 0); put("updated_at", System.currentTimeMillis())
        }, "uuid = ?", arrayOf(uuid))
    }

    fun hidesDocumentsFromGallery(): Boolean = galleryPreferences.getBoolean("hide_documents", false)

    fun hidesPeopleFromCollections(): Boolean = galleryPreferences.getBoolean("hide_people_collection", false)

    fun hidesDocumentsFromCollections(): Boolean = galleryPreferences.getBoolean("hide_documents_collection", false)

    /** Motion photos (asked 2026-09-26): "one" shows a picture and its seconds of video as one; "remove" sends the video half to Android's Trash. */
    fun motionPhotos(): String = galleryPreferences.getString("motion_photos", "one") ?: "one"
    fun motionPlays(): Boolean = galleryPreferences.getBoolean("motion_autoplay", false)
    fun setMotionPlays(on: Boolean) { galleryPreferences.edit().putBoolean("motion_autoplay", on).apply() }
    fun setMotionPhotos(mode: String) { if (mode == "one" || mode == "remove") galleryPreferences.edit().putString("motion_photos", mode).apply() }

    fun setHidesScreenshotsFromGallery(hide: Boolean) { galleryPreferences.edit().putBoolean("hide_screenshots", hide).apply(); touchViewSettings() }

    fun setHidesDocumentsFromGallery(hide: Boolean) { galleryPreferences.edit().putBoolean("hide_documents", hide).apply(); touchViewSettings() }

    /**
     * The two "hide these from Photos" answers describe the library, not this phone, so they cross to the
     * computer. What a device should *do* — run face or document analysis, which model to use — stays local:
     * syncing that would start hours of work on a device that never asked for it.
     */
    fun viewSettingsUpdatedAt(): Long = galleryPreferences.getLong("view_settings_updated_at", 0)

    fun applyIncomingViewSettings(hideScreenshots: Boolean, hideDocuments: Boolean, updatedAt: Long) {
        if (updatedAt <= viewSettingsUpdatedAt()) return
        galleryPreferences.edit().putBoolean("hide_screenshots", hideScreenshots).putBoolean("hide_documents", hideDocuments)
            .putLong("view_settings_updated_at", updatedAt).apply()
    }

    private fun touchViewSettings() { galleryPreferences.edit().putLong("view_settings_updated_at", System.currentTimeMillis()).apply() }

    fun setHidesPeopleFromCollections(hide: Boolean) { galleryPreferences.edit().putBoolean("hide_people_collection", hide).apply() }

    fun setHidesDocumentsFromCollections(hide: Boolean) { galleryPreferences.edit().putBoolean("hide_documents_collection", hide).apply() }

    private fun keysFor(query: String, arguments: Array<String>): Set<String> = readableDatabase.rawQuery(query, arguments).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun valuesByPhoto(query: String, keys: Collection<String>): Map<String, List<String>> {
        if (keys.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(query, keys.toTypedArray()).use { cursor -> buildMap {
            while (cursor.moveToNext()) {
                val photoKey = cursor.getString(0)
                put(photoKey, (get(photoKey).orEmpty() + cursor.getString(1)).distinct())
            }
        } }
    }

    private fun createLocationTable(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE IF NOT EXISTS photo_location (photo_key TEXT PRIMARY KEY, latitude REAL, longitude REAL, place_name TEXT, place_resolved INTEGER NOT NULL DEFAULT 0)",
    )

    private fun setState(keys: Collection<String>, column: String, value: Boolean) = writableDatabase.inTransaction {
        keys.distinct().forEach { key ->
            val now = System.currentTimeMillis()
            insertWithOnConflict("photo_state", null, ContentValues().apply {
                put("photo_key", key)
                put(column, if (value) 1 else 0)
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            update("photo_state", ContentValues().apply { put(column, if (value) 1 else 0); put("updated_at", now) }, "photo_key = ?", arrayOf(key))
        }
    }

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try { block().also { setTransactionSuccessful() } } finally { endTransaction() }
    }

    private fun createAiTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS photo_ai_record (photo_key TEXT PRIMARY KEY, source_fingerprint TEXT NOT NULL, type TEXT, subtype TEXT, confidence REAL, model_version TEXT, policy_version INTEGER, user_verified INTEGER NOT NULL DEFAULT 0, review_state TEXT NOT NULL DEFAULT 'none', updated_at INTEGER NOT NULL DEFAULT 0)")
        createFaceTables(db)
    }

    private fun createFaceTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS face_samples (id INTEGER PRIMARY KEY, photo_key TEXT NOT NULL, left_edge INTEGER NOT NULL, top_edge INTEGER NOT NULL, right_edge INTEGER NOT NULL, bottom_edge INTEGER NOT NULL, embedding BLOB NOT NULL, group_id INTEGER, quality REAL NOT NULL DEFAULT 1.0, updated_at INTEGER NOT NULL DEFAULT 0, taken_at INTEGER NOT NULL DEFAULT 0, UNIQUE(photo_key, left_edge, top_edge, right_edge, bottom_edge))")
    }

    private fun createFaceGroupingTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS face_groups (id INTEGER PRIMARY KEY, name TEXT NOT NULL, updated_at INTEGER NOT NULL DEFAULT 0, cover_uuid TEXT, hidden INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS face_reviews (id INTEGER PRIMARY KEY, photo_key TEXT NOT NULL, face_sample_id INTEGER NOT NULL, candidate_group_id INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', updated_at INTEGER NOT NULL DEFAULT 0, UNIQUE(face_sample_id, candidate_group_id))")
    }

    private fun createLabelTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS photo_ai_label (photo_key TEXT NOT NULL, label TEXT NOT NULL COLLATE NOCASE, PRIMARY KEY(photo_key, label))")
    }

    private fun SQLiteDatabase.createFaceGroup(): Long = insertOrThrow("face_groups", null, ContentValues().apply {
        put("updated_at", System.currentTimeMillis())
        put("uuid", UUID.randomUUID().toString())
        put("name", "Person ${rawQuery("SELECT COUNT(*) FROM face_groups", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) + 1 }}")
    })

    private fun SQLiteDatabase.faceCandidates(): List<FaceCandidate> = rawQuery(
        "SELECT group_id, embedding, taken_at FROM face_samples WHERE group_id IS NOT NULL AND quality >= 0.68", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(FaceCandidate(cursor.getLong(0), cursor.getBlob(1).toFloatArray(), dayOf(cursor.getLong(2))))
        }
    }

    private data class FaceCandidate(val groupId: Long, val embedding: FloatArray, val day: Long)
}

internal object PhotoSearchRules {
    private val aliases = mapOf(
        "θαλασσα" to setOf("sea", "ocean", "beach", "water", "coast", "shore"),
        "βουνο" to setOf("mountain", "hill", "cliff", "peak"),
        "νυχτα" to setOf("night", "darkness"),
        "μερα" to setOf("day", "daytime", "sunlight"),
        "προσωπο" to setOf("portrait", "person", "face"),
        "ζωο" to setOf("animal", "dog", "cat", "bird", "horse", "fish"),
    )

    fun matches(query: String, terms: Collection<String>): Boolean {
        val needle = normalized(query)
        if (needle.isBlank()) return true
        val candidates = aliases[needle].orEmpty() + needle
        return terms.any { term -> candidates.any { normalized(term).contains(it) } }
    }

    fun frequentTags(labels: Collection<String>, limit: Int = 5): List<String> = labels
        .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit).map { it.key }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "").lowercase(Locale.ROOT)
}

/** A face card worth showing: still pending, and not asking whether a face belongs where it already is. */
private const val LIVE_REVIEW = "r.state = 'pending' AND s.group_id IS NOT r.candidate_group_id AND g.hidden = 0 AND " +
    "NOT EXISTS (SELECT 1 FROM face_groups h WHERE h.id = s.group_id AND h.hidden = 1)" // nothing about someone forgotten

internal fun isGeneratedPersonName(name: String): Boolean = name.matches(Regex("Person \\d+"))

private fun ByteArray.toFloatArray(): FloatArray = ByteBuffer.wrap(this).order(ByteOrder.nativeOrder()).let { buffer ->
    FloatArray(size / 4) { buffer.float }
}

private fun FloatArray.toBytes(): ByteArray = ByteBuffer.allocate(size * 4).order(ByteOrder.nativeOrder()).apply {
    forEach(::putFloat)
}.array()

internal object PhotoMetadataRules {
    fun stableKey(uri: String, relativePath: String?, name: String, sizeBytes: Long): String {
        val source = listOf(uri, relativePath.orEmpty(), name, sizeBytes.toString()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun collectionName(rawName: String): String = rawName.trim().also {
        need(it.isNotEmpty(), R.string.photos_error_name_empty) { "Collection name cannot be empty." }
        need(it.length <= 60, R.string.photos_error_name_long) { "Collection names can be at most 60 characters." }
        need(it.none(Char::isISOControl), R.string.photos_error_name_chars) { "Collection name contains unsupported characters." }
    }

    fun visibleInGallery(isScreenshot: Boolean, isDocument: Boolean, hideScreenshots: Boolean, hideDocuments: Boolean, inHiddenAlbum: Boolean = false): Boolean =
        !(hideScreenshots && isScreenshot) && !(hideDocuments && isDocument) && !inHiddenAlbum
}
