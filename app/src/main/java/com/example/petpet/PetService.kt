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
import android.media.MediaPlayer
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

    companion object {
        private const val CHANNEL_ID = "petpet_channel"
        private const val NOTI_ID = 1001

        // 👇 桌宠显示大小（dp）。想改大改小就改这个数字
        private const val PET_SIZE_DP = 150
    }

    // 👇👇 台词在这里改，想加多少加多少
    private val lines = listOf(
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

    private lateinit var wm: WindowManager
    private var pet: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleHideTask: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())

    private val frames = ArrayList<Int>()
    private var frameIndex = 0

    private val tapSounds = ArrayList<Int>()

    // 动画状态
    private var popAt = 0L
    private var idleAt = 0L
    private var idleKind = 0
    private var nextIdleAt = 0L
    private var tilt = 0f
    private var touching = false
    private var lastRawX = 0f

    // 拖动状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var snapAnim: ValueAnimator? = null

    // 窗口比图片大 50%，留出放大空间
    private val boxPx by lazy { dp((PET_SIZE_DP * 1.5f).toInt()) }
    private val padPx by lazy { dp((PET_SIZE_DP * 0.25f).toInt()) }

    private val animTicker = object : Runnable {
        override fun run() {
            renderAnimations()
            handler.postDelayed(this, 33)
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
        loadSounds()
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
        hideBubble()
        pet?.let { runCatching { wm.removeView(it) } }
        pet = null
        super.onDestroy()
    }

    // ---------------- 悬浮窗 ----------------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun addPetView() {
        val iv = ImageView(this).apply {
            setImageResource(frames[0])
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padPx, padPx, padPx, padPx)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val p = WindowManager.LayoutParams(
            boxPx,
            boxPx,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(20)
        p.y = (resources.displayMetrics.heightPixels * 0.55f).toInt()

        iv.setOnTouchListener { v, e -> handleTouch(v, e) }

        wm.addView(iv, p)
        pet = iv
        lp = p

        handler.post(animTicker)
        handler.post(frameTicker)
    }

    // ---------------- 动画渲染（统一在一处算，避免打架） ----------------

    private fun renderAnimations() {
        val v = pet ?: return
        val now = System.currentTimeMillis()
        val t = now / 1000.0

        // 💗 呼吸
        var scale = 1.0 + 0.022 * sin(t * 2.4)

        // 👆 点击弹跳
        if (popAt > 0L) {
            val e = (now - popAt) / 320.0
            if (e >= 1.0) {
                popAt = 0L
            } else {
                scale *= 1.0 + 0.28 * sin(e * PI)
            }
        }

        // 🎈 闲置小动作
        var rot = 0f
        var hop = 0f
        if (idleAt > 0L) {
            val e = (now - idleAt) / 560.0
            if (e >= 1.0) {
                idleAt = 0L
            } else {
                when (idleKind) {
                    0 -> hop = -(dp(12) * sin(e * PI)).toFloat()
                    1 -> rot = (8.0 * sin(e * PI * 2)).toFloat()
                    else -> scale *= 1.0 + 0.09 * sin(e * PI)
                }
            }
        }

        // 🖐 拖动倾斜衰减
        if (!touching) tilt *= 0.86f

        // 🎏 上下浮动
        val bob = if (touching) 0f else (sin(t * 1.9) * dp(4)).toFloat()

        v.scaleX = scale.toFloat()
        v.scaleY = scale.toFloat()
        v.rotation = tilt + rot
        v.translationY = bob + hop

        // 安排下一次闲置动作
        if (nextIdleAt == 0L) nextIdleAt = now + 4000
        if (now > nextIdleAt && idleAt == 0L && popAt == 0L && !touching) {
            idleKind = Random.nextInt(3)
            idleAt = now
            nextIdleAt = now + Random.nextLong(5000, 13000)
        }
    }

    // ---------------- 触摸 ----------------

    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        val p = lp ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnim?.cancel()
                downRawX = e.rawX
                downRawY = e.rawY
                lastRawX = e.rawX
                startX = p.x
                startY = p.y
                moved = false
                touching = true
                hideBubble()
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
                    val vx = e.rawX - lastRawX
                    tilt = (tilt * 0.55f + vx * 1.6f).coerceIn(-15f, 15f)
                }
                lastRawX = e.rawX
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
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
        val viewW = if (v.width > 0) v.width else boxPx
        // 补偿内边距，让图片真正贴到屏幕边
        val target = if (p.x + viewW / 2 < screenW / 2) {
            -padPx
        } else {
            screenW - viewW + padPx
        }

        val anim = ValueAnimator.ofInt(p.x, target).apply {
            duration = 240
            addUpdateListener {
                p.x = it.animatedValue as Int
                wm.updateViewLayout(v, p)
            }
        }
        snapAnim = anim
        anim.start()
    }

    private fun onTapped() {
        popAt = System.currentTimeMillis()
        advanceFrame()
        playTapSound()
        showBubble(lines.random())
    }

    // ---------------- 台词气泡 ----------------

    private fun showBubble(text: String) {
        hideBubble()

        val tv = TextView(this).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            maxWidth = dp(190)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE62B3A55.toInt())
            }
        }

        tv.measure(
            View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        )
        blp.gravity = Gravity.TOP or Gravity.START

        tv.alpha = 0f
        runCatching { wm.addView(tv, blp) }
        bubble = tv
        bubbleLp = blp

        val p = lp
        if (p != null) {
            val screenW = resources.displayMetrics.widthPixels
            var x = p.x + p.width / 2 - tv.measuredWidth / 2
            x = x.coerceIn(dp(4), (screenW - tv.measuredWidth - dp(4)).coerceAtLeast(dp(4)))
            var y = p.y + padPx - tv.measuredHeight - dp(6)
            if (y < dp(30)) y = p.y + p.height - padPx + dp(6)
            blp.x = x
            blp.y = y
            runCatching { wm.updateViewLayout(tv, blp) }
        }

        tv.animate().alpha(1f).setDuration(130).start()

        val hide = Runnable {
            tv.animate().alpha(0f).setDuration(220).withEndAction {
                if (bubble === tv) hideBubble()
            }.start()
        }
        bubbleHideTask = hide
        handler.postDelayed(hide, 1700)
    }

    private fun hideBubble() {
        bubbleHideTask?.let { handler.removeCallbacks(it) }
        bubbleHideTask = null
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleLp = null
    }

    // ---------------- 帧动画 & 音效 ----------------

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

    /** 自动识别 res/raw 里的 tap_0.mp3、tap_1.mp3 … */
    private fun loadSounds() {
        tapSounds.clear()
        var i = 0
        while (i < 30) {
            val id = resources.getIdentifier("tap_$i", "raw", packageName)
            if (id == 0) break
            tapSounds.add(id)
            i++
        }
    }

    private fun playTapSound() {
        if (tapSounds.isEmpty()) return
        runCatching {
            val mp = MediaPlayer.create(this, tapSounds.random())
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        }
    }

    // ---------------- 通知 ----------------

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
