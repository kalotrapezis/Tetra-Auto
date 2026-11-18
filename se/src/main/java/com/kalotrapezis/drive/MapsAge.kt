package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat

/**
 * A map turning old (the user, 27 Oct: "a proper notification, a system notification"): once per map each time it is
 * due ([MapsFiles.isOld], keyed by its built date, so a remade map that ages again is said again), saying the computer
 * remakes it; a tap opens Settings › Maps. Looked at as Tetra starts and with the Calendar's quarter-hourly look.
 */
internal object MapsAge {
    const val SCREEN = "settings-maps" // the tap: Settings › Maps (MainActivity)
    private const val CHANNEL = "maps_old_nobadge"

    fun check(c: Context) {
        val root = MapsFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))
        val prefs = c.getSharedPreferences("maps_age", Context.MODE_PRIVATE)
        val said = prefs.getStringSet("said", emptySet())!!
        val today = java.time.LocalDate.now()
        val due = MapsFiles.maps(root).map { it to MapsFiles.built(root, it.id) }.filter { (_, b) -> MapsFiles.isOld(b, today) }
        val new = due.filter { (m, b) -> "${m.id}@$b" !in said }
        if (new.isEmpty()) return
        val nm = c.getSystemService(NotificationManager::class.java)
        // made again each time: an existing channel takes only its name, in the language now
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.maps_old_channel), NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(c, CHANNEL.hashCode(), Intent(c, MainActivity::class.java).putExtra(Applet.EXTRA, SCREEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        new.forEach { (m, _) ->
            val body = c.getString(R.string.maps_old_body)
            val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_map).setContentTitle(c.getString(R.string.maps_old_title, m.name))
                .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify("maps_old_${m.id}".hashCode(), n) }
        }
        // the maps no longer due drop out, so the set stays as small as the maps
        prefs.edit().putStringSet("said", due.map { (m, b) -> "${m.id}@$b" }.toSet()).apply()
    }
}
