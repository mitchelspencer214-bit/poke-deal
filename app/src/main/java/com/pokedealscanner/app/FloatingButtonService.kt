package com.pokedealscanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import java.lang.ref.WeakReference

class FloatingButtonService : Service() {
    private var floatingButton: Button? = null
    private var windowManager: WindowManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Poke Deal is running")
            .setContentText("Tap the floating P button to scan a listing.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        showFloatingButton()
        activeInstance = WeakReference(this)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Poke Deal floating scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val button = Button(this).apply {
            text = "P"
            textSize = 22f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(35, 94, 160))
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                // Full-page reading if it's turned on; otherwise screen capture.
                if (PageReaderService.scan()) return@setOnClickListener
                val openScanner = Intent(this@FloatingButtonService, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SCAN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(openScanner)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }

        windowManager?.addView(button, params)
        floatingButton = button
    }

    private fun setButtonBusy(busy: Boolean) {
        floatingButton?.apply {
            text = if (busy) "…" else "P"
            isEnabled = !busy
        }
    }

    private fun setButtonVisible(visible: Boolean) {
        floatingButton?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) activeInstance = null
        floatingButton?.let {
            windowManager?.removeView(it)
        }
        floatingButton = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "poke_deal_scanner"
        private const val NOTIFICATION_ID = 1001

        private var activeInstance: WeakReference<FloatingButtonService>? = null

        /** Hides the P button so it doesn't end up in the screenshot. Call on the main thread. */
        fun setVisible(visible: Boolean) {
            activeInstance?.get()?.setButtonVisible(visible)
        }

        /** Shows "…" on the button while a full-page scan runs. Call on the main thread. */
        fun setBusy(busy: Boolean) {
            activeInstance?.get()?.setButtonBusy(busy)
        }
    }
}
