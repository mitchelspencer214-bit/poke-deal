package com.pokedealscanner.app.parse

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One line of OCR text and where it sat on screen (pixels). */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class Price(
    val amount: Double,
    val raw: String,
    val each: Boolean = false,
    val obo: Boolean = false,
    val firm: Boolean = false,
) {
    fun display(): String {
        val base = if (amount < 1.0) "${(amount * 100).toInt()}¢" else money(amount)
        val flags = listOfNotNull(
            if (each) "each" else null,
            if (obo) "obo" else null,
            if (firm) "firm" else null,
        )
        return if (flags.isEmpty()) base else "$base ${flags.joinToString(" ")}"
    }
}

data class LineItem(
    val name: String,
    val price: Price?,
    val quantity: Int?,
    val set: String?,
    val product: String?,
    val grade: String?,
    val tags: List<String>,
    val raw: String,
)

enum class MainPriceMeaning(val label: String) {
    TITLE_ITEM("Title item"),
    WHOLE_LOT("Whole lot"),
    ONE_ITEM("One listed item"),
    PLACEHOLDER("Placeholder, see description"),
    UNCLEAR("Unclear"),
}

data class ParsedListing(
    val title: String?,
    val mainPrice: Price?,
    val mainPriceMeaning: MainPriceMeaning?,
    val mainPriceReason: String?,
    val titleProduct: String?,
    val setsMentioned: List<String>,
    val items: List<LineItem>,
    val notes: List<String>,
    val sponsored: Boolean,
    val sponsoredReasons: List<String>,
    val descriptionTruncated: Boolean,
    val descriptionFound: Boolean,
    val warnings: List<String>,
)

fun money(amount: Double): String =
    if (amount % 1.0 == 0.0) "$" + "%,d".format(amount.toLong()) else "$" + "%,.2f".format(amount)

/**
 * Turns raw OCR lines from a Facebook Marketplace listing screenshot into a
 * structured listing: title, main price (and what it covers), and an item list
 * pulled from the description.
 *
 * Pure Kotlin, no Android dependencies, so it's unit-tested on the JVM.
 */
object ListingParser {

    // ---------- Screen layout ----------

    private const val STATUS_BAR_FRACTION = 0.045
    private const val NAV_BAR_FRACTION = 0.97

    private val UI_NOISE = setOf(
        "x", "×", "...", "…", "send", "message", "save", "share", "more", "details",
        "send seller a message", "hi, is this available?", "is this still available?",
        "hi, is this still available?", "hi, is this item still available?",
        "seller information", "seller details", "location is approximate",
        "today's picks", "hide listing", "see translation", "description", "see less",
        "about this item", "marketplace", "condition", "view seller profile",
    )

    private val DESCRIPTION_STOP = listOf(
        "seller information", "seller details", "location is approximate",
        "today's picks", "similar items", "related listings", "send seller a message",
        "more from this seller", "sponsored", "if you don't like this",
        "the promotion is not sponsored",
    )

    private val CONDITION_LINE = Regex(
        """^(condition\b.*|new|used\s*[-–]?\s*(like new|good|fair)?|like new|brand new|for parts.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val LISTED_LINE = Regex("""^listed\b.*""", RegexOption.IGNORE_CASE)

    // ---------- Sponsored ads ----------

    private val SPONSORED_PHRASES = listOf(
        "show you fewer listings like this" to "Has the ad menu (\"show you fewer listings like this\")",
        "not sponsored by" to "Ad fine print (\"not sponsored by...\")",
        "new app users only" to "Ad fine print (\"new app users only\")",
        "t&cs apply" to "Ad fine print (\"T&Cs apply\")",
        "ai-generated imagery" to "Says it uses AI-generated images",
        "ai- generated imagery" to "Says it uses AI-generated images",
        "ai generated imagery" to "Says it uses AI-generated images",
    )

    // ---------- Prices ----------

    /** "$" sometimes reads as "S", "SJ", or "S|". Digits sometimes read as letters. */
    private val OCR_MONEY = Regex(
        """(?<![A-Za-z0-9$])(\$|S[J|]?)\s?([0-9IlJ|Oo]{1,5}(?:,[0-9Oo]{3})?)(?:([ .,])([0-9Oo]{2}))?(?![A-Za-z0-9])"""
    )
    private val DOLLAR = Regex("""\$\s?(\d{1,3}(?:,\d{3})+|\d{1,6})(?:\.(\d{2}))?(\s?k\b)?""", RegexOption.IGNORE_CASE)
    private val CENTS = Regex("""(?<![\d.])(\d{1,2})\s?(?:c|¢|cents)\b""", RegexOption.IGNORE_CASE)
    private val BARE_NUMBER = Regex("""(?<![\w/#.$,])(\d{1,5})(?:\.(\d{2}))?(?![\w/%,]|\.\d)""")
    private val PRICE_LEAD = Regex("""(?:[-–—:=@|]|\bfor|\bat|\bprice|\basking|\bask)\s*$""", RegexOption.IGNORE_CASE)
    private val PRICE_TRAIL = Regex("""^\s*(?:obo|firm|each|ea\b|/ea|shipped|cash|bucks|dollars|or best offer|neg)""", RegexOption.IGNORE_CASE)
    private val QTY_TRAIL = Regex("""^\s*(?:x\b|cards?\b|packs?\b|pcs\b|pieces\b|ct\b|count\b|holos?\b|boxes\b|etbs?\b|slabs?\b|total\b)""", RegexOption.IGNORE_CASE)
    private val GRADE_LEAD = Regex("""(?:psa|bgs|cgc|sgc|tag|ace|grade[d]?|gem|mint|lot of|x)\s*$""", RegexOption.IGNORE_CASE)

    private val EACH = Regex("""\b(each|ea|a piece|per card|per pack|apiece)\b|/\s?ea\b""", RegexOption.IGNORE_CASE)
    private val OBO = Regex("""\b(obo|or best offer|negotiable|neg|ono)\b""", RegexOption.IGNORE_CASE)
    private val FIRM = Regex("""\bfirm\b""", RegexOption.IGNORE_CASE)

    private val PLACEHOLDER_PRICES = setOf(0.0, 1.0, 12.0, 123.0, 1234.0, 12345.0, 999.0, 9999.0, 99999.0)

    // ---------- Item details ----------

    private val QUANTITY = listOf(
        Regex("""\b(\d{1,4})\s?x\b""", RegexOption.IGNORE_CASE),
        Regex("""\bx\s?(\d{1,4})\b""", RegexOption.IGNORE_CASE),
        Regex("""\blot of\s?(\d{1,5})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,5})\s?(?:cards|packs|pcs|pieces|ct|count|holos|etbs|boxes|slabs)\b""", RegexOption.IGNORE_CASE),
    )
    private val GRADE = Regex("""\b(PSA|BGS|CGC|SGC|TAG|ACE)\s?(10|[1-9](?:\.5)?)\b""", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("""^\s*(?:[•·*▪●◦\-–]|\d{1,2}[.)])\s+""")
    private val LOT_WORDS = Regex(
        """\b(lot|bundle deal|all for|everything for|take all|take everything|whole collection|entire collection|for all|all of it|as a lot|for everything|whole thing)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEALED_WORDS = Regex("""\b(sealed|factory sealed|nib|unopened)\b""", RegexOption.IGNORE_CASE)

    // ======================================================================

    fun parse(rawLines: List<OcrLine>, screenHeight: Int): ParsedListing {
        // 1. Reading order, minus phone status bar and nav bar.
        val lines = rawLines
            .map { it.copy(text = clean(it.text)) }
            .filter { it.text.isNotBlank() }
            .filter { screenHeight <= 0 || (it.bottom > screenHeight * STATUS_BAR_FRACTION && it.top < screenHeight * NAV_BAR_FRACTION) }
            .sortedWith(compareBy({ it.top / 12 }, { it.left }))

        val allText = lines.joinToString("\n") { it.text }
        val lower = allText.lowercase()

        // 2. Sponsored ad?
        val sponsoredReasons = (
            (if (lines.any { it.text.lowercase().trim('.', ' ', '·') == "sponsored" }) listOf("Marked \"Sponsored\"") else emptyList()) +
                SPONSORED_PHRASES.filter { lower.contains(it.first) }.map { it.second }
            ).distinct()
        val sponsored = sponsoredReasons.isNotEmpty()

        // 3. Main price + title.
        val texts = lines.map { fixOcrMoney(it.text) }
        val priceIdx = texts.indexOfFirst { isStandalonePrice(it) }
        val mainPrice = if (priceIdx >= 0) firstDollar(texts[priceIdx]) else null
        val title = findTitle(lines, texts, priceIdx)

        // 4. Description region.
        val (descLines, descFound) = findDescription(lines, texts, priceIdx)
        val truncated = descLines.any { it.lowercase().contains("see more") || it.endsWith("...") || it.endsWith("…") } ||
            Regex("""\bsee more\b""", RegexOption.IGNORE_CASE).containsMatchIn(allText)

        // 5. Items vs notes.
        val items = mutableListOf<LineItem>()
        val notes = mutableListOf<String>()
        for (line in mergeWrapped(descLines)) {
            for (piece in splitMultiPrice(line)) {
                val item = parseItem(piece)
                val lotStatement = LOT_WORDS.containsMatchIn(piece) && item.product == null && item.set == null && item.grade == null
                if (lotStatement) notes += piece
                else if (looksLikeItem(piece, item)) items += item
                else if (isBoxArtFragment(piece)) continue
                else if (!isNoise(piece)) notes += piece.removeSuffix("See more").trim()
            }
        }

        // 6. Context.
        val titleItem = title?.let { parseItem(it) }
        val titleProduct = titleItem?.product
        val sets = findSets(allText)

        val (meaning, reason) = classifyMainPrice(mainPrice, title, titleItem, items, descLines.joinToString(" "))

        // 7. Warnings.
        val warnings = mutableListOf<String>()
        if (sponsored) warnings += "Sponsored ad, not a regular seller. Be careful."
        lowPriceWarning(mainPrice, meaning, titleProduct, lower)?.let { warnings += it }
        items.forEach { item ->
            val floor = item.product?.let { PokemonCatalog.SEALED_FLOOR[it] }
            val p = item.price
            if (floor != null && p != null && p.amount < floor) {
                warnings += "${item.name}: ${money(p.amount)} is far below normal for a sealed ${item.product}. Could be fake or a scam."
            }
        }
        if (truncated) warnings += "Description is cut off. Tap \"See more\" and scan again."
        if (!descFound) warnings += "Couldn't find the description on screen. Scroll so it's visible and scan again."

        return ParsedListing(
            title = title,
            mainPrice = mainPrice,
            mainPriceMeaning = meaning,
            mainPriceReason = reason,
            titleProduct = titleProduct,
            setsMentioned = sets,
            items = items,
            notes = notes,
            sponsored = sponsored,
            sponsoredReasons = sponsoredReasons,
            descriptionTruncated = truncated,
            descriptionFound = descFound,
            warnings = warnings.distinct(),
        )
    }

    // ---------- Text cleanup ----------

    private fun clean(s: String): String = s
        .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun isNoise(s: String): Boolean {
        val l = s.lowercase().trim().trimEnd('.', ':')
        return l in UI_NOISE || l.length <= 1 || l == "see more"
    }

    /** Fixes common OCR misreads in money: "SJII 99" -> "$11.99", "S8O" -> "$80". */
    fun fixOcrMoney(line: String): String {
        val standaloneCheck = line.trim()
        return OCR_MONEY.replace(line) { m ->
            val prefix = m.groupValues[1]
            val digits = m.groupValues[2]
            val sep = m.groupValues[3]
            val cents = m.groupValues[4]
            val hasRealDigit = digits.any { it.isDigit() }
            val isWholeLine = m.value.trim() == standaloneCheck
            if (prefix != "$" && !hasRealDigit && !isWholeLine) return@replace m.value
            if (prefix != "$" && digits.all { it.isLetter() } && !isWholeLine) return@replace m.value
            val fixed = digits.map {
                when (it) { 'I', 'l', 'J', '|' -> '1'; 'O', 'o' -> '0'; else -> it }
            }.joinToString("")
            val fixedCents = cents.replace('O', '0').replace('o', '0')
            // A space or comma before exactly two digits is almost always a lost decimal point,
            // but only when the whole thing came in as a $ amount.
            if (fixedCents.isNotEmpty() && (sep == "." || isWholeLine)) "$$fixed.$fixedCents"
            else if (fixedCents.isNotEmpty()) "$$fixed$sep$fixedCents"
            else "$$fixed"
        }
    }

    private fun isStandalonePrice(s: String): Boolean {
        val t = s.trim()
        if (t.equals("free", ignoreCase = true)) return true
        val m = DOLLAR.find(t) ?: return false
        if (m.range.first != 0) return false
        val rest = t.substring(m.range.last + 1).trim()
        // "$120", "$120 $150" (sale), "$120 · In stock"
        return rest.isEmpty() || (rest.length <= 20 && rest.split(" ").size <= 3)
    }

    private fun firstDollar(s: String): Price? {
        if (s.trim().equals("free", ignoreCase = true)) return Price(0.0, "Free")
        val m = DOLLAR.find(s) ?: return null
        return Price(dollarAmount(m), m.value.trim(), EACH.containsMatchIn(s), OBO.containsMatchIn(s), FIRM.containsMatchIn(s))
    }

    private fun dollarAmount(m: MatchResult): Double {
        val whole = m.groupValues[1].replace(",", "").toDouble()
        val cents = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble()?.div(100) ?: 0.0
        val k = if (m.groupValues[3].isNotBlank()) 1000.0 else 1.0
        return (whole + cents) * k
    }

    /** Finds an item's price in a line, avoiding card numbers, grades, years, and quantities. */
    fun findPrice(line: String): Pair<Price, IntRange>? {
        val s = fixOcrMoney(line)
        val each = EACH.containsMatchIn(s)
        val obo = OBO.containsMatchIn(s)
        val firm = FIRM.containsMatchIn(s)

        DOLLAR.find(s)?.let { return Price(dollarAmount(it), it.value.trim(), each, obo, firm) to it.range }
        CENTS.find(s)?.let {
            return Price(it.groupValues[1].toDouble() / 100.0, it.value.trim(), each || true, obo, firm) to it.range
        }

        val candidates = BARE_NUMBER.findAll(s).filter { m ->
            val before = s.substring(0, m.range.first)
            val after = s.substring(m.range.last + 1)
            val n = m.groupValues[1].toInt()
            when {
                GRADE_LEAD.containsMatchIn(before) -> false
                QTY_TRAIL.containsMatchIn(after) -> false
                n in 1995..2035 && m.groupValues[2].isEmpty() -> false
                m.value == "151" && !PRICE_LEAD.containsMatchIn(before) -> false
                PRICE_LEAD.containsMatchIn(before) -> true
                PRICE_TRAIL.containsMatchIn(after) -> true
                // Last thing on a line that has words before it: "Charizard ex 80"
                after.isBlank() && before.any { it.isLetter() } -> true
                else -> false
            }
        }.toList()
        val m = candidates.lastOrNull() ?: return null
        val amount = m.groupValues[1].toDouble() + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble()?.div(100) ?: 0.0)
        if (amount <= 0.0) return null
        return Price(amount, m.value, each, obo, firm) to m.range
    }

    // ---------- Layout ----------

    private fun findTitle(lines: List<OcrLine>, texts: List<String>, priceIdx: Int): String? {
        if (priceIdx > 0) {
            val parts = mutableListOf<OcrLine>()
            var i = priceIdx - 1
            while (i >= 0 && parts.size < 2) {
                val l = lines[i]
                if (isNoise(l.text) || isStandalonePrice(texts[i])) break
                val anchor = parts.lastOrNull() ?: lines[priceIdx]
                val height = max(1, l.bottom - l.top)
                if (anchor.top - l.bottom > height * 1.3) break
                parts += l
                i--
            }
            if (parts.isNotEmpty()) return parts.reversed().joinToString(" ") { it.text }
        }
        return lines.firstOrNull { !isNoise(it.text) && it.text.count { c -> c.isLetter() } >= 3 }?.text
    }

    private fun findDescription(lines: List<OcrLine>, texts: List<String>, priceIdx: Int): Pair<List<String>, Boolean> {
        val lowers = lines.map { it.text.lowercase().trim().trimEnd(':') }
        var start = lowers.indexOfFirst { it == "description" || it == "about this item" }
        var found = start >= 0
        if (start < 0) {
            val details = lowers.indexOfFirst { it == "details" }
            if (details >= 0) { start = details; found = true }
        }
        if (start < 0 && priceIdx >= 0) start = priceIdx
        if (start < 0) start = -1

        val out = mutableListOf<String>()
        for (i in (start + 1) until lines.size) {
            val t = lines[i].text
            val l = lowers[i]
            if (DESCRIPTION_STOP.any { l.startsWith(it) }) break
            if (LISTED_LINE.matches(t) || CONDITION_LINE.matches(t)) continue
            if (isNoise(t)) continue
            out += texts[i]
        }
        if (!found) found = out.isNotEmpty()
        return out to found
    }

    /** Joins a line with the next when the seller's line wrapped. */
    private fun mergeWrapped(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            val prev = out.lastOrNull()
            val startsLower = line.firstOrNull()?.isLowerCase() == true
            val priceOnly = isStandalonePrice(line) || Regex("""^\s*(obo|firm|each)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
            if (prev != null && (startsLower || priceOnly) && findPrice(prev) == null && !BULLET.containsMatchIn(line)) {
                out[out.lastIndex] = "$prev $line"
            } else {
                out += line
            }
        }
        return out
    }

    /** "Charizard $80, Pikachu $20, Mew $15" -> three items. */
    private fun splitMultiPrice(line: String): List<String> {
        val dollarCount = DOLLAR.findAll(fixOcrMoney(line)).count()
        if (dollarCount < 2) return listOf(line)
        val parts = line.split(Regex("""\s*(?:,|;|\s\|\s|\s/\s|\s•\s)\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts else listOf(line)
    }

    /** Single shouty words like "HEROE" or "FLAMES" are usually product-image text. */
    private fun isBoxArtFragment(s: String): Boolean =
        s.split(" ").size <= 2 && s.none { it.isLowerCase() } && s.count { it.isLetter() } >= 3

    private fun looksLikeItem(raw: String, item: LineItem): Boolean =
        item.price != null || item.set != null || item.product != null || item.grade != null ||
            item.quantity != null || item.tags.any { it != "Sealed" } || BULLET.containsMatchIn(raw)

    // ---------- Item parsing ----------

    fun parseItem(raw: String): LineItem {
        val line = BULLET.replace(raw, "").trim()
        val priceHit = findPrice(line)
        val price = priceHit?.first

        val fixed = fixOcrMoney(line)
        var name = if (priceHit != null) fixed.removeRange(priceHit.second) else fixed
        name = name
            .replace(EACH, "").replace(OBO, "").replace(FIRM, "")
            .replace(Regex("""\b(asking|price|for|at)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim().trim('-', '–', '—', ':', '=', '@', '|', ',', ' ', '.')

        val leadingCount = Regex("""^(\d{1,3})\s+(?=[A-Za-z])""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it != 151 && it in 1..500 && priceHit?.second?.first != 0 }
        val quantity = QUANTITY.firstNotNullOfOrNull { it.find(line)?.groupValues?.get(1)?.toIntOrNull() } ?: leadingCount
        val grade = GRADE.find(line)?.let { "${it.groupValues[1].uppercase()} ${it.groupValues[2]}" }
        val product = findProduct(line)

        val tags = mutableListOf<String>()
        PokemonCatalog.RARITY_TAGS.forEach { (label, rx) -> if (rx.containsMatchIn(line)) tags += label }
        if ("Special Illustration Rare" in tags) tags.remove("Illustration Rare")
        if (SEALED_WORDS.containsMatchIn(line)) tags += "Sealed"

        var set = findSets(line).firstOrNull()
        PokemonCatalog.CARD_NICKNAMES.forEach { (nick, info) ->
            if (line.lowercase().contains(nick)) {
                tags += info.first
                if (set == null) set = info.second
            }
        }

        return LineItem(
            name = name.ifBlank { line },
            price = price,
            quantity = quantity,
            set = set,
            product = product,
            grade = grade,
            tags = tags.distinct(),
            raw = raw,
        )
    }

    fun findProduct(line: String): String? {
        val l = " ${line.lowercase()} "
        var best: Pair<String, Int>? = null
        for ((label, phrases) in PokemonCatalog.PRODUCT_TYPES) {
            for (p in phrases) {
                if (Regex("""(?<![a-z])${Regex.escape(p.trim())}(?![a-z])""").containsMatchIn(l)) {
                    if (best == null || p.length > best.second) best = label to p.length
                }
            }
        }
        return best?.first
    }

    // ---------- Sets ----------

    /** Exact names and nicknames, plus fuzzy matching for OCR-mangled box art text. */
    fun findSets(text: String): List<String> {
        val found = linkedSetOf<String>()
        val lower = text.lowercase()
        for ((name, aliases) in PokemonCatalog.SETS) {
            for (n in listOf(name) + aliases) {
                val hit = if (n.length <= 3 && n.all { it.isUpperCase() }) {
                    Regex("""(?<![A-Za-z0-9$])${Regex.escape(n)}(?![A-Za-z0-9])""").containsMatchIn(text)
                } else {
                    Regex("""(?<![a-z0-9$])${Regex.escape(n.lowercase())}(?![a-z0-9])""").containsMatchIn(lower)
                }
                if (hit) { found += name; break }
            }
        }

        // Fuzzy: compare letter-only word windows to each multi-word set name.
        val words = text.split(Regex("""[\s\n]+""")).map { w -> w.filter { it.isLetter() }.uppercase() }.filter { it.isNotEmpty() }
        for (name in PokemonCatalog.SETS.keys) {
            if (name in found) continue
            val target = name.filter { it.isLetter() }.uppercase()
            if (target.length < 8) continue
            val k = name.split(" ").count { it.any(Char::isLetter) }
            for (size in setOf(k, k + 1, max(1, k - 1))) {
                if (words.size < size) continue
                val hit = (0..words.size - size).any { i ->
                    val window = words.subList(i, i + size).joinToString("")
                    similarity(window, target) >= 0.8
                }
                if (hit) { found += name; break }
            }
        }
        return found.toList()
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (abs(a.length - b.length) > max(a.length, b.length) / 4) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    // ---------- Main price ----------

    fun classifyMainPrice(
        main: Price?,
        title: String?,
        titleItem: LineItem?,
        items: List<LineItem>,
        description: String,
    ): Pair<MainPriceMeaning?, String?> {
        if (main == null) return null to null
        val amount = main.amount
        val priced = items.filter { it.price != null }
        val context = "${title.orEmpty()} $description"

        if (amount in PLACEHOLDER_PRICES && (priced.isNotEmpty() || amount <= 1.0 || amount == 123.0 || amount == 1234.0)) {
            return MainPriceMeaning.PLACEHOLDER to "${money(amount)} is a common placeholder; real prices are likely in the description or by message"
        }

        val total = priced.sumOf { it.price!!.amount * (if (it.price.each) (it.quantity ?: 1) else 1) }
        val closeToTotal = priced.size >= 2 && abs(total - amount) <= max(1.0, total * 0.10)

        LOT_WORDS.find(context)?.let { m ->
            return if (closeToTotal) MainPriceMeaning.WHOLE_LOT to "says \"${m.value}\" and matches the item total (${money(total)})"
            else MainPriceMeaning.WHOLE_LOT to "listing says \"${m.value}\""
        }

        priced.firstOrNull { abs(it.price!!.amount - amount) < 0.01 }?.let {
            return MainPriceMeaning.ONE_ITEM to "matches the price of \"${it.name}\""
        }

        if (closeToTotal) return MainPriceMeaning.WHOLE_LOT to "matches the total of the listed items (${money(total)})"

        if (priced.size >= 2) {
            return MainPriceMeaning.UNCLEAR to "description lists ${priced.size} prices (total ${money(total)}); main price matches neither one item nor the total"
        }

        if (priced.isEmpty() && items.size >= 2) {
            return MainPriceMeaning.WHOLE_LOT to "description lists ${items.size} items with no individual prices"
        }

        if (titleItem != null && (titleItem.product != null || titleItem.grade != null || titleItem.set != null) && items.size <= 1) {
            return MainPriceMeaning.TITLE_ITEM to "title names one product and the description doesn't list others"
        }

        if (items.isEmpty()) return MainPriceMeaning.TITLE_ITEM to "no other items listed"

        return MainPriceMeaning.UNCLEAR to "couldn't tell from the title or description"
    }

    private fun lowPriceWarning(main: Price?, meaning: MainPriceMeaning?, titleProduct: String?, lowerAll: String): String? {
        if (main == null || meaning == MainPriceMeaning.PLACEHOLDER || meaning == MainPriceMeaning.WHOLE_LOT) return null
        val product = titleProduct ?: run {
            // Fall back to the only sealed product type mentioned anywhere on screen.
            val mentioned = PokemonCatalog.SEALED_FLOOR.keys.filter { label ->
                PokemonCatalog.PRODUCT_TYPES.first { it.first == label }.second.any { p ->
                    Regex("""(?<![a-z])${Regex.escape(p)}(?![a-z])""").containsMatchIn(lowerAll)
                }
            }
            mentioned.singleOrNull()
        } ?: return null
        val floor = PokemonCatalog.SEALED_FLOOR[product] ?: return null
        if (main.amount >= floor || main.amount <= 0.0) return null
        return "${money(main.amount)} is far below normal for a sealed $product. Likely an ad, fake, or scam."
    }
}
