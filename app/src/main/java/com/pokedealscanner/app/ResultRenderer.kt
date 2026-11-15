package com.pokedealscanner.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Draws a saved scan as easy-to-scan cards. */
class ResultRenderer(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun render(json: String, scannedAt: String): View {
        val o = JSONObject(json)
        val col = vertical()

        // Header: title + main price
        col.addView(card {
            addView(text(scannedAt, 12f, MUTED))
            addView(text(o.optString("title"), 20f, INK, bold = true).apply { setPadding(0, dp(4), 0, dp(8)) })
            val row = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
            o.optStringOrNull("mainPrice")?.let { row.addView(text(it, 28f, BRAND, bold = true).apply { setPadding(0, 0, dp(12), 0) }) }
            o.optStringOrNull("mainMeaning")?.let { row.addView(pill(it, NEUTRAL_BG, MUTED)) }
            addView(row)
            o.optJSONObject("mainCheck")?.let { addView(checkRow(it).apply { setPadding(0, dp(10), 0, 0) }) }
            o.optStringOrNull("mainReason")?.let { addView(text(it, 12f, MUTED).apply { setPadding(0, dp(6), 0, 0) }) }
            val sets = o.optJSONArray("sets").strings()
            if (sets.isNotEmpty()) addView(chips(sets).apply { setPadding(0, dp(10), 0, 0) })
        })

        // Warnings
        o.optJSONArray("warnings").strings().forEach { w ->
            col.addView(banner("⚠  $w", WARN_BG, WARN_INK))
        }

        // Deal summary
        o.optJSONObject("verdictCounts")?.let { counts ->
            val row = horizontal().apply { setPadding(dp(2), dp(4), 0, dp(4)) }
            listOf("GOOD_DEAL", "FAIR", "OVERPRICED").forEach { v ->
                val n = counts.optInt(v, 0)
                if (n > 0) row.addView(pill("$n ${LABEL[v]}", BG[v]!!, FG[v]!!))
            }
            if (row.childCount > 0) col.addView(row)
        }

        // Items
        val items = o.optJSONArray("items") ?: JSONArray()
        if (items.length() > 0) {
            col.addView(sectionLabel("ITEMS (${items.length()})"))
            for (i in 0 until items.length()) col.addView(itemCard(items.getJSONObject(i)))
            o.optStringOrNull("lotValue")?.let { v ->
                val partial = o.optStringOrNull("lotPartial")?.let { " ($it)" } ?: ""
                col.addView(card {
                    val row = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
                    row.addView(text("Market value of everything listed$partial", 14f, MUTED), weighted())
                    row.addView(text(v, 18f, INK, bold = true))
                    addView(row)
                })
            }
        } else {
            col.addView(card { addView(text("No items found in the description.", 14f, MUTED)) })
        }

        // Seller notes (collapsed)
        val notes = o.optJSONArray("notes").strings()
        if (notes.isNotEmpty()) {
            col.addView(collapsible("Seller notes (${notes.size})", notes.joinToString("\n\n") { "• $it" }))
        }

        // Scanned text (collapsed)
        col.addView(collapsible("Show scanned text", o.optString("raw"), mono = true))

        o.optStringOrNull("pricesUpdated")?.let {
            col.addView(text("Sealed prices updated $it · Cards: TCGplayer market via TCGdex", 11f, MUTED).apply {
                setPadding(dp(4), dp(8), dp(4), dp(16))
            })
        }
        return col
    }

    /** Plain-text result (old scans and errors). */
    fun renderPlain(textBody: String, scannedAt: String): View = vertical().apply {
        addView(card {
            addView(text(scannedAt, 12f, MUTED))
            addView(text(textBody, 14f, INK).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, 0) })
        })
    }

    // ---------- Pieces ----------

    private fun itemCard(item: JSONObject): View = card {
        val check = item.optJSONObject("check")
        val top = horizontal().apply { gravity = Gravity.TOP }
        val left = vertical()
        left.addView(text(item.optString("name"), 16f, INK, bold = true))
        val details = item.optJSONArray("details").strings()
        if (details.isNotEmpty()) left.addView(chips(details).apply { setPadding(0, dp(6), 0, 0) })
        top.addView(left, weighted())

        val right = vertical().apply { gravity = Gravity.END }
        right.addView(text(item.optStringOrNull("ask") ?: "no price", 18f, if (item.has("ask") && !item.isNull("ask")) INK else MUTED, bold = true).apply { gravity = Gravity.END })
        right.addView(text("asking", 11f, MUTED).apply { gravity = Gravity.END })
        top.addView(right)
        addView(top)

        if (check != null) addView(checkRow(check).apply { setPadding(0, dp(10), 0, 0) })
    }

    /** "Market $103.18   [GOOD DEAL]  12% under market" */
    private fun checkRow(c: JSONObject): View = vertical().apply {
        val market = c.optStringOrNull("market")
        val verdict = c.optStringOrNull("verdict")
        if (market != null) {
            val row = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text("Market $market", 14f, INK), weighted())
            if (verdict != null) {
                row.addView(pill(LABEL[verdict] ?: verdict, BG[verdict] ?: NEUTRAL_BG, FG[verdict] ?: INK).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = 0 })
            }
            addView(row)
            c.optStringOrNull("verdictText")?.takeIf { it.isNotBlank() }?.let {
                addView(text(it, 12f, verdict?.let { v -> FG[v] } ?: MUTED).apply {
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
            c.optStringOrNull("note")?.let { addView(text(it, 12f, MUTED).apply { setPadding(0, dp(4), 0, 0) }) }
        } else {
            c.optStringOrNull("problem")?.let { addView(text("No market price: $it", 12f, MUTED)) }
        }
    }

    private fun collapsible(title: String, body: String, mono: Boolean = false): View = card {
        val header = text("▸  $title", 14f, BRAND, bold = true)
        val content = text(body, if (mono) 12f else 14f, INK).apply {
            visibility = View.GONE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
            if (mono) typeface = Typeface.MONOSPACE
        }
        header.setOnClickListener {
            val open = content.visibility == View.VISIBLE
            content.visibility = if (open) View.GONE else View.VISIBLE
            header.text = (if (open) "▸  " else "▾  ") + title
        }
        addView(header)
        addView(content)
    }

    private fun banner(msg: String, bg: Int, fg: Int): View = text(msg, 14f, fg, bold = true).apply {
        background = rounded(bg, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = marginParams()
    }

    private fun sectionLabel(s: String) = text(s, 13f, MUTED, bold = true).apply { setPadding(dp(4), dp(14), 0, dp(6)) }

    private fun chips(values: List<String>): View {
        // Simple wrapping: one row per ~3 chips keeps it readable on phones.
        val col = vertical()
        values.chunked(3).forEach { chunk ->
            val row = horizontal().apply { setPadding(0, 0, 0, dp(4)) }
            chunk.forEach { row.addView(pill(it, NEUTRAL_BG, MUTED, small = true)) }
            col.addView(row)
        }
        return col
    }

    private fun pill(s: String, bg: Int, fg: Int, small: Boolean = false): TextView =
        text(s, if (small) 11f else 12f, fg, bold = true).apply {
            background = rounded(bg, 50)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
        }

    private fun card(build: LinearLayout.() -> Unit): View = vertical().apply {
        background = rounded(Color.WHITE, 14).apply { setStroke(dp(1), BORDER) }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = marginParams()
        build()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun vertical() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun marginParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

    companion object {
        val BRAND = Color.rgb(35, 94, 160)
        val INK = Color.rgb(32, 33, 36)
        val MUTED = Color.rgb(95, 99, 104)
        val BORDER = Color.rgb(225, 228, 232)
        val NEUTRAL_BG = Color.rgb(241, 243, 244)
        val WARN_BG = Color.rgb(254, 247, 224)
        val WARN_INK = Color.rgb(176, 96, 0)

        val LABEL = mapOf("GOOD_DEAL" to "GOOD DEAL", "FAIR" to "FAIR", "OVERPRICED" to "OVERPRICED")
        val BG = mapOf(
            "GOOD_DEAL" to Color.rgb(230, 244, 234),
            "FAIR" to Color.rgb(232, 240, 254),
            "OVERPRICED" to Color.rgb(252, 232, 230),
        )
        val FG = mapOf(
            "GOOD_DEAL" to Color.rgb(24, 128, 56),
            "FAIR" to Color.rgb(25, 103, 210),
            "OVERPRICED" to Color.rgb(197, 34, 31),
        )
    }
}
