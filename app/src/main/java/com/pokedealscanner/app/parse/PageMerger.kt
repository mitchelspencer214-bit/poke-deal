package com.pokedealscanner.app.parse

import kotlin.math.max

/**
 * Stitches several scrolled "pages" of text lines into one tall page.
 *
 * Each page's coordinates are relative to the screen. Lines that appear on two
 * consecutive pages tell us how far the page scrolled; pinned elements (a top bar
 * or a "Send" footer) stay put and would say "didn't move", so we take the most
 * common non-zero shift.
 */
object PageMerger {

    fun merge(pages: List<List<OcrLine>>): List<OcrLine> {
        val out = mutableListOf<OcrLine>()
        val seen = HashMap<String, OcrLine>()
        var maxBottom = 0

        for (page in pages) {
            if (page.isEmpty()) continue
            val sorted = page.sortedWith(compareBy({ it.top }, { it.left }))

            val offset = if (out.isEmpty()) {
                0
            } else {
                val shifts = sorted.mapNotNull { l -> seen[key(l)]?.let { it.top - l.top } }
                val moving = shifts.filter { it != 0 }
                when {
                    moving.isNotEmpty() -> moving.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                    shifts.isNotEmpty() -> 0
                    else -> maxBottom + 20 - sorted.first().top
                }
            }

            for (l in sorted) {
                val k = key(l)
                if (k.isEmpty() || seen.containsKey(k)) continue
                val abs = l.copy(top = l.top + offset, bottom = l.bottom + offset)
                seen[k] = abs
                out += abs
                maxBottom = max(maxBottom, abs.bottom)
            }
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun key(l: OcrLine) = l.text.trim().lowercase()
}
