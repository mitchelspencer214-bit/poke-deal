package com.pokedealscanner.app.parse

/** Plain-text summary shown in the app and the result notification. */
object ListingFormatter {

    fun summary(p: ParsedListing, check: DealCheck? = null): String = buildString {
        check?.let { c ->
            val verdicts = (c.items + listOfNotNull(c.main)).mapNotNull { it.verdict }
            if (verdicts.isNotEmpty()) {
                val counts = Verdict.values().mapNotNull { v -> verdicts.count { it == v }.takeIf { it > 0 }?.let { "$it ${v.label.lowercase()}" } }
                appendLine("DEAL CHECK: ${counts.joinToString(", ")}")
                appendLine()
            }
        }
        if (p.warnings.isNotEmpty()) {
            p.warnings.forEach { appendLine("⚠ $it") }
            appendLine()
        }

        appendLine("TITLE: ${p.title ?: "not found"}")
        val main = p.mainPrice
        if (main == null) {
            appendLine("MAIN PRICE: not found")
        } else {
            append("MAIN PRICE: ${main.display()}")
            p.mainPriceMeaning?.let { append(" → ${it.label}") }
            appendLine()
            p.mainPriceReason?.let { appendLine("   ($it)") }
            check?.main?.let { appendCheck(it, "   ") }
        }
        if (p.setsMentioned.isNotEmpty()) appendLine("SETS: ${p.setsMentioned.joinToString(", ")}")

        appendLine()
        if (p.items.isEmpty()) {
            appendLine("ITEMS: none found in the description")
        } else {
            appendLine("ITEMS (${p.items.size})")
            p.items.forEachIndexed { i, item ->
                appendLine("${i + 1}. ${itemLine(item)}")
                check?.items?.getOrNull(i)?.let { appendCheck(it, "   ") }
            }
            check?.lotValue?.let { v ->
                appendLine()
                val partial = if (check.lotPriced < check.lotTotal) " (${check.lotPriced} of ${check.lotTotal} items priced)" else ""
                appendLine("MARKET VALUE OF ITEMS: ${money(v)}$partial")
            }
        }

        if (p.notes.isNotEmpty()) {
            appendLine()
            appendLine("OTHER NOTES")
            p.notes.take(8).forEach { appendLine("- $it") }
        }

        if (p.sponsored) {
            appendLine()
            appendLine("WHY IT LOOKS LIKE AN AD")
            p.sponsoredReasons.forEach { appendLine("- $it") }
        }
    }.trimEnd()

    private fun StringBuilder.appendCheck(c: ItemCheck, indent: String) {
        val m = c.market
        if (m == null) {
            c.problem?.let { appendLine("$indent→ market: $it") }
            return
        }
        val value = if (!m.exact && m.high > m.low) "${money(m.low)} to ${money(m.high)}" else money(m.amount)
        val verdict = Pricing.describe(c)?.let { " → $it" } ?: ""
        appendLine("$indent→ market $value$verdict")
        m.note?.let { appendLine("$indent   ($it)") }
    }

    fun itemLine(item: LineItem): String {
        val details = listOfNotNull(
            item.quantity?.let { "qty $it" },
            item.grade,
            item.product,
            item.set,
        ) + item.tags
        val left = if (details.isEmpty()) item.name else "${item.name} [${details.distinct().joinToString(", ")}]"
        return "$left | ${item.price?.display() ?: "no price"}"
    }

    /** One-line preview for the notification. */
    fun headline(p: ParsedListing, check: DealCheck? = null): String {
        val parts = mutableListOf<String>()
        if (p.sponsored) parts += "AD"
        check?.let { c ->
            val deals = (c.items + listOfNotNull(c.main)).count { it.verdict == Verdict.GOOD_DEAL }
            if (deals > 0) parts += "$deals GOOD DEAL${if (deals > 1) "S" else ""}"
        }
        p.mainPrice?.let { price -> parts += price.display() + (p.mainPriceMeaning?.let { " (${it.label})" } ?: "") }
        parts += "${p.items.size} item${if (p.items.size == 1) "" else "s"}"
        if (p.warnings.any { it.contains("far below") }) parts += "price looks too low"
        if (p.descriptionTruncated) parts += "tap See more"
        return (p.title?.let { "$it: " } ?: "") + parts.joinToString(" | ")
    }
}
