package com.kalotrapezis.drive

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as K

/**
 * Tetra's contacts seen by every app (Plan.md § Contacts, step 6; the user said yes, 23 Oct): Tetra's own account
 * ("Tetra") holds a copy of Tetra/.System/Contacts/Shared in the phone's contacts, written one way from the files — the dialer's caller ID,
 * Viber's and WhatsApp's names, Android Auto's calls know them; Android joins each with the same person from Google. The
 * copy is read-only elsewhere; edits are made in Tetra. Turned off, the account and its copy go.
 */
internal object ContactsAccount {
    const val TYPE = "com.kalotrapezis.drive.contacts"
    private val account = Account("Tetra", TYPE)

    fun on(c: Context) = c.getSharedPreferences("contacts", Context.MODE_PRIVATE).getBoolean("system", true)
    fun set(c: Context, on: Boolean) {
        c.getSharedPreferences("contacts", Context.MODE_PRIVATE).edit().putBoolean("system", on).apply()
        if (on) soon(c) else Thread { runCatching { AccountManager.get(c).removeAccountExplicitly(account) } }.start()
    }

    private fun allowed(c: Context) = c.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The copy brought up to date on a thread of its own (after a save, an import, a merge, opening Contacts). */
    fun soon(c: Context) {
        val app = c.applicationContext
        if (!on(app) || !allowed(app)) return
        Thread { runCatching { write(app) }.onFailure { WriterLog.e("Contacts: the phone's copy", it) } }.start()
    }

    /**
     * The account's raw contacts made to match the files: one per contact (SOURCE_ID = its UID, SYNC1 = its REV); a changed
     * one is written again, one gone from the files is removed. Answers how many were written or removed.
     */
    @Synchronized fun write(c: Context): Int {
        AccountManager.get(c).addAccountExplicitly(account, null, null)
        android.content.ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        val cr = c.contentResolver
        val raw = ContactsContract.RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name).appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type).build()
        val have = HashMap<String, Pair<Long, String>>()
        cr.query(raw, arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1),
            "${ContactsContract.RawContacts.DELETED} = 0", null, null)?.use { q -> while (q.moveToNext()) have[q.getString(1) ?: ""] = q.getLong(0) to (q.getString(2) ?: "") }
        val files = ContactsFiles.all(contactsRoot()).map { it.info }.filter { it.uid.isNotEmpty() }
        val ops = ArrayList<ContentProviderOperation>()
        var n = 0
        fun flush() { if (ops.isNotEmpty()) { cr.applyBatch(ContactsContract.AUTHORITY, ops); ops.clear() } }
        for ((uid, idRev) in have) if (files.none { it.uid == uid }) {
            ops += ContentProviderOperation.newDelete(android.content.ContentUris.withAppendedId(raw, idRev.first)).build(); n++
        }
        for (i in files) {
            val was = have[i.uid]
            if (was != null && was.second == i.rev) continue
            if (was != null) ops += ContentProviderOperation.newDelete(android.content.ContentUris.withAppendedId(raw, was.first)).build()
            val at = ops.size
            ops += ContentProviderOperation.newInsert(raw).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type).withValue(ContactsContract.RawContacts.SOURCE_ID, i.uid)
                .withValue(ContactsContract.RawContacts.SYNC1, i.rev).build()
            rows(i).forEach { v -> ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build())
                .withValues(v).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, at).build() }
            n++
            if (ops.size > 300) flush() // shortcut: a batch per ~300 rows, under Binder's 1 MB with 512 px photos; smaller if photos grow
        }
        flush()
        if (n > 0) WriterLog.i("Contacts: the phone's copy, $n written or removed")
        return n
    }

    /** A contact's rows in Android's form. */
    private fun rows(i: ContactInfo): List<ContentValues> {
        fun row(mime: String, vararg kv: Pair<String, Any?>) = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, mime)
            kv.forEach { (k, v) -> when (v) { null -> putNull(k); is Int -> put(k, v); is ByteArray -> put(k, v); else -> put(k, v.toString()) } }
        }
        fun phoneType(l: String) = when (l) { "Mobile" -> K.Phone.TYPE_MOBILE; "Home" -> K.Phone.TYPE_HOME; "Work" -> K.Phone.TYPE_WORK; "Main" -> K.Phone.TYPE_MAIN
            "Home fax" -> K.Phone.TYPE_FAX_HOME; "Work fax" -> K.Phone.TYPE_FAX_WORK; "Pager" -> K.Phone.TYPE_PAGER; "Other", "" -> K.Phone.TYPE_OTHER; else -> K.Phone.TYPE_CUSTOM }
        fun placeType(l: String) = when (l) { "Home" -> 1; "Work" -> 2; "Other", "" -> 3; else -> 0 } // the same numbers for e-mail and address
        val out = mutableListOf(row(K.StructuredName.CONTENT_ITEM_TYPE, K.StructuredName.DISPLAY_NAME to i.name, K.StructuredName.GIVEN_NAME to i.given.ifEmpty { null },
            K.StructuredName.FAMILY_NAME to i.family.ifEmpty { null }, K.StructuredName.MIDDLE_NAME to i.middle.ifEmpty { null },
            K.StructuredName.PREFIX to i.prefix.ifEmpty { null }, K.StructuredName.SUFFIX to i.suffix.ifEmpty { null }))
        i.phones.forEach { f -> out += row(K.Phone.CONTENT_ITEM_TYPE, K.Phone.NUMBER to f.value, K.Phone.TYPE to phoneType(f.label), K.Phone.LABEL to f.label.takeIf { phoneType(it) == 0 }) }
        i.emails.forEach { f -> out += row(K.Email.CONTENT_ITEM_TYPE, K.Email.ADDRESS to f.value, K.Email.TYPE to placeType(f.label), K.Email.LABEL to f.label.takeIf { placeType(it) == 0 }) }
        i.addresses.forEach { a -> out += row(K.StructuredPostal.CONTENT_ITEM_TYPE, K.StructuredPostal.FORMATTED_ADDRESS to a.text(), K.StructuredPostal.STREET to a.street.ifEmpty { null },
            K.StructuredPostal.CITY to a.city.ifEmpty { null }, K.StructuredPostal.POSTCODE to a.postcode.ifEmpty { null }, K.StructuredPostal.COUNTRY to a.country.ifEmpty { null },
            K.StructuredPostal.TYPE to placeType(a.label), K.StructuredPostal.LABEL to a.label.takeIf { placeType(it) == 0 }) }
        if (i.org.isNotEmpty() || i.title.isNotEmpty()) out += row(K.Organization.CONTENT_ITEM_TYPE, K.Organization.COMPANY to i.org.ifEmpty { null },
            K.Organization.DEPARTMENT to i.department.ifEmpty { null }, K.Organization.TITLE to i.title.ifEmpty { null })
        if (i.nickname.isNotEmpty()) out += row(K.Nickname.CONTENT_ITEM_TYPE, K.Nickname.NAME to i.nickname)
        if (i.note.isNotEmpty()) out += row(K.Note.CONTENT_ITEM_TYPE, K.Note.NOTE to i.note)
        i.birthday?.let { out += row(K.Event.CONTENT_ITEM_TYPE, K.Event.START_DATE to it.value(), K.Event.TYPE to K.Event.TYPE_BIRTHDAY) }
        i.urls.forEach { f -> out += row(K.Website.CONTENT_ITEM_TYPE, K.Website.URL to f.value) }
        i.photo?.let { p -> runCatching { java.util.Base64.getDecoder().decode(p) }.getOrNull()?.let { out += row(K.Photo.CONTENT_ITEM_TYPE, K.Photo.PHOTO to it) } }
        return out
    }
}

/** The account's authenticator: Tetra's account has no password or sign-in; it only holds the copy. */
class ContactsAuthService : Service() {
    private val auth by lazy {
        object : AbstractAccountAuthenticator(this) {
            override fun editProperties(r: AccountAuthenticatorResponse?, t: String?) = null
            override fun addAccount(r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?) = null
            override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?) = null
            override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?) = null
            override fun getAuthTokenLabel(t: String?) = null
            override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?) = null
            override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?) = null
        }
    }
    override fun onBind(intent: Intent?): IBinder = auth.iBinder
}

/** The account's sync adapter (Android asks one of a contacts account): it writes the copy from the files, as Tetra does. */
class ContactsSyncService : Service() {
    private val adapter by lazy {
        object : AbstractThreadedSyncAdapter(applicationContext, true) {
            override fun onPerformSync(a: Account?, e: Bundle?, authority: String?, p: ContentProviderClient?, r: SyncResult?) {
                runCatching { if (ContactsAccount.on(context)) ContactsAccount.write(context) }.onFailure { WriterLog.e("Contacts: the phone's copy (sync)", it) }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}
