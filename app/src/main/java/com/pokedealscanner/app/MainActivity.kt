package com.pokedealscanner.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var scanResult: TextView
    private var pendingScanAfterNotification = false
    private var scanRequestInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshLastScan()
        updateStatus()

        // Only on a fresh launch, so rotation or recreation doesn't restart a scan.
        if (savedInstanceState == null && intent?.action == ACTION_SCAN) {
            startScanFlow()
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 40, 28, 24)
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
            setPadding(0, 12, 0, 22)
        }

        val scanButton = Button(this).apply {
            text = "Capture Screen & Read Listing"
            setOnClickListener { startScanFlow() }
        }

        val enableButton = Button(this).apply {
            text = "Enable Floating P Button"
            setOnClickListener { enableFloatingButton() }
        }

        val pageReaderButton = Button(this).apply {
            text = "Full-Page Reading Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop Floating Button"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingButtonService::class.java))
                status.text = "Floating button stopped."
            }
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 14)
        }

        val resultTitle = TextView(this).apply {
            text = "LAST SCAN"
            textSize = 18f
            setTextColor(Color.rgb(35, 94, 160))
            setPadding(0, 14, 0, 8)
        }

        scanResult = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.WHITE)
            text = "No scan yet. Open a Marketplace listing and tap the floating P button."
        }

        val resultScroll = ScrollView(this).apply {
            addView(scanResult)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(scanButton)
        root.addView(enableButton)
        root.addView(pageReaderButton)
        root.addView(stopButton)
        root.addView(status)
        root.addView(resultTitle)
        root.addView(
            resultScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun startScanFlow() {
        if (scanRequestInProgress) return

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingScanAfterNotification = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        if (scanRequestInProgress) return

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            scanRequestInProgress = true
            status.text = "Approve screen sharing to scan this listing."
            // Android 14+: force "Entire screen" so the user can't pick a single app.
            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }
            startActivityForResult(captureIntent, REQUEST_CAPTURE)
        } catch (e: Exception) {
            scanRequestInProgress = false
            status.text = "Could not start screen capture: ${e.localizedMessage}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATIONS && pendingScanAfterNotification) {
            pendingScanAfterNotification = false
            requestScreenCapture()
        }
    }

    @Deprecated("Uses the Android screen-capture consent activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE) return
        scanRequestInProgress = false

        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture was cancelled. No screen was scanned."
            return
        }

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            status.text = "Returning to your listing. Reading screen text..."
            moveTaskToBack(true)
        } catch (e: Exception) {
            status.text = "Could not start scanner: ${e.localizedMessage}"
        }
    }

    private fun enableFloatingButton() {
        if (!Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
            return
        }

        val serviceIntent = Intent(this, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        status.text = "Floating P button is running."
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val overlay = if (Settings.canDrawOverlays(this)) {
            "Floating button permission is enabled."
        } else {
            "Enable overlay permission to use the floating button."
        }
        val pageReader = if (PageReaderService.isEnabledInSettings(this)) {
            "Full-page reading is ON. Tapping P scrolls and reads the whole listing."
        } else {
            "Full-page reading is OFF (P captures one screen). To turn on: Full-Page Reading Settings > Installed apps > Poke Deal > On. " +
                "If it's greyed out: Settings > Apps > Poke Deal > ⋮ menu > Allow restricted settings, then try again."
        }
        status.text = "$overlay\n$pageReader"
    }

    private fun refreshLastScan() {
        if (!::scanResult.isInitialized) return

        val prefs = getSharedPreferences(ScanResults.PREFS_NAME, MODE_PRIVATE)
        val text = prefs.getString(ScanResults.PREFS_TEXT, null)
        val timestamp = prefs.getLong(ScanResults.PREFS_TIME, 0L)

        scanResult.text = if (text.isNullOrBlank()) {
            "No scan yet. Open a Marketplace listing and tap the floating P button."
        } else {
            val date = if (timestamp > 0L) {
                DateFormat.getDateTimeInstance().format(Date(timestamp))
            } else {
                "Unknown time"
            }
            "Scanned $date\n\n$text"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        when (intent.action) {
            ACTION_SCAN -> startScanFlow()
            ACTION_SHOW_RESULT -> refreshLastScan()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshLastScan()
    }

    companion object {
        const val ACTION_SCAN = "com.pokedealscanner.app.ACTION_SCAN"
        const val ACTION_SHOW_RESULT = "com.pokedealscanner.app.ACTION_SHOW_RESULT"
        private const val REQUEST_CAPTURE = 9001
        private const val REQUEST_NOTIFICATIONS = 9002
    }
}
