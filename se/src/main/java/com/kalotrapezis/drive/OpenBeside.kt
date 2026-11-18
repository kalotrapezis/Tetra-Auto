package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager

/**
 * Two Tetra applets side by side in Android's own split screen (asked 1 Nov: AI Assist beside a document): a second
 * MainActivity (or the applet's own window, AppletActivities.kt) in a task of its own, launched adjacent. Android 12L+ on a wide screen; elsewhere the plain open.
 */
internal object Beside {
    /** Split screen is there for Tetra: 12L or later, a screen at least 600 dp on its short side (a tablet). */
    fun can(c: Context) = Build.VERSION.SDK_INT >= 32 && c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        .let { minOf(it.width(), it.height()) / c.resources.displayMetrics.density >= 600 }

    /** [what] as MainActivity reads Applet.EXTRA: an applet's name, "note:<id>", "mail:<id>", "file:<relative path>". */
    // shortcut: each open beside is a new task (Recents gains one each time); reuse the last one if it piles up
    fun open(c: Context, what: String) = c.startActivity(appletIntent(c, what).addFlags(
        if (can(c)) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}
