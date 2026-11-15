package com.pokedealscanner.app.parse

/** Plain-text summary shown in the app and the result notification. */
object ListingFormatter {

    fun summary(p: ParsedListing): String = buildString {
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
        }
        if (p.setsMentioned.isNotEmpty()) appendLine("SETS: ${p.setsMentioned.joinToString(", ")}")

        appendLine()
        if (p.items.isEmpty()) {
            appendLine("ITEMS: none found in the description")
        } else {
            appendLine("ITEMS (${p.items.size})")
            p.items.forEachIndexed { i, item -> appendLine("${i + 1}. ${itemLine(item)}") }
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
    fun headline(p: ParsedListing): String {
        val parts = mutableListOf<String>()
        if (p.sponsored) parts += "AD"
        p.mainPrice?.let { price -> parts += price.display() + (p.mainPriceMeaning?.let { " (${it.label})" } ?: "") }
        parts += "${p.items.size} item${if (p.items.size == 1) "" else "s"}"
        if (p.warnings.any { it.contains("far below") }) parts += "price looks too low"
        if (p.descriptionTruncated) parts += "tap See more"
        return (p.title?.let { "$it: " } ?: "") + parts.joinToString(" | ")
    }
}
