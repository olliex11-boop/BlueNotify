package com.example.trackernotifier

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
        if (intent.action !in validActions) return

        val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
        val shouldAutoStart = prefs.getBoolean("auto_start_enabled", false)
        if (!shouldAutoStart) return

        val hasBtConnect = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        if (hasBtConnect) {
            val savedFilter = prefs.getString("device_filter", "").orEmpty()
            TrackerForegroundService.start(context, savedFilter)
        }
    }
}
