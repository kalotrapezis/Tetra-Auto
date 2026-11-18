package com.kalotrapezis.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pause and Resume, wherever they are tapped from — the notification, or the Dynamic Island, which fires the
 * button's intent from the system UI's process. A broadcast is the one delivery that is never turned down for
 * being started from outside the app, and it only flips a flag the running service is already watching.
 */
internal class SyncActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SyncService.ACTION_PAUSE -> if (SyncService.isRunning) SyncService.togglePause()
            PhotoAnalysisService.ACTION_PAUSE -> if (PhotoAnalysisService.isRunning) PhotoAnalysisService.togglePause()
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { SyncListenService.start(context); DataSpeed.start(context) } // listening (and the live speed) again after a restart or an update
        }
    }
}
