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
import android.view.accessibility.AccessibilityWindowInfo
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
            ScanResults.logReader(
                this,
                lastProblem ?: "Read ${lines?.size ?: 0} text items from $lastPackage, not enough for a listing. " +
                    "Used screen capture. First items: ${lines.orEmpty().take(12).joinToString(" | ") { it.text.take(40) }}",
            )
            // This app hides its text. Fall back to screen capture + OCR.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SCAN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            return
        }
        ScanResults.logReader(this, "Worked. Read ${lines!!.size} text items from $lastPackage over $lastPages screen(s). Steps: $lastTrail.")
        val pageText = lines.joinToString("\n") { it.text }
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
    @Volatile private var lastProblem: String? = null
    @Volatile private var lastPackage: String = "?"
    @Volatile private var lastPages = 0
    @Volatile private var lastSeeMore = false
    @Volatile private var lastTrail = ""

    /** The app window under the P button (Facebook), never our own overlay. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val candidates = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { w -> w.root?.let { r -> w to r } }
                .filter { (_, r) -> r.packageName?.toString() != packageName }
        } catch (_: Exception) { emptyList() }
        val rect = Rect()
        val best = candidates.maxByOrNull { (w, _) ->
            w.getBoundsInScreen(rect)
            (if (w.isActive || w.isFocused) 1_000_000_000L else 0L) + rect.width().toLong() * rect.height()
        }?.second
        if (best != null) return best
        return rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }

    private fun readPage(): List<OcrLine>? {
        lastProblem = null; lastPages = 0; lastSeeMore = false; lastTrail = ""
        val first = targetRoot()
        if (first == null) {
            lastProblem = "Couldn't see the app behind the P button (Android returned no window). Used screen capture."
            return null
        }
        lastPackage = first.packageName?.toString() ?: "?"

        val pages = mutableListOf<List<OcrLine>>()
        val moves = mutableListOf<Boolean>() // true = finger swipe, false = scroll command
        val trail = mutableListOf<String>()
        var clickedSeeMore = false
        var lastSignature = ""
        var useSwipe = false

        var step = 0
        while (step < MAX_PAGES) {
            step++
            val root = targetRoot() ?: break
            val lines = collect(root)

            if (!clickedSeeMore) {
                val seeMore = findSeeMore(root)
                if (seeMore != null && click(seeMore)) {
                    clickedSeeMore = true
                    lastSeeMore = true
                    trail += "tapped See more"
                    Thread.sleep(SETTLE_MS)
                    continue
                }
            }

            val signature = lines.joinToString("|") { it.text }
            if (signature == lastSignature) {
                // The last move didn't change anything on screen.
                if (!useSwipe) {
                    // Scroll command was ignored (common in Facebook). Undo nothing; switch to a finger swipe.
                    useSwipe = true
                    moves.removeAt(moves.lastIndex)
                    trail += "scroll command didn't move the page, switching to swipe"
                    if (swipe(forward = true)) { moves += true; Thread.sleep(SWIPE_SETTLE_MS); continue }
                }
                trail += "reached the bottom"
                break
            }
            lastSignature = signature
            pages += lines
            lastPages = pages.size
            trail += "screen ${pages.size}: ${lines.size} items"

            if (pastDescription(lines)) { trail += "passed the description"; break }

            val moved = if (useSwipe) swipe(forward = true) else scrollCommand(root, forward = true)
            if (!moved) {
                if (!useSwipe && swipe(forward = true)) { useSwipe = true; moves += true; Thread.sleep(SWIPE_SETTLE_MS); continue }
                trail += "couldn't scroll"
                break
            }
            moves += useSwipe
            Thread.sleep(if (useSwipe) SWIPE_SETTLE_MS else SETTLE_MS)
        }
        lastTrail = trail.joinToString("; ")

        // Put the user back where they started.
        for (wasSwipe in moves.asReversed()) {
            if (wasSwipe) swipe(forward = false) else targetRoot()?.let { scrollCommand(it, forward = false) }
            Thread.sleep(300)
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
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() && !isImageDescription(it) }
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
        // A container's label often repeats all of its children's text in one long line. Drop those.
        return out.filter { line ->
            line.text.length < 40 || out.count { other -> other !== line && other.text.length >= 3 && line.text.contains(other.text) } < 2
        }
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

    private fun scrollCommand(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return largestScrollable(root)?.performAction(action) == true
    }

    private fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val cls = node.className?.toString().orEmpty()
            if (node.isScrollable && node.isVisibleToUser && !cls.contains("Pager") && !cls.contains("Horizontal")) {
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
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
        private const val SWIPE_SETTLE_MS = 900L

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
