package com.sahil.volumebutton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hamesha screen pe dikhne wala floating panel: [handle] [+] [level] [-]
 * Handle pakad ke panel kahin bhi drag kar sakte ho. + / - dabao (ya daba ke rakho) volume badlo.
 */
class VolumeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var levelText: TextView? = null

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateLevel()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService() ke 5 second ke andar yeh call zaroori hai, isliye sabse pehle.
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.setEnabled(this, true)
        if (panel == null) {
            try {
                addOverlay()
            } catch (e: Exception) {
                // Overlay add na ho paya (e.g. permission turant hati) -> band kar do, crash nahi.
                panel = null
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotate hone pe panel screen ke bahar na reh jaye.
        val p = params ?: return
        val v = panel ?: return
        clamp(p)
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            contentResolver.unregisterContentObserver(volumeObserver)
        } catch (e: Exception) {
        }
        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
        }
        panel = null
        super.onDestroy()
    }

    // ---------- Overlay UI ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun makeLabel(text: String, sizeSp: Float, desc: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = desc
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                topMargin = dp(4)
            }
        }

    private fun addOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundBg(Color.argb(210, 30, 30, 30), 28)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        val dragHandle = makeLabel("☰", 18f, "Move").apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(36))
        }
        val plus = makeLabel("+", 28f, "Volume up").apply {
            background = roundBg(Color.parseColor("#1E88E5"), 26)
        }
        val level = makeLabel("", 16f, "Current volume")
        val minus = makeLabel("−", 28f, "Volume down").apply {
            background = roundBg(Color.parseColor("#1E88E5"), 26)
        }
        levelText = level

        root.addView(dragHandle)
        root.addView(plus)
        root.addView(level)
        root.addView(minus)

        attachRepeatingButton(plus, AudioManager.ADJUST_RAISE)
        attachRepeatingButton(minus, AudioManager.ADJUST_LOWER)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.getX(this@VolumeOverlayService)
            y = Prefs.getY(this@VolumeOverlayService)
        }
        clamp(lp)

        attachDrag(dragHandle, root, lp)

        windowManager.addView(root, lp)
        panel = root
        params = lp

        updateLevel()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    private fun updateLevel() {
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        levelText?.text = "$cur/$max"
    }

    private fun adjust(direction: Int) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (e: SecurityException) {
            // Kuch phones (DND mode) mein block ho sakta hai; ignore, crash nahi.
        }
        updateLevel()
    }

    /** Ek tap = ek step. Daba ke rakho = tez repeat. */
    private fun attachRepeatingButton(view: View, direction: Int) {
        val repeat = object : Runnable {
            override fun run() {
                adjust(direction)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.alpha = 0.6f
                    adjust(direction)
                    handler.postDelayed(repeat, REPEAT_START_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.alpha = 1f
                    handler.removeCallbacks(repeat)
                    true
                }
                else -> true
            }
        }
    }

    private fun attachDrag(handle: View, root: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clamp(lp)
                    try {
                        windowManager.updateViewLayout(root, lp)
                    } catch (e: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Prefs.setPosition(this, lp.x, lp.y)
                    true
                }
                else -> true
            }
        }
    }

    /** Panel ko hamesha screen ke andar rakhta hai. */
    private fun clamp(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - dp(60)).coerceAtLeast(0)
        val maxY = (dm.heightPixels - dp(260)).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Volume button", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VolumeOverlayService::class.java).setAction(ACTION_STOP),
            flags
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), flags
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_volume)
            .setContentTitle("Volume button chalu hai")
            .setContentText("Band karne ke liye Stop dabao")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(0, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.sahil.volumebutton.ACTION_STOP"
        private const val CHANNEL_ID = "volume_overlay"
        private const val NOTIF_ID = 1001
        private const val REPEAT_START_DELAY_MS = 450L
        private const val REPEAT_INTERVAL_MS = 120L
    }
}
