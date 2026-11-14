package com.sahil.volumebutton

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Volume Button"
            textSize = 26f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        val start = Button(this).apply {
            text = "Start"
            setOnClickListener { startOverlay() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopOverlay() }
        }

        root.addView(title)
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = when {
            !Settings.canDrawOverlays(this) ->
                "Pehle \"Start\" dabao, phir \"Display over other apps\" permission allow karo."
            Prefs.isEnabled(this) -> "Volume button chalu hai. Screen pe + aur − dikhenge."
            else -> "Volume button band hai. \"Start\" dabao."
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Notification na bhi mile to overlay chalega; bas Stop wala notification nahi dikhega.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        try {
            startForegroundService(Intent(this, VolumeOverlayService::class.java))
        } catch (e: Exception) {
            status.text = "Start nahi ho paya, dobara try karo."
            return
        }
        // Service khud Prefs.enabled = true set karti hai; UI thodi der baad refresh hoga.
        status.postDelayed({ refreshStatus() }, 400)
    }

    private fun stopOverlay() {
        startService(
            Intent(this, VolumeOverlayService::class.java).setAction(VolumeOverlayService.ACTION_STOP)
        )
        Prefs.setEnabled(this, false)
        refreshStatus()
    }

    companion object {
        private const val REQ_NOTIF = 10
    }
}
