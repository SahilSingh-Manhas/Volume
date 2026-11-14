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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * Hamesha screen pe ek round button. Tap karo to volume bar khulta hai: upar swipe = zyada, niche = kam.
 * Button pakad ke kheencho to kahin bhi move karo. Bar 3 sec baad apne aap band.
 */
class VolumeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

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

    private var barView: VolumeBarView? = null
    private var expanded = false
    private val autoHide = Runnable { setExpanded(false) }

    /** Vertical volume bar: upar swipe = zyada, niche swipe = kam. */
    private inner class VolumeBarView(c: Context) : View(c) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 30, 30, 30) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E88E5") }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(14).toFloat()
            textAlign = Paint.Align.CENTER
        }

        private fun fraction(): Float {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = w / 2f
            canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
            val top = h - h * fraction()
            canvas.save()
            val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawRect(0f, top, w, h, fill)
            canvas.restore()
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            canvas.drawText(cur.toString(), w / 2f, h - dp(12).toFloat(), label)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    handler.removeCallbacks(autoHide)
                    val f = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * max), 0)
                    } catch (e: SecurityException) {
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    handler.postDelayed(autoHide, AUTO_HIDE_MS)
            }
            return true
        }
    }

    private fun addOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val button = TextView(this).apply {
            text = "\u266B"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Volume"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E88E5"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP))
        }
        val bar = VolumeBarView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BAR_DP)).apply {
                topMargin = dp(8)
            }
        }
        barView = bar
        root.addView(button)
        root.addView(bar)

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
        attachButtonTouch(button, root, lp)

        windowManager.addView(root, lp)
        panel = root
        params = lp
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    private fun updateLevel() {
        barView?.invalidate()
    }

    private fun setExpanded(value: Boolean) {
        val p = params ?: return
        val v = panel ?: return
        expanded = value
        handler.removeCallbacks(autoHide)
        barView?.visibility = if (value) View.VISIBLE else View.GONE
        barView?.invalidate()
        clamp(p)
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
        if (value) handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    /** Tap = bar kholo/band karo. Pakad ke kheencho = button move karo. */
    private fun attachButtonTouch(button: View, root: View, lp: WindowManager.LayoutParams) {
        val slop = dp(8)
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    handler.removeCallbacks(autoHide)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (dragged || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        dragged = true
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clamp(lp)
                        try {
                            windowManager.updateViewLayout(root, lp)
                        } catch (e: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        Prefs.setPosition(this, lp.x, lp.y)
                        if (expanded) handler.postDelayed(autoHide, AUTO_HIDE_MS)
                    } else {
                        setExpanded(!expanded)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (expanded) handler.postDelayed(autoHide, AUTO_HIDE_MS)
                }
            }
            true
        }
    }

    /** Panel ko hamesha screen ke andar rakhta hai (bar khula ho to uski height bhi gino). */
    private fun clamp(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val h = if (expanded) dp(BUTTON_DP + 8 + BAR_DP) else dp(BUTTON_DP)
        val maxX = (dm.widthPixels - dp(BUTTON_DP)).coerceAtLeast(0)
        val maxY = (dm.heightPixels - h).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Purana channel hata do: channel ki lockscreen setting install ke baad badalti nahi.
        nm.deleteNotificationChannel("volume_overlay")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Volume button", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
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
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.sahil.volumebutton.ACTION_STOP"
        private const val CHANNEL_ID = "volume_overlay_v2"
        private const val NOTIF_ID = 1001
        private const val AUTO_HIDE_MS = 3000L
        private const val BUTTON_DP = 56
        private const val BAR_DP = 220
    }
}
