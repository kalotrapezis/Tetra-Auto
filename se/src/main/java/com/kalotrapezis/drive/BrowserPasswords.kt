package com.kalotrapezis.drive

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The Browser's saved passwords between devices, encrypted end to end (the user, 3 Nov: "Could we sync passwords as well but
 * have some encryption?"). Off until chosen in Settings › Browser › Sync; nothing of the vault leaves a device before.
 *
 *   Browser/Passwords/key.json      {"v":1,"kdf":"argon2id","m":47104,"t":1,"p":1,"salt":…,"made":…,"check":…}: the key's
 *                                   recipe (Argon2id of the sync passphrase with that salt; no secret in it) and a box
 *                                   only that key opens, so a wrong passphrase is refused. Made by the first device.
 *   Browser/Passwords/<device>.enc  {"v":1,"kdf":…,"salt":…,"nonce":…,"data":…}: every login, address and card that device
 *                                   knows, with change times and tombstones (the bookmarks' rules), AES-256-GCM with a fresh
 *                                   nonce each write, the device's name bound in. Only that device writes it.
 *
 * The derived key is kept on each device under the Android Keystore (BrowserSecrets), so the passphrase is asked once a
 * device. "Forgot it": a new key.json, made later, wins; every device then asks for the new passphrase, the vaults stay.
 * Nothing plain is written outside the app's keystore-encrypted storage, and no password, passphrase or key is logged.
 */

/** One saved thing of the vault as sync knows it: [value] its fields in a fixed order (a JSON array), or a tombstone. */
internal data class SecretRec(val value: String, val at: Long, val gone: Boolean = false)

internal data class SecretSet(val items: Map<String, SecretRec> = emptyMap()) {
    fun merge(o: SecretSet) = SecretSet((items.keys + o.items.keys).associateWith { k ->
        listOfNotNull(items[k], o.items[k]).maxWith(compareBy<SecretRec>({ it.at }, { !it.gone }, { it.value }))
    })

    fun pruned(now: Long, days: Int = 30) = (now - days * 86_400_000L).let { old -> SecretSet(items.filterValues { !it.gone || it.at >= old }) }

    /** What [v] changed since this set, stamped [now]: a new or changed one recorded whole, one no longer there a tombstone. */
    fun record(v: BrowserVault, now: Long) = record(keyed(v), now)

    /** The same for any keyed values ([keyed]'s shape; 2FA's are Authenticator.kt's). */
    fun record(here: Map<String, String>, now: Long): SecretSet {
        val out = items.toMutableMap()
        here.forEach { (k, value) -> val r = items[k]; if (r == null || r.gone || r.value != value) out[k] = SecretRec(value, now) }
        items.forEach { (k, r) -> if (!r.gone && k !in here) out[k] = r.copy(gone = true, at = now) }
        return SecretSet(out)
    }

    /** The vault to keep: the living ones, [prev]'s order kept, new ones after. */
    fun vault(prev: BrowserVault = BrowserVault()): BrowserVault {
        val live = live(keyed(prev).keys)
        val keys = live.keys
        fun a(k: String) = JSONArray(live.getValue(k))
        return BrowserVault(
            keys.filter { it.startsWith("l|") }.map { a(it).let { j -> SavedLogin(j.getString(0), j.getString(1), j.getString(2), j.optString(3), j.optString(4) == "1") } },
            keys.filter { it.startsWith("a|") }.map { a(it).let { j -> (0 until j.length() / 2).associate { i -> j.getString(2 * i) to j.getString(2 * i + 1) } } },
            keys.filter { it.startsWith("c|") }.map { a(it).let { j -> SavedCard(j.getString(0), j.getString(1), j.getString(2), j.getString(3), j.optString(4)) } })
    }

    /** The living ones' values by key, [prev]'s order kept, new ones after (oldest first). */
    fun live(prev: Collection<String>): Map<String, String> {
        val live = items.filterValues { !it.gone }
        val order = prev.withIndex().associate { it.value to it.index }
        return live.keys.sortedWith(compareBy({ order[it] ?: Int.MAX_VALUE }, { live.getValue(it).at }, { it })).associateWith { live.getValue(it).value }
    }

    fun json(): String = JSONObject(items.mapValues { (_, r) -> JSONObject().put("v", r.value).put("at", r.at).apply { if (r.gone) put("gone", true) } }).toString()

    companion object {
        /**
         * Each thing by what it is: a login by its site and user (its password the value), a card by its number, an address
         * by all it holds (an address changed is the old one gone and a new one). The bookmarks' merge then covers all three.
         */
        fun keyed(v: BrowserVault): Map<String, String> =
            v.logins.associate { "l|${it.host}|${it.user}" to JSONArray(listOfNotNull(it.host, it.user, it.password, it.note.ifEmpty { if (it.pinned) "" else null }, if (it.pinned) "1" else null)).toString() } + // no note, no ♥: as before them
            v.cards.associate { "c|${it.number}" to JSONArray(listOf(it.name, it.number, it.month, it.year) + listOfNotNull(it.cvc.ifEmpty { null })).toString() } +
            v.addresses.associate { m -> JSONArray(m.toSortedMap().flatMap { listOf(it.key, it.value) }).toString().let { "a|$it" to it } }

        fun parse(s: String): SecretSet? = runCatching {
            val o = JSONObject(s)
            SecretSet(o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { SecretRec(it.getString("v"), it.getLong("at"), it.optBoolean("gone")) } })
        }.getOrNull()
    }
}

internal object BrowserPasswords {
    const val DIR = "Passwords"
    private const val CHECK = "tetra-browser-passwords"
    private val b64 = Base64.getEncoder(); private val unb64 = Base64.getDecoder()
    private val random = SecureRandom()

    /** A key's recipe from key.json: Argon2id's settings and salt, when it was made, its check box. */
    data class Recipe(val salt: ByteArray, val m: Int, val t: Int, val p: Int, val made: Long, val check: String) {
        fun json(): String = JSONObject().put("v", 1).put("kdf", "argon2id").put("m", m).put("t", t).put("p", p).put("salt", b64.encodeToString(salt))
            .put("made", made).put("check", check).toString(1) + "\n"
        val id get() = b64.encodeToString(salt)
    }

    class WrongPassphrase : Exception("Wrong passphrase")

    fun derive(passphrase: CharArray, r: Recipe): ByteArray {
        val g = Argon2BytesGenerator()
        g.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(r.salt).withMemoryAsKB(r.m).withIterations(r.t).withParallelism(r.p).build())
        return ByteArray(32).also { g.generateBytes(passphrase, it) }
    }

    private fun seal(key: ByteArray, plain: ByteArray, aad: String): Pair<String, String> {
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad.toByteArray()) }
        return b64.encodeToString(nonce) to b64.encodeToString(c.doFinal(plain))
    }

    /** The plain bytes, or AEADBadTagException: a wrong key, or bytes changed on the way. */
    private fun open(key: ByteArray, nonce: String, data: String, aad: String): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64.decode(nonce))); updateAAD(aad.toByteArray()) }
            .doFinal(unb64.decode(data))

    /** A new recipe and its key (the first device, or "forgot it"): OWASP's Argon2id setting, 46 MiB once through. */
    fun create(passphrase: CharArray, now: Long = System.currentTimeMillis(), m: Int = 47104, t: Int = 1): Pair<Recipe, ByteArray> {
        val r0 = Recipe(ByteArray(16).also(random::nextBytes), m, t, 1, now, "")
        val key = derive(passphrase, r0)
        val (n, d) = seal(key, CHECK.toByteArray(), "check")
        return r0.copy(check = "$n.$d") to key
    }

    /** The key [passphrase] makes with [r], or [WrongPassphrase] when the check box does not open with it. */
    fun unlock(passphrase: CharArray, r: Recipe): ByteArray {
        val key = derive(passphrase, r)
        val ok = runCatching { open(key, r.check.substringBefore('.'), r.check.substringAfter('.'), "check").decodeToString() == CHECK }.getOrDefault(false)
        if (!ok) throw WrongPassphrase()
        return key
    }

    fun parseRecipe(s: String): Recipe? = runCatching {
        val o = JSONObject(s)
        if (o.optInt("v") != 1 || o.optString("kdf") != "argon2id") return null
        Recipe(unb64.decode(o.getString("salt")), o.getInt("m"), o.getInt("t"), o.getInt("p"), o.getLong("made"), o.getString("check"))
    }.getOrNull()

    /** The recipe in force: of key.json and any copy sync left beside it ("key (2).json"), the one made last. */
    fun recipe(root: File): Recipe? = File(root, DIR).listFiles().orEmpty().filter { it.isFile && it.name.startsWith("key") && it.name.endsWith(".json") }
        .mapNotNull { parseRecipe(it.readText()) }.maxWithOrNull(compareBy({ it.made }, { it.id }))

    fun writeRecipe(root: File, r: Recipe) = BrowserSync.save(File(root, "$DIR/key.json"), r.json())

    // [kind] is bound into each file ("passwords", "2fa"), so one moved into the other's folder does not open
    fun encrypt(key: ByteArray, r: Recipe, me: String, set: SecretSet, kind: String = "passwords"): String {
        val (n, d) = seal(key, set.json().toByteArray(), "$kind|$me")
        return JSONObject().put("v", 1).put("kdf", "argon2id").put("m", r.m).put("t", r.t).put("p", r.p).put("salt", r.id).put("nonce", n).put("data", d).toString(1) + "\n"
    }

    /** A device's file opened with [key]; null when it is another key's (another salt) or does not open (changed, or not ours). */
    fun decrypt(key: ByteArray, r: Recipe, device: String, text: String, kind: String = "passwords"): SecretSet? = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v") != 1 || o.getString("salt") != r.id) return null
        SecretSet.parse(open(key, o.getString("nonce"), o.getString("data"), "$kind|$device").decodeToString())
    }.getOrNull()

    class Result(val vault: BrowserVault, val base: SecretSet, val unreadable: Int)

    /**
     * The vault of every device with [vault], this device's own: what changed here since [base] (the set it was last shown
     * from) recorded, the others' files merged in, this device's file written again when it differs. [key] and [r] must be
     * the recipe in force ([recipe]); a file of another key is left (that device has not entered the new passphrase yet).
     */
    fun sync(root: File, me: String, vault: BrowserVault, base: SecretSet?, key: ByteArray, r: Recipe, now: Long = System.currentTimeMillis()): Result {
        val (own, recorded, all, others, unreadable) = merged(File(root, DIR), "passwords", me, SecretSet.keyed(vault), base, key, r, now)
        val out = all.vault(vault)
        val here = recorded.items.count { (k, v) -> own.items[k] != v }
        val there = all.items.count { (k, v) -> recorded.items[k] != v }
        if (here > 0 || there > 0 || unreadable > 0 || base == null)
            WriterLog.i("Browser sync: passwords ${if (base == null) "(first sync) " else ""}${out.logins.size} logins, ${out.addresses.size} addresses, ${out.cards.size} cards; " +
                "$others other devices; $here changed here, $there from the others (${all.items.count { (k, v) -> v.gone && recorded.items[k]?.gone == false }} deleted)" +
                if (unreadable > 0) "; $unreadable files of another passphrase left" else "")
        return Result(out, all, unreadable)
    }

    data class Merged(val own: SecretSet, val recorded: SecretSet, val all: SecretSet, val others: Int, val unreadable: Int)

    /** [sync]'s merge for any keyed values in [dir]: [here]'s changes since [base] recorded, the others' files merged in, mine written when it differs. */
    fun merged(dir: File, kind: String, me: String, here: Map<String, String>, base: SecretSet?, key: ByteArray, r: Recipe, now: Long): Merged {
        val mine = File(dir, "$me.enc")
        val shared = mine.takeIf { it.isFile }?.let { decrypt(key, r, me, it.readText(), kind) }
        val own = base ?: shared ?: SecretSet()
        val recorded = own.record(here, now)
        var unreadable = 0
        val others = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".enc") && !it.name.startsWith(".") && it.name != mine.name }
            .mapNotNull { f -> decrypt(key, r, f.name.removeSuffix(".enc"), f.readText(), kind).also { if (it == null) unreadable++ } }
        val all = others.fold(recorded) { a, b -> a.merge(b) }.pruned(now)
        if (all != shared) BrowserSync.save(mine, encrypt(key, r, me, all, kind))
        return Merged(own, recorded, all, others.size, unreadable)
    }
}

/** This device's side of password sync: the switch, the key under the Keystore (BrowserSecrets), the merge base likewise. */
internal object PasswordSync {
    enum class State { OFF, ON, NEED } // NEED: on, but another passphrase is in force now ("forgot it" elsewhere): asked again

    fun stored(c: android.content.Context): Pair<BrowserPasswords.Recipe, ByteArray>? = BrowserSecrets.get(c, "sync_key")?.let { s -> runCatching {
        val o = JSONObject(s)
        BrowserPasswords.parseRecipe(o.getString("recipe"))!! to Base64.getDecoder().decode(o.getString("key"))
    }.getOrNull() }

    fun state(c: android.content.Context, root: File = BrowserSync.root()): State {
        if (!BrowserSync.on(c, BrowserSync.PASSWORDS)) return State.OFF
        val mine = stored(c) ?: return State.NEED
        val now = BrowserPasswords.recipe(root)
        return if (now == null || now.id == mine.first.id) State.ON else State.NEED
    }

    /** The recipe in force, for the passphrase dialog: null when no device has chosen one yet. */
    fun recipe(root: File = BrowserSync.root()) = BrowserPasswords.recipe(root)

    /** Switched on with [key] (made or unlocked by the dialog): kept, the recipe written when it is new, a first merge now. */
    fun enable(c: android.content.Context, r: BrowserPasswords.Recipe, key: ByteArray, isNew: Boolean, root: File = BrowserSync.root()) {
        if (isNew) BrowserPasswords.writeRecipe(root, r)
        BrowserSecrets.set(c, "sync_key", JSONObject().put("recipe", r.json()).put("key", Base64.getEncoder().encodeToString(key)).toString())
        c.getSharedPreferences("browser", android.content.Context.MODE_PRIVATE).edit().putBoolean(BrowserSync.PASSWORDS, true).apply()
        WriterLog.i("Browser sync: passwords on (${if (isNew) "a new passphrase" else "the passphrase in force"})")
        run(c, BrowserSecrets.vault(c))?.let { BrowserSecrets.save(c, it) }
    }

    /** Off: the vault here stays; the key and the base are forgotten, so on again asks for the passphrase. */
    fun disable(c: android.content.Context) {
        c.getSharedPreferences("browser", android.content.Context.MODE_PRIVATE).edit().putBoolean(BrowserSync.PASSWORDS, false).apply()
        BrowserSecrets.set(c, "sync_key", null); BrowserSecrets.set(c, "sync_base", null)
        WriterLog.i("Browser sync: passwords off; the vault stays on this device")
    }

    /** [vault] merged with the other devices' when on and unlocked: the vault to keep, else null (nothing read or written). */
    fun run(c: android.content.Context, vault: BrowserVault, root: File = BrowserSync.root()): BrowserVault? {
        if (!BrowserSync.on(c, BrowserSync.PASSWORDS)) return null
        val (mine, key) = stored(c) ?: return null
        val now = BrowserPasswords.recipe(root)
        if (now == null) BrowserPasswords.writeRecipe(root, mine) // ours, not there (yet): written again
        else if (now.id != mine.id) { WriterLog.w("Browser sync: passwords wait for the new passphrase"); return null }
        val base = BrowserSecrets.get(c, "sync_base")?.let(SecretSet::parse)
        val r = BrowserPasswords.sync(root, BrowserSync.device(c), vault, base, key, mine)
        if (r.base != base) BrowserSecrets.set(c, "sync_base", r.base.json())
        return r.vault
    }
}
