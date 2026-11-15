package com.pokedealscanner.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var setupToggle: TextView
    private lateinit var setupHelp: TextView
    private lateinit var readerLog: TextView
    private lateinit var resultContainer: LinearLayout
    private var setupOpenedByUser = false
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "POKE DEAL"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ResultRenderer.BRAND)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        setupToggle = TextView(this).apply {
            text = "⚙ Setup"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ResultRenderer.BRAND)
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener {
                setupOpenedByUser = setupBox.visibility != View.VISIBLE
                setupBox.visibility = if (setupOpenedByUser) View.VISIBLE else View.GONE
            }
        }
        header.addView(setupToggle)
        column.addView(header)

        status = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(12))
        }
        column.addView(status)

        // Setup (folds away once everything is on)
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), ResultRenderer.BORDER)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        setupHelp = TextView(this).apply {
            textSize = 13f
            setTextColor(ResultRenderer.MUTED)
            setPadding(0, 0, 0, dp(8))
        }
        setupBox.addView(setupHelp)
        setupBox.addView(Button(this).apply {
            text = "Enable Floating P Button"
            setOnClickListener { enableFloatingButton() }
        })
        setupBox.addView(Button(this).apply {
            text = "Full-Page Reading Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setupBox.addView(Button(this).apply {
            text = "Capture Screen & Read Listing"
            setOnClickListener { startScanFlow() }
        })
        setupBox.addView(Button(this).apply {
            text = "Stop Floating Button"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingButtonService::class.java))
                status.text = "Floating button stopped."
            }
        })
        readerLog = TextView(this).apply {
            textSize = 11f
            setTextColor(ResultRenderer.MUTED)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        setupBox.addView(readerLog)
        column.addView(setupBox)

        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(resultContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 250))
            isFillViewport = true
            addView(column)
        })
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
        val overlayOn = Settings.canDrawOverlays(this)
        val readerOn = PageReaderService.isEnabledInSettings(this)
        val readerConnected = readerOn && PageReaderService.isActive
        val ready = overlayOn && readerConnected

        status.text = when {
            ready -> "● Ready. Open a listing and tap P."
            !overlayOn -> "● Setup needed: allow the floating P button."
            readerOn -> "● Full-page reading isn't connected. Turn it off and on again."
            else -> "● Full-page reading is off. P will only capture one screen."
        }
        status.setTextColor(if (ready) Color.rgb(24, 128, 56) else ResultRenderer.WARN_INK)

        setupHelp.text = buildString {
            append(if (overlayOn) "✓ Floating P button allowed\n" else "✗ Floating P button: tap Enable Floating P Button\n")
            append(
                when {
                    readerConnected -> "✓ Full-page reading on"
                    readerOn -> "✗ Full-page reading on but not connected: turn it off and on in Full-Page Reading Settings"
                    else -> "✗ Full-page reading off: Full-Page Reading Settings > Installed apps > Poke Deal > On. " +
                        "If greyed out: Settings > Apps > Poke Deal > ⋮ > Allow restricted settings"
                }
            )
        }
        if (!setupOpenedByUser) setupBox.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun refreshLastScan() {
        if (!::resultContainer.isInitialized) return

        val prefs = getSharedPreferences(ScanResults.PREFS_NAME, MODE_PRIVATE)
        val text = prefs.getString(ScanResults.PREFS_TEXT, null)
        val json = prefs.getString(ScanResults.PREFS_JSON, null)
        val timestamp = prefs.getLong(ScanResults.PREFS_TIME, 0L)
        val scannedAt = if (timestamp > 0L) "Scanned " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)) else ""

        readerLog.text = prefs.getString(ScanResults.PREFS_READER_LOG, null)?.let { "Last full-page read: $it" } ?: ""

        resultContainer.removeAllViews()
        val renderer = ResultRenderer(this)
        val view = when {
            json != null -> runCatching { renderer.render(json, scannedAt) }.getOrNull()
                ?: renderer.renderPlain(text.orEmpty(), scannedAt)
            !text.isNullOrBlank() -> renderer.renderPlain(text, scannedAt)
            else -> renderer.renderPlain("No scan yet. Open a Marketplace listing and tap the floating P button.", "")
        }
        resultContainer.addView(view)
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
