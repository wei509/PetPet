package com.example.petpet

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
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
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class PetService : Service() {

    // ★ 台词改这里
    private val lines = arrayOf(
        "干嘛呀～",
        "主人今天也辛苦啦",
        "摸头要收费的哦",
        "唔…好困…",
        "你在看什么呀？",
        "嘿嘿～",
        "别戳啦，痒！",
        "要一起玩吗？",
        "有点想回海里了…",
        "今天也要加油哦！"
    )

    // ★ 桌宠大小改这里（单位dp）
    private val petDp = 150
    private val boxDp = (petDp * 1.5f).toInt()
    private val padDp = (petDp * 0.25f).toInt()

    private lateinit var wm: WindowManager
    private var pet: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var hideTask: Runnable? = null
    private var tone: ToneGenerator? = null

    private val handler = Handler(Looper.getMainLooper())
    private val frames = ArrayList<Int>()
    private val sounds = ArrayList<Int>()
    private var frameIndex = 0

    private var popAt = 0L
    private var idleAt = 0L
    private var idleKind = 0
    private var nextIdle = 0L
    private var tilt = 0f
    private var touching = false
    private var lastX = 0f

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var snapAnim: ValueAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            draw()
            handler.postDelayed(this, 33)
        }
    }

    private val frameTicker = object : Runnable {
        override fun run() {
            nextFrame()
            handler.postDelayed(this, 220)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        loadFrames()
        loadSounds()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val noti = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, noti)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pet == null) {
            addPet()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        hideBubble()
        val g = tone
        if (g != null) {
            runCatching { g.release() }
        }
        tone = null
        val v = pet
        if (v != null) {
            runCatching { wm.removeView(v) }
        }
        pet = null
        super.onDestroy()
    }    @Suppress("DEPRECATION")
    private fun overlayType(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun baseFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        f = f or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        f = f or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return f
    }

    private fun addPet() {
        val iv = ImageView(this)
        iv.setImageResource(frames[0])
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        iv.setBackgroundColor(Color.TRANSPARENT)

        val p = WindowManager.LayoutParams(
            dp(boxDp), dp(boxDp), overlayType(), baseFlags(), PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(20)
        p.y = (resources.displayMetrics.heightPixels * 0.55f).toInt()

        iv.setOnTouchListener { v, e -> onTouch(v, e) }

        wm.addView(iv, p)
        pet = iv
        lp = p
        handler.post(ticker)
        handler.post(frameTicker)
    }

    private fun draw() {
        val v = pet
        if (v == null) {
            return
        }
        val now = System.currentTimeMillis()
        val t = now / 1000.0

        var k = 1.0 + 0.022 * sin(t * 2.4)

        if (popAt > 0L) {
            val e = (now - popAt) / 320.0
            if (e >= 1.0) {
                popAt = 0L
            } else {
                k *= 1.0 + 0.28 * sin(e * PI)
            }
        }

        var rot = 0f
        var hop = 0f
        if (idleAt > 0L) {
            val e = (now - idleAt) / 560.0
            if (e >= 1.0) {
                idleAt = 0L
            } else if (idleKind == 0) {
                hop = (-dp(12) * sin(e * PI)).toFloat()
            } else if (idleKind == 1) {
                rot = (8.0 * sin(e * PI * 2)).toFloat()
            } else {
                k *= 1.0 + 0.09 * sin(e * PI)
            }
        }

        if (!touching) {
            tilt *= 0.86f
        }

        var bob = 0f
        if (!touching) {
            bob = (sin(t * 1.9) * dp(4)).toFloat()
        }

        v.scaleX = k.toFloat()
        v.scaleY = k.toFloat()
        v.rotation = tilt + rot
        v.translationY = bob + hop

        if (nextIdle == 0L) {
            nextIdle = now + 4000L
        }
        if (now > nextIdle && idleAt == 0L && popAt == 0L && !touching) {
            idleKind = Random.nextInt(3)
            idleAt = now
            nextIdle = now + Random.nextLong(5000L, 13000L)
        }
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val p = lp
        if (p == null) {
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val a = snapAnim
                if (a != null) {
                    a.cancel()
                }
                downX = e.rawX
                downY = e.rawY
                lastX = e.rawX
                startX = p.x
                startY = p.y
                moved = false
                touching = true
                hideBubble()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (abs(dx) > dp(10) || abs(dy) > dp(10)) {
                    moved = true
                }
                if (moved) {
                    p.x = (startX + dx).toInt()
                    p.y = (startY + dy).toInt()
                    wm.updateViewLayout(v, p)
                    tilt = tilt * 0.55f + (e.rawX - lastX) * 1.6f
                    if (tilt > 15f) {
                        tilt = 15f
                    }
                    if (tilt < -15f) {
                        tilt = -15f
                    }
                }
                lastX = e.rawX
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (moved) {
                    snapEdge()
                } else {
                    tap()
                }
                return true
            }
        }
        return false
    }

    private fun snapEdge() {
        val v = pet
        val p = lp
        if (v == null || p == null) {
            return
        }
        val sw = resources.displayMetrics.widthPixels
        var w = v.width
        if (w <= 0) {
            w = dp(boxDp)
        }
        var target = sw - w + dp(padDp)
        if (p.x + w / 2 < sw / 2) {
            target = -dp(padDp)
        }
        val a = ValueAnimator.ofInt(p.x, target)
        a.duration = 240L
        a.addUpdateListener {
            p.x = it.animatedValue as Int
            wm.updateViewLayout(v, p)
        }
        a.start()
        snapAnim = a
    }

    private fun tap() {
        popAt = System.currentTimeMillis()
        nextFrame()
        playSound()
        showBubble(lines[Random.nextInt(lines.size)])
    }

    private fun showBubble(text: String) {
        hideBubble()

        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.setPadding(dp(12), dp(7), dp(12), dp(7))
        tv.maxWidth = dp(190)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(Color.argb(230, 43, 58, 85))
        tv.background = bg
        tv.alpha = 0f

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), baseFlags(), PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START

        runCatching { wm.addView(tv, p) }
        bubble = tv

        val parent = lp
        if (parent != null) {
            tv.post {
                val pw = tv.width
                val ph = tv.height
                if (pw > 0) {
                    val sw = resources.displayMetrics.widthPixels
                    var x = parent.x + parent.width / 2 - pw / 2
                    if (x < dp(4)) {
                        x = dp(4)
                    }
                    if (x > sw - pw - dp(4)) {
                        x = sw - pw - dp(4)
                    }
                    var y = parent.y + dp(padDp) - ph - dp(6)
                    if (y < dp(30)) {
                        y = parent.y + parent.height - dp(padDp) + dp(6)
                    }
                    p.x = x
                    p.y = y
                    runCatching { wm.updateViewLayout(tv, p) }
                }
            }
        }

        tv.animate().alpha(1f).setDuration(130L).start()

        val t = Runnable {
            tv.animate().alpha(0f).setDuration(200L).withEndAction {
                if (bubble === tv) {
                    hideBubble()
                }
            }.start()
        }
        hideTask = t
        handler.postDelayed(t, 1700L)
    }

    private fun hideBubble() {
        val t = hideTask
        if (t != null) {
            handler.removeCallbacks(t)
        }
        hideTask = null
        val b = bubble
        if (b != null) {
            runCatching { wm.removeView(b) }
        }
        bubble = null
    }

    private fun loadFrames() {
        frames.clear()
        var i = 0
        while (i < 240) {
            val id = resources.getIdentifier("pet_" + i, "drawable", packageName)
            if (id == 0) {
                break
            }
            frames.add(id)
            i++
        }
        if (frames.isEmpty()) {
            val s = resources.getIdentifier("pet", "drawable", packageName)
            if (s != 0) {
                frames.add(s)
            } else {
                frames.add(R.drawable.ic_launcher)
            }
        }
    }

    private fun nextFrame() {
        if (frames.size <= 1) {
            return
        }
        frameIndex = (frameIndex + 1) % frames.size
        val v = pet
        if (v != null) {
            v.setImageResource(frames[frameIndex])
        }
    }

    private fun loadSounds() {
        sounds.clear()
        var i = 0
        while (i < 30) {
            val id = resources.getIdentifier("tap_" + i, "raw", packageName)
            if (id == 0) {
                break
            }
            sounds.add(id)
            i++
        }
    }

    private fun playSound() {
        // ① 如果你以后上传过 tap_0.mp3 这类音频，优先播放它们
        if (sounds.isNotEmpty()) {
            val id = sounds[Random.nextInt(sounds.size)]
            val played = runCatching {
                val mp = MediaPlayer.create(this, id)
                if (mp != null) {
                    mp.setOnCompletionListener { it.release() }
                    mp.start()
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (played) {
                return
            }
        }
        // ② 没有音频文件时，用手机自带的提示音（零素材）
        try {
            var g = tone
            if (g == null) {
                g = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
                tone = g
            }
            val pool = intArrayOf(
                ToneGenerator.TONE_PROP_ACK,
                ToneGenerator.TONE_PROP_BEEP,
                ToneGenerator.TONE_PROP_BEEP2,
                ToneGenerator.TONE_PROP_PROMPT
            )
            g.startTone(pool[Random.nextInt(pool.size)], 120)
        } catch (e: Exception) {
            // 少数手机不支持系统提示音，忽略即可
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel("petpet") == null) {
                val ch = NotificationChannel("petpet", "桌宠", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = Notification.Builder(this, "petpet")
        } else {
            builder = Notification.Builder(this)
        }
        return builder
            .setContentTitle("桌宠运行中")
            .setContentText("点这里回到设置页")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }
}
