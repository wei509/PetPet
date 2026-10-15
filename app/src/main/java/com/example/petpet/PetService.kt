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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
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

    // ★ 序列帧切换速度（毫秒/帧）
    private val idleMs = 300L
    private val clickMs = 130L
    private val patMs = 170L

    // ★ 按住多久算"摸头"（毫秒）
    private val holdMs = 600L

    // ★ 音效音量（0.0 ~ 1.0）
    private val sndVolume = 0.35f

    // 状态：0=待机  1=被点击  2=被摸头
    private var state = 0
    private var stateIndex = 0

    private lateinit var wm: WindowManager
    private var pet: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var hideTask: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())

    private val idleDraws = ArrayList<Drawable>()
    private val clickDraws = ArrayList<Drawable>()
    private val patDraws = ArrayList<Drawable>()

    private var soundPool: SoundPool? = null
    private val clickSounds = ArrayList<Int>()
    private val patSounds = ArrayList<Int>()

    private var popAt = 0L
    private var patAt = 0L

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

    private val animTicker = object : Runnable {
        override fun run() {
            draw()
            handler.postDelayed(this, 40)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createSoundPool()
        loadAll()
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
        val sp = soundPool
        if (sp != null) {
            runCatching { sp.release() }
        }
        soundPool = null
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

    private fun drawsOf(s: Int): ArrayList<Drawable> {
        if (s == 1) {
            return clickDraws
        }
        if (s == 2) {
            return patDraws
        }
        return idleDraws
    }

    private fun setState(s: Int) {
        state = s
        stateIndex = 0
        showFrame()
    }

    private fun showFrame() {
        val list = drawsOf(state)
        if (list.isEmpty()) {
            return
        }
        var i = stateIndex
        if (i >= list.size) {
            i = 0
        }
        val v = pet
        if (v != null) {
            v.setImageDrawable(list[i])
        }
    }

    private fun step() {
        val list = drawsOf(state)
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

    private fun draw() {
        val v = pet
        if (v == null) {
            return
        }
        val now = System.currentTimeMillis()
        val t = now / 1000.0

        var sx = 1.0 + 0.018 * sin(t * 1.8)
        var sy = sx
        var ty = (sin(t * 1.5) * dp(3)).toFloat()
        var rot = 0f

        if (popAt > 0L) {
            val e = (now - popAt) / 340.0
            if (e >= 1.0) {
                popAt = 0L
            } else {
                val k = 1.0 + 0.20 * sin(e * PI)
                sx *= k
                sy *= k
                ty -= (dp(10) * sin(e * PI)).toFloat()
            }
        }

        if (patAt > 0L) {
            val e = (now - patAt) / 900.0
            if (e >= 1.0) {
                patAt = 0L
            } else {
                rot = (5.0 * sin(e * PI * 3)).toFloat()
                sx *= 1.0 + 0.05 * sin(e * PI * 2)
                sy *= 1.0 - 0.04 * sin(e * PI * 2)
            }
        }

        v.scaleX = sx.toFloat()
        v.scaleY = sy.toFloat()
        v.translationY = ty
        v.rotation = rot
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

        val first = drawsOf(0)
        if (first.isNotEmpty()) {
            iv.setImageDrawable(first[0])
        }

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
        handler.post(frameTicker)
        handler.post(animTicker)
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
        playSound(clickSounds)
        popAt = System.currentTimeMillis()
        showBubble(clickLines[Random.nextInt(clickLines.size)])
        if (clickDraws.isNotEmpty()) {
            setState(1)
        }
    }

    private fun doPat() {
        playSound(patSounds)
        patAt = System.currentTimeMillis()
        showBubble(patLines[Random.nextInt(patLines.size)])
        if (patDraws.isNotEmpty()) {
            setState(2)
        }
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

    private fun loadAll() {
        loadDrawables("idle_", idleDraws)
        loadDrawables("click_", clickDraws)
        loadDrawables("pat_", patDraws)

        if (idleDraws.isEmpty()) {
            loadRetouch(idleDraws)
        }
        if (idleDraws.isEmpty()) {
            loadDrawables("pet_", idleDraws)
        }
        if (idleDraws.isEmpty()) {
            val d = getDrawableSafely(R.drawable.ic_launcher)
            if (d != null) {
                idleDraws.add(d)
            }
        }
    }

    private fun loadDrawables(prefix: String, into: ArrayList<Drawable>) {
        into.clear()
        var i = 0
        while (i < 60) {
            val id = resources.getIdentifier(prefix + i, "drawable", packageName)
            if (id == 0) {
                break
            }
            val d = getDrawableSafely(id)
            if (d != null) {
                into.add(d)
            }
            i++
        }
    }

    private fun loadRetouch(into: ArrayList<Drawable>) {
        val names = ArrayList<String>()
        try {
            val fs = R.drawable::class.java.fields
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
                val d = getDrawableSafely(id)
                if (d != null) {
                    into.add(d)
                }
            }
            k++
        }
    }

    private fun getDrawableSafely(id: Int): Drawable? {
        return try {
            resources.getDrawable(id, null)
        } catch (e: Exception) {
            null
        }
    }

    private fun createSoundPool() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build()
            soundPool = sp
        } catch (e: Exception) {
            soundPool = null
        }
    }

    private fun loadSounds() {
        loadRaw("snd_click_", clickSounds)
        loadRaw("snd_pat_", patSounds)
        if (clickSounds.isEmpty()) {
            loadRaw("tap_", clickSounds)
        }
    }

    private fun loadRaw(prefix: String, into: ArrayList<Int>) {
        into.clear()
        val sp = soundPool ?: return
        var i = 0
        while (i < 30) {
            val id = resources.getIdentifier(prefix + i, "raw", packageName)
            if (id == 0) {
                break
            }
            val sid = sp.load(this, id, 1)
            if (sid != 0) {
                into.add(sid)
            }
            i++
        }
    }

    private fun playSound(list: ArrayList<Int>) {
        var pool = list
        if (pool.isEmpty()) {
            pool = clickSounds
        }
        if (pool.isEmpty()) {
            return
        }
        val sp = soundPool ?: return
        val sid = pool[Random.nextInt(pool.size)]
        runCatching {
            sp.play(sid, sndVolume, sndVolume, 1, 0, 1f)
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
            .setContentDescription("桌宠")
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
