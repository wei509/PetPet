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
import kotlin.math.abs
import kotlin.random.Random

class PetService : Service() {

    // ★ 点击时说的话
    private val clickLines = arrayOf(
        "干嘛呀～",
        "主人今天也辛苦啦",
        "摸头要收费的哦",
        "唔…好困…",
        "你在看什么呀？",
        "嘿嘿～",
        "别戳啦，痒！",
        "要一起玩吗？"
    )

    // ★ 被摸头时说的话
    private val patLines = arrayOf(
        "唔…",
        "嘿嘿～",
        "别摸啦～",
        "舒服…",
        "再摸一下下…",
        "啊？"
    )

    // ★ 桌宠大小（单位dp，想改大改小改这个数）
    private val petDp = 150
    private val boxDp = (petDp * 1.5f).toInt()
    private val padDp = (petDp * 0.25f).toInt()

    // ★ 三组动画各自的播放速度（毫秒/帧，数字越小动得越快）
    private val idleMs = 260L
    private val clickMs = 130L
    private val patMs = 170L

    // ★ 按住多久算"摸头"（毫秒）
    private val holdMs = 600L

    // 状态：0=待机  1=被点击  2=被摸头
    private var state = 0
    private var stateIndex = 0

    private lateinit var wm: WindowManager
    private var pet: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var hideTask: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())

    private val idleFrames = ArrayList<Int>()
    private val clickFrames = ArrayList<Int>()
    private val patFrames = ArrayList<Int>()

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var pressed = false
    private var holdFired = false
    private var snapAnim: ValueAnimator? = null

    private val holdTask = Runnable {
        if (pressed && !moved) {
            holdFired = true
            doPat()
        }
    }

    private val frameTicker = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, speedOf(state))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        loadFrames()
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
        val v = pet
        if (v != null) {
            runCatching { wm.removeView(v) }
        }
        pet = null
        super.onDestroy()
    }

    // ===== 第①段结束 =====    // ===== 第②段开始 =====

    private fun speedOf(s: Int): Long {
        if (s == 1) {
            return clickMs
        }
        if (s == 2) {
            return patMs
        }
        return idleMs
    }

    private fun framesOf(s: Int): ArrayList<Int> {
        if (s == 1) {
            return clickFrames
        }
        if (s == 2) {
            return patFrames
        }
        return idleFrames
    }

    private fun hasAnim(s: Int): Boolean {
        return framesOf(s).isNotEmpty()
    }

    private fun setState(s: Int) {
        state = s
        stateIndex = 0
        showFrame()
    }

    private fun showFrame() {
        val list = framesOf(state)
        if (list.isEmpty()) {
            return
        }
        var i = stateIndex
        if (i >= list.size) {
            i = 0
        }
        val v = pet
        if (v != null) {
            v.setImageResource(list[i])
        }
    }

    private fun step() {
        val list = framesOf(state)
        if (list.size <= 1) {
            return
        }
        stateIndex = stateIndex + 1
        if (stateIndex >= list.size) {
            if (state == 0) {
                stateIndex = 0
            } else {
                setState(0)
                return
            }
        }
        showFrame()
    }

    @Suppress("DEPRECATION")
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
        state = 0
        stateIndex = 0
        showFrame()
        handler.post(frameTicker)
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
                startX = p.x
                startY = p.y
                moved = false
                pressed = true
                holdFired = false
                hideBubble()
                handler.postDelayed(holdTask, holdMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (abs(dx) > dp(10) || abs(dy) > dp(10)) {
                    moved = true
                    handler.removeCallbacks(holdTask)
                }
                if (moved) {
                    p.x = (startX + dx).toInt()
                    p.y = (startY + dy).toInt()
                    wm.updateViewLayout(v, p)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                handler.removeCallbacks(holdTask)
                if (holdFired) {
                    return true
                }
                if (moved) {
                    snapEdge()
                } else {
                    doClick()
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

    private fun doClick() {
        showBubble(clickLines[Random.nextInt(clickLines.size)])
        if (!hasAnim(1)) {
            return
        }
        setState(1)
    }

    private fun doPat() {
        showBubble(patLines[Random.nextInt(patLines.size)])
        if (!hasAnim(2)) {
            return
        }
        setState(2)
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
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
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
        loadDrawables("idle_", idleFrames)
        loadDrawables("click_", clickFrames)
        loadDrawables("pat_", patFrames)

        if (idleFrames.isEmpty()) {
            loadRetouch(idleFrames)
        }
        if (idleFrames.isEmpty()) {
            loadDrawables("pet_", idleFrames)
        }
        if (idleFrames.isEmpty()) {
            val s = resources.getIdentifier("pet", "drawable", packageName)
            if (s != 0) {
                idleFrames.add(s)
            } else {
                idleFrames.add(R.drawable.ic_launcher)
            }
        }
    }

    private fun loadDrawables(prefix: String, into: ArrayList<Int>) {
        into.clear()
        var i = 0
        while (i < 60) {
            val id = resources.getIdentifier(prefix + i, "drawable", packageName)
            if (id == 0) {
                break
            }
            into.add(id)
            i++
        }
    }

    private fun loadRetouch(into: ArrayList<Int>) {
        val names = ArrayList<String>()
        try {
            val cls = R.drawable::class.java
            val fs = cls.fields
            var i = 0
            while (i < fs.size) {
                val n = fs[i].name
                if (n.startsWith("retouch_")) {
                    names.add(n)
                }
                i++
            }
        } catch (e: Exception) {
            names.clear()
        }
        names.sort()
        var k = 0
        while (k < names.size) {
            val id = resources.getIdentifier(names[k], "drawable", packageName)
            if (id != 0) {
                into.add(id)
            }
            k++
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
