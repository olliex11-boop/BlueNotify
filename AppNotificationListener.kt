package com.example.trackernotifier

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AppNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
        val allowedPackages = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()

        if (sbn.packageName !in allowedPackages) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (title.isNotBlank() || text.isNotBlank()) {
            val appLabel = try {
                val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                sbn.packageName
            }
            val patternName = prefs.getString("vibe_pattern_${sbn.packageName}", VibrationPattern.SINGLE.name)
            val pattern = VibrationPattern.fromName(patternName)
            BleTrackerManager.sendNotificationAlert(appLabel, title, text, pattern)
        }
    }
}
