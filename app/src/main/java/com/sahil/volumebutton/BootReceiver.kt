package com.sahil.volumebutton

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Phone restart / app update ke baad overlay wapas chalu karta hai (agar user ne on rakha tha). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.isEnabled(context) || !Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(Intent(context, VolumeOverlayService::class.java))
        } catch (e: Exception) {
            // System ne background start roka to crash nahi, user app khol ke start kar lega.
        }
    }
}
