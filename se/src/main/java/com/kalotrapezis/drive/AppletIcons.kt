package com.kalotrapezis.drive

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Tetra's parts with launcher icons of their own (asked 17 Oct: Settings › App icons — on the home screen and in the
 * app list, each opening straight into its part). Each is an activity-alias, off until switched on: of its own window (AppletActivities.kt),
 * or of [AppletLaunch] for the parts still in Tetra's (Photos, Files, Scanner, Office).
 */
internal enum class Applet(@androidx.annotation.StringRes val label: Int, val art: Int) {
    // Home's art for each; the launcher icons are made from the same (res/mipmap-anydpi-v26/applet_*)
    Photos(R.string.app_photos, R.drawable.gallery_art),
    Files(R.string.app_files, R.drawable.files_art),
    Notes(R.string.app_notes, R.drawable.notes_art),
    Scanner(R.string.app_scanner, R.drawable.scanner_art),
    Codes(R.string.app_codes, R.drawable.codes_art),
    Office(R.string.app_office, R.drawable.office_art),
    TextEditor(R.string.app_text_editor, R.drawable.text_editor_art),
    Calendar(R.string.app_calendar, R.drawable.calendar_art),
    Timer(R.string.app_timer, R.drawable.timer_art),
    Maps(R.string.app_maps, R.drawable.maps_art),
    Contacts(R.string.app_contacts, R.drawable.contacts_art),
    Phone(R.string.app_phone, R.drawable.phone_art),
    Data(R.string.app_data, R.drawable.data_art),
    Assistant(R.string.app_assistant, R.drawable.assistant_art),
    Mail(R.string.app_mail, R.drawable.mail_art),
    Messages(R.string.app_sms, R.drawable.sms_art),
    Browser(R.string.app_browser, R.drawable.browser_art),
    Pills(R.string.app_pills, R.drawable.pills_art),
    Passwords(R.string.app_passwords, R.drawable.passwords_art);

    private fun component(context: Context) = ComponentName(context.packageName, "${Applet::class.java.name.substringBeforeLast('.')}.App$name")

    fun shown(context: Context) = context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** The icon put in or taken out of the launcher (Tetra keeps running: DONT_KILL_APP). */
    fun show(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(component(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        WriterLog.i("App icon ${if (on) "shown" else "hidden"}: $name")
    }

    companion object {
        const val EXTRA = "com.kalotrapezis.drive.APPLET"
        fun of(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/**
 * An applet icon's tap: hands its part to Tetra's one window and goes. A launcher alias of MainActivity itself would
 * only bring a running Tetra to the front where it was; from here MainActivity is asked again, and turns to the part.
 */
class AppletLaunch : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val applet = intent.component?.className?.substringAfterLast(".App")
        startActivity(Intent(this, MainActivity::class.java).putExtra(Applet.EXTRA, applet)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
