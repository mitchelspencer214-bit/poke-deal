package com.pokedealscanner.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pokedealscanner.app.parse.ListingFormatter
import com.pokedealscanner.app.parse.ListingParser
import com.pokedealscanner.app.parse.OcrLine
import com.pokedealscanner.app.parse.PageMerger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full-page reader. When the user taps P, this reads the listing's text straight
 * from the app on screen (no OCR), taps "See more", scrolls down until the
 * description ends, then scrolls back up.
 *
 * It does nothing until P is tapped: no events are processed in the background.
 */
class PageReaderService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var scanning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        FloatingButtonService.setBusy(true)

        Thread {
            val lines = try { readPage() } catch (_: Exception) { null }
            main.post {
                scanning = false
                FloatingButtonService.setBusy(false)
                handle(lines)
            }
        }.start()
    }

    private fun handle(lines: List<OcrLine>?) {
        val usable = lines != null && lines.size >= 3 && lines.any { it.text.contains('$') || it.text.length > 25 }
        if (!usable) {
            // This app hides its text. Fall back to screen capture + OCR.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SCAN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            return
        }
        val pageText = lines!!.joinToString("\n") { it.text }
        try {
            val parsed = ListingParser.parse(lines, 0)
            ScanResults.publish(
                this,
                ListingFormatter.summary(parsed) + "\n\n----- PAGE TEXT (read directly, full page) -----\n" + pageText,
                ListingFormatter.headline(parsed),
            )
        } catch (e: Exception) {
            ScanResults.publish(this, "Couldn't organize this listing (${e.localizedMessage}).\n\n$pageText")
        }
    }

    // ---------- Reading ----------

    /** Runs on a worker thread. */
    private fun readPage(): List<OcrLine>? {
        val first = rootInActiveWindow ?: return null
        if (first.packageName?.toString() == packageName) return null

        val pages = mutableListOf<List<OcrLine>>()
        var scrolls = 0
        var clickedSeeMore = false
        var lastSignature = ""

        var step = 0
        while (step < MAX_PAGES) {
            step++
            val root = rootInActiveWindow ?: break
            val lines = collect(root)

            if (!clickedSeeMore) {
                val seeMore = findSeeMore(root)
                if (seeMore != null && click(seeMore)) {
                    clickedSeeMore = true
                    Thread.sleep(SETTLE_MS)
                    continue
                }
            }

            pages += lines
            val signature = lines.joinToString("|") { it.text }
            if (signature == lastSignature) break // didn't move: bottom of page
            lastSignature = signature

            if (pastDescription(lines)) break
            if (!scroll(root, forward = true)) break
            scrolls++
            Thread.sleep(SETTLE_MS)
        }

        // Put the user back where they started.
        repeat(scrolls) {
            rootInActiveWindow?.let { scroll(it, forward = false) }
            Thread.sleep(250)
        }

        return PageMerger.merge(pages)
    }

    private fun collect(root: AccessibilityNodeInfo): List<OcrLine> {
        val out = mutableListOf<OcrLine>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val bounds = Rect()

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }

            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() && node.childCount == 0 && !isImageDescription(it) }
                ?: continue

            node.getBoundsInScreen(bounds)
            val parts = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val lineHeight = ((bounds.height()) / parts.size.coerceAtLeast(1)).coerceAtLeast(1)
            parts.forEachIndexed { i, part ->
                val top = bounds.top + i * lineHeight
                val key = "$part@$top"
                if (seen.add(key)) out += OcrLine(part, bounds.left, top, bounds.right, top + lineHeight)
            }
        }
        return out
    }

    private fun isImageDescription(s: String): Boolean {
        val l = s.lowercase()
        return IMAGE_PREFIXES.any { l.startsWith(it) }
    }

    private fun findSeeMore(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText("See more")
            .firstOrNull { it.isVisibleToUser && (it.text ?: it.contentDescription)?.toString()?.trim()?.lowercase()?.let { t -> t == "see more" || t.endsWith("see more") } == true }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private fun pastDescription(lines: List<OcrLine>): Boolean =
        lines.any { l -> val t = l.text.lowercase(); END_MARKERS.any { t.startsWith(it) } }

    private fun scroll(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        largestScrollable(root)?.let { if (it.performAction(action)) return true }
        return swipe(forward)
    }

    private fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isScrollable && node.isVisibleToUser) {
                node.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { best = node; bestArea = area }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    /** Fallback when the app doesn't expose a scrollable view: a finger swipe. */
    private fun swipe(forward: Boolean): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val low = m.heightPixels * 0.75f
        val high = m.heightPixels * 0.30f
        val path = Path().apply {
            moveTo(x, if (forward) low else high)
            lineTo(x, if (forward) high else low)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        val done = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) { done.countDown() }
        }, main)
        if (!sent) return false
        done.await(2, TimeUnit.SECONDS)
        return ok
    }

    companion object {
        private const val MAX_PAGES = 8
        private const val SETTLE_MS = 650L

        private val END_MARKERS = listOf(
            "seller information", "seller details", "today's picks", "location is approximate",
            "related listings", "more from this seller", "similar items",
        )
        private val IMAGE_PREFIXES = listOf("may be an image", "may be a photo", "photo of", "image of", "profile picture", "no photo description")

        @Volatile private var instance: PageReaderService? = null

        /** True when the user has turned on full-page reading in Accessibility settings. */
        val isActive: Boolean get() = instance != null

        /** Starts a full-page scan. Returns false if full-page reading is off. */
        fun scan(): Boolean {
            val svc = instance ?: return false
            svc.startScan()
            return true
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.contains("${context.packageName}/") && it.contains("PageReaderService") }
        }
    }
}
