package com.example.petpet

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.sin

class PetService : Service() {

    companion object {
        private const val CHANNEL_ID = "petpet_channel"
        private const val NOTI_ID = 1001

        // 想改桌宠大小，改这里（单位：dp）
        private const val PET_WIDTH_DP = 150
    }

    private lateinit var wm: WindowManager
    private var pet: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private val frames = ArrayList<Int>()
    private var frameIndex = 0
    private var snapAnim: ValueAnimator? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val bobTicker = object : Runnable {
        override fun run() {
            val t = System.currentTimeMillis() / 1000.0
            pet?.translationY = (sin(t * 2.0) * dp(5)).toFloat()
            handler.postDelayed(this, 60)
        }
    }

    private val frameTicker = object : Runnable {
        override fun run() {
            advanceFrame()
            handler.postDelayed(this, 220)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        loadFrames()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (pet == null) addPetView()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        pet?.let { runCatching { wm.removeView(it) } }
        pet = null
        super.onDestroy()
    }

    private fun addPetView() {
        val iv = ImageView(this).apply {
            setImageResource(frames[0])
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val p = WindowManager.LayoutParams(
            dp(PET_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(20)
        p.y = (resources.displayMetrics.heightPixels * 0.55f).toInt()

        iv.setOnTouchListener { v, e -> handleTouch(v, e) }

        wm.addView(iv, p)
        pet = iv
        lp = p

        handler.post(bobTicker)
        handler.post(frameTicker)
    }

    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        val p = lp ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnim?.cancel()
                downRawX = e.rawX
                downRawY = e.rawY
                startX = p.x
                startY = p.y
                moved = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (abs(dx) > dp(10) || abs(dy) > dp(10)) moved = true
                if (moved) {
                    p.x = (startX + dx).toInt()
                    p.y = (startY + dy).toInt()
                    wm.updateViewLayout(v, p)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) {
                    snapToEdge()
                } else {
                    onTapped()
                }
                return true
            }
        }
        return false
    }

    private fun snapToEdge() {
        val v = pet ?: return
        val p = lp ?: return
        val screenW = resources.displayMetrics.widthPixels
        val viewW = if (v.width > 0) v.width else dp(PET_WIDTH_DP)
        val target = if (p.x + viewW / 2 < screenW / 2) 0 else screenW - viewW

        val anim = ValueAnimator.ofInt(p.x, target).apply {
            duration = 220
            addUpdateListener {
                p.x = it.animatedValue as Int
                wm.updateViewLayout(v, p)
            }
        }
        snapAnim = anim
        anim.start()
    }

    private fun onTapped() {
        val v = pet ?: return
        advanceFrame()
        v.animate().cancel()
        v.animate()
            .scaleX(1.22f).scaleY(1.22f).setDuration(90)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            .start()
    }

    private fun loadFrames() {
        frames.clear()
        var i = 0
        while (i < 240) {
            val id = resources.getIdentifier("pet_$i", "drawable", packageName)
            if (id == 0) break
            frames.add(id)
            i++
        }
        if (frames.isEmpty()) {
            val single = resources.getIdentifier("pet", "drawable", packageName)
            frames.add(if (single != 0) single else R.drawable.ic_launcher)
        }
    }

    private fun advanceFrame() {
        if (frames.size <= 1) return
        frameIndex = (frameIndex + 1) % frames.size
        pet?.setImageResource(frames[frameIndex])
    }

    private fun startForegroundCompat() {
        val noti = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("桌宠运行中")
            .setContentText("点这里回到设置页")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
