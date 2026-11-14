package com.pokedealscanner.app

import android.app.Activity
import android.content.Intent
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 64, 32, 32)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }

        val title = TextView(this).apply {
            text = "POKE DEAL"
            textSize = 30f
            setTextColor(Color.rgb(35, 94, 160))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Your Pokémon marketplace scanner"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        val enableButton = Button(this).apply {
            text = "Enable Floating P Button"
            setOnClickListener { enableFloatingButton() }
        }

        val stopButton = Button(this).apply {
            text = "Stop Floating Button"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingButtonService::class.java))
                status.text = "Floating button stopped."
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(enableButton)
        root.addView(stopButton)
        root.addView(status)

        setContentView(root)
        updateStatus()
    }

    private fun enableFloatingButton() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val intent = Intent(this, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        status.text = "Your floating P button is running."
    }

    private fun updateStatus() {
        if (::status.isInitialized) {
            status.text = if (Settings.canDrawOverlays(this)) {
                "Overlay permission is enabled. Start your floating button!"
            } else {
                "Enable overlay permission to use the floating button."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
