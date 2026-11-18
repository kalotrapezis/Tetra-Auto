package com.kalotrapezis.drive

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Xiaomi HyperOS shows an ongoing notification in the Dynamic Island when the notification carries a
 * `miui.focus.param` extra: a JSON description of what to draw. That is the whole mechanism — no SDK, no
 * service, no new permission — so this writes the JSON rather than taking a dependency for it. (The
 * `hyperisland_kit` library does the same thing, but declares minSdk 35 and pulls in appcompat, material and
 * kotlinx-serialization, none of which this app has a use for.)
 *
 * Every device that is not a Xiaomi, and every Xiaomi that has not granted focus notifications, simply
 * ignores the extra and shows the ordinary notification, which is why nothing here is allowed to throw.
 */
internal object HyperIsland {
    private const val EXTRA = "miui.focus.param"

    /** One id per kind of island, so a backup and a read of the gallery are two islands, not one fighting itself. */
    const val SYNC = "tetra_sync"
    const val ANALYSIS = "tetra_analysis"
    private const val ACCENT = "#4C8DF6"
    private const val EXPANDED_MS = 2500 // how long the big card stays before it shrinks back to the pill

    fun isSupported(context: Context): Boolean = runCatching {
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) && systemFeature() && canShowFocus(context)
    }.getOrDefault(false)

    /**
     * Adds the island description to a notification that already works on its own. The island itself is only the
     * progress: Pause and Stop stay in the app and in the notification, where they are the platform's own
     * controls rather than MIUI-styled buttons that match nothing else in Tetra.
     */
    fun decorate(builder: NotificationCompat.Builder, title: String, text: String, done: Int, total: Int, announce: Boolean = false, business: String = SYNC) {
        runCatching { builder.addExtras(Bundle().apply { putString(EXTRA, param(title, text, done, total, announce, business)) }) }
    }

    private fun param(title: String, text: String, done: Int, total: Int, announce: Boolean, business: String): String {
        val paramV2 = JSONObject()
            .put("protocol", 3) // the version param_v2 is read as; anything else is parsed to nothing
            .put("business", business)
            .put("updatable", true) // one island that changes, not a new one per progress step
            .put("ticker", title)
            // Only the first post opens the big card; after that the island is a pill that quietly counts up,
            // otherwise every file sent would pop the card open again.
            .put("enableFloat", announce)
            .put("baseInfo", JSONObject().put("type", 1).put("title", title).put("content", text))
            // The island is only a few characters wide, so it gets the app icon and the count, not the sentence.
            .put("param_island", JSONObject()
                .put("islandProperty", 1)
                .put("islandPriority", 1)
                .put("expandedTime", EXPANDED_MS)
                .put("bigIslandArea", JSONObject()
                    .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", JSONObject().put("type", 1)))
                    .put("imageTextInfoRight", JSONObject().put("type", 2)
                        .put("textInfo", JSONObject().put("title", if (total > 0) "$done/$total" else "…")))))
        if (total > 0) paramV2.put("progressInfo", JSONObject()
            .put("progress", (done.toLong() * 100 / total).coerceIn(0, 100))
            .put("colorProgress", ACCENT))
        return JSONObject().put("param_v2", paramV2).put("title", title).put("content", text).toString()
    }

    const val CALENDAR = "tetra_calendar"

    /**
     * A Calendar reminder in the island (the user, 18 Oct: "make it work with hyper island like the sync does"): the card
     * opens once with the event and its time, then stays a pill with the time; its X marks the reminder done, Snooze
     * rings it again in ten minutes. The buttons are the notification's own actions, linked by key, as HyperOS reads
     * them (`param_v2.actions` naming `miui.focus.action_<key>` in the `miui.focus.actions` bundle; the format the
     * open-source HyperIsland ToolKit writes).
     */
    fun reminder(builder: NotificationCompat.Builder, title: String, text: String, time: String,
                 done: android.app.PendingIntent, doneIcon: android.graphics.drawable.Icon, snooze: android.app.PendingIntent?) {
        runCatching {
            // the builder's own context: the callers' signature stays as it is
            @android.annotation.SuppressLint("RestrictedApi") val c = builder.mContext
            val doneLabel = c.getString(R.string.home_island_done); val snoozeLabel = c.getString(R.string.home_island_snooze)
            val actions = Bundle().apply {
                putParcelable("miui.focus.action_done", android.app.Notification.Action.Builder(doneIcon, doneLabel, done).build())
                if (snooze != null) putParcelable("miui.focus.action_snooze", android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?, snoozeLabel, snooze).build())
            }
            val paramV2 = JSONObject()
                .put("protocol", 3)
                .put("business", CALENDAR)
                .put("updatable", true) // as sync's, which the island shows
                .put("ticker", title)
                .put("enableFloat", true) // a reminder is the moment: the card opens
                .put("isShowNotification", true)
                .put("islandFirstFloat", true)
                .put("baseInfo", JSONObject().put("type", 1).put("title", title).put("content", text))
                .put("actions", org.json.JSONArray()
                    .put(JSONObject().put("type", 0).put("action", "miui.focus.action_done").put("actionTitle", doneLabel).put("actionIntentType", 2))
                    .apply { if (snooze != null) put(JSONObject().put("type", 2).put("action", "miui.focus.action_snooze").put("actionTitle", snoozeLabel).put("actionIntentType", 2)) })
                .put("param_island", JSONObject()
                    .put("islandProperty", 1)
                    .put("islandPriority", 1)
                    .put("expandedTime", 5000)
                    .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1)))
                    .put("bigIslandArea", JSONObject()
                        .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", JSONObject().put("type", 1)))
                        .put("imageTextInfoRight", JSONObject().put("type", 2).put("textInfo", JSONObject().put("title", time)))))
            builder.addExtras(Bundle().apply {
                putString(EXTRA, JSONObject().put("param_v2", paramV2).put("title", title).put("content", text).toString())
                putBundle("miui.focus.actions", actions)
            })
        }
    }

    const val TIMER = "tetra_timer"
    const val STOPWATCH = "tetra_stopwatch"
    const val ALARM = "tetra_alarm"

    /**
     * A timer in the island (the user, 18 Oct: "active notification on the hyper island with pause and stop buttons"):
     * running, the island counts down by itself to [ends] (HyperOS's countdown, timerType -1, as Xiaomi's own Clock);
     * paused or rung, it shows [shown]. [buttons] are linked by key as the reminder's; with [icons] each is an icon only (type 0, as
     * the reminder's ✕) with its label kept for TalkBack. The
 * stopwatch counts up from [ends] (its start: timerType 1); an alarm shows its time.
     */
    fun timer(builder: NotificationCompat.Builder, title: String, text: String, ends: Long?, shown: String, announce: Boolean,
              buttons: List<Triple<String, String, android.app.PendingIntent>>, business: String = TIMER, countUp: Boolean = false, icons: List<Int> = emptyList()) {
        runCatching {
            val now = System.currentTimeMillis()
            @android.annotation.SuppressLint("RestrictedApi") val c = builder.mContext
            val actions = Bundle().apply {
                buttons.forEachIndexed { i, (key, label, pi) ->
                    val icon = icons.getOrNull(i)?.let { android.graphics.drawable.Icon.createWithResource(c, it) }
                    putParcelable("miui.focus.action_$key", android.app.Notification.Action.Builder(icon, label, pi).build())
                }
            }
            val right = if (ends != null) JSONObject().put("sameWidthDigitInfo", JSONObject()
                .put("timerInfo", JSONObject().put("timerType", if (countUp) 1 else -1).put("timerWhen", ends).put("timerTotal", now).put("timerSystemCurrent", now)))
            else JSONObject().put("imageTextInfoRight", JSONObject().put("type", 2).put("textInfo", JSONObject().put("title", shown)))
            val paramV2 = JSONObject()
                .put("protocol", 3)
                .put("business", business)
                .put("updatable", true)
                .put("ticker", title)
                .put("enableFloat", announce) // the card opens when it starts and when it rings, not at each pause
                .put("isShowNotification", true)
                .put("baseInfo", JSONObject().put("type", 1).put("title", title).put("content", text))
                .put("actions", org.json.JSONArray().apply {
                    buttons.forEachIndexed { i, (key, label, _) -> put(JSONObject().put("type", if (i == 0 || icons.isNotEmpty()) 0 else 2).put("action", "miui.focus.action_$key").put("actionTitle", label).put("actionIntentType", 2)) }
                })
                .put("param_island", JSONObject()
                    .put("islandProperty", 1)
                    .put("islandPriority", 1)
                    .put("expandedTime", 4000)
                    .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1)))
                    .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", JSONObject().put("type", 1))).apply {
                        right.keys().forEach { k -> put(k, right.get(k)) }
                    }))
            builder.addExtras(Bundle().apply {
                putString(EXTRA, JSONObject().put("param_v2", paramV2).put("title", title).put("content", text).toString())
                putBundle("miui.focus.actions", actions)
            })
        }
    }

    /** HyperOS only draws an island where the device has one. */
    private fun systemFeature(): Boolean = Class.forName("android.os.SystemProperties")
        .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        .invoke(null, "persist.sys.feature.island", false) as Boolean

    /** The user can turn focus notifications off per app; MIUI answers for this package. */
    private fun canShowFocus(context: Context): Boolean = context.contentResolver.call(
        Uri.parse("content://miui.statusbar.notification.public"),
        "canShowFocus",
        null,
        Bundle().apply { putString("package", context.packageName) },
    )?.getBoolean("canShowFocus", false) == true
}
