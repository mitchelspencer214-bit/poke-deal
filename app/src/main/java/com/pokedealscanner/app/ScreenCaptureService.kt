package com.pokedealscanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.pokedealscanner.app.parse.OcrLine

class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captured = false
    private var scanFinished = false
    @Volatile private var pricingInProgress = false

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
                    val raw = result.text.trim()
                    if (raw.isBlank()) {
                        finishWithResult("No readable text was found on this screen. Try a listing with visible title and description.")
                        return@addOnSuccessListener
                    }
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                        }
                    }
                    // Price lookups use the network: do them off the main thread, and keep
                    // this service alive until they're done.
                    scanFinished = true
                    pricingInProgress = true
                    val height = bitmap.height
                    Thread {
                        ScanPipeline.process(this, lines, height, "RAW SCREEN TEXT", raw)
                        handler.post { stopSelf() }
                    }.start()
                }
                .addOnFailureListener { error ->
                    finishWithResult(
                        "Text recognition failed: ${error.localizedMessage ?: "Unknown error"}. The OCR model may still be downloading."
                    )
                }
                .addOnCompleteListener {
                    recognizer.close()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (!pricingInProgress) stopSelf()
                }
        } catch (e: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            finishWithResult("Could not start text recognition: ${e.localizedMessage}")
            stopSelf()
        }
    }

    private fun finishWithResult(text: String, headline: String? = null) {
        if (scanFinished) return
        scanFinished = true
        ScanResults.publish(this, text, headline)
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


        private const val FOREGROUND_CHANNEL = "poke_deal_capture"
        private const val FOREGROUND_NOTIFICATION_ID = 2001
    }
}
