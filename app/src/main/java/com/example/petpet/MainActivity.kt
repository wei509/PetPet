package com.example.petpet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnStart).setOnClickListener { startFlow() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, PetService::class.java))
            Toast.makeText(this, "桌宠已收起", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (pendingStart && canDrawOverlay()) {
            pendingStart = false
            launchPet()
        }
    }

    private fun startFlow() {
        if (!canDrawOverlay()) {
            pendingStart = true
            Toast.makeText(this, "请打开「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        } else {
            launchPet()
        }
    }

    private fun launchPet() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        val i = Intent(this, PetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
        Toast.makeText(this, "桌宠出来啦！拖一拖、点一下试试", Toast.LENGTH_LONG).show()
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

    private fun refreshStatus() {
        tvStatus.text = if (canDrawOverlay()) {
            "✅ 悬浮窗权限：已开启\n点下面的按钮叫TA出来吧"
        } else {
            "⚠️ 还没开「显示在其他应用上层」权限\n点启动会自动带你去开"
        }
    }
}
