package com.pokedealscanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captured = false
    private var scanFinished = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!scanFinished) {
                finishWithResult("Screen sharing stopped before the scan could finish.")
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannels()
        startForegroundForCapture()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

        @Suppress("DEPRECATION")
        val consentData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || consentData == null) {
            finishWithResult("Screen capture permission was missing. Please try again.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, consentData)
            projection?.registerCallback(projectionCallback, handler)

            // Hide the P button, then give Android time to return to the
            // Marketplace screen (and redraw without the button) before capturing.
            FloatingButtonService.setVisible(false)
            handler.postDelayed({ createVirtualDisplay() }, 900L)
        } catch (e: Exception) {
            finishWithResult("Screen capture could not start: ${e.localizedMessage}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL,
                "Poke Deal screen scanning",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL,
                "Poke Deal scan results",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun startForegroundForCapture() {
        val notification = Notification.Builder(this, FOREGROUND_CHANNEL)
            .setContentTitle("Poke Deal is scanning")
            .setContentText("Reading the listing text from your shared screen.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun createVirtualDisplay() {
        if (scanFinished) return

        try {
            val activeProjection = projection
                ?: throw IllegalStateException("Screen sharing session is unavailable.")

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                if (captured || scanFinished) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                captured = true
                val bitmap = try {
                    imageToBitmap(image)
                } catch (_: Exception) {
                    null
                } finally {
                    image.close()
                }

                releaseDisplayOnly()

                if (bitmap == null) {
                    finishWithResult("The screen image could not be read. Please try again.")
                    stopSelf()
                } else {
                    recognizeText(bitmap)
                }
            }, handler)

            virtualDisplay = activeProjection.createVirtualDisplay(
                "PokeDealScreenScan",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (e: Exception) {
            finishWithResult("Screen capture failed: ${e.localizedMessage}")
            stopSelf()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        return if (paddedWidth == image.width) {
            paddedBitmap
        } else {
            val cropped = Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                image.width,
                image.height
            )
            paddedBitmap.recycle()
            cropped
        }
    }

    private fun releaseDisplayOnly() {
        FloatingButtonService.setVisible(true)
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
    }

    private fun recognizeText(bitmap: Bitmap) {
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    finishWithResult(
                        if (text.isBlank()) {
                            "No readable text was found on this screen. Try a listing with visible title and description."
                        } else {
                            text
                        }
                    )
                }
                .addOnFailureListener { error ->
                    finishWithResult(
                        "Text recognition failed: ${error.localizedMessage ?: "Unknown error"}. The OCR model may still be downloading."
                    )
                }
                .addOnCompleteListener {
                    recognizer.close()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    stopSelf()
                }
        } catch (e: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            finishWithResult("Could not start text recognition: ${e.localizedMessage}")
            stopSelf()
        }
    }

    private fun finishWithResult(text: String) {
        if (scanFinished) return
        scanFinished = true

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREFS_TEXT, text)
            .putLong(PREFS_TIME, System.currentTimeMillis())
            .apply()

        val showResult = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_RESULT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2202,
            showResult,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val preview = text.replace('\n', ' ').take(110)
        val notification = Notification.Builder(this, RESULT_CHANNEL)
            .setContentTitle("Poke Deal scan complete")
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(text.take(2500)))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(RESULT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        releaseDisplayOnly()

        val activeProjection = projection
        projection = null
        if (activeProjection != null) {
            try {
                activeProjection.unregisterCallback(projectionCallback)
                activeProjection.stop()
            } catch (_: Exception) {
                // Projection may already have been stopped by Android.
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"

        const val PREFS_NAME = "poke_deal_scan"
        const val PREFS_TEXT = "last_scan_text"
        const val PREFS_TIME = "last_scan_time"

        private const val FOREGROUND_CHANNEL = "poke_deal_capture"
        private const val RESULT_CHANNEL = "poke_deal_results"
        private const val FOREGROUND_NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
    }
}
