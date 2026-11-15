package com.pokedealscanner.app.parse

import kotlin.math.abs

data class SealedPrice(val set: String, val product: String, val market: Double, val source: String)

/** The sealed price list (from sealed-prices.json) plus the verdict cutoffs. */
data class PriceBook(
    val updated: String,
    val sealed: List<SealedPrice>,
    val dealBelow: Double = 0.85,
    val overpricedAbove: Double = 1.05,
)

enum class Verdict(val label: String) {
    GOOD_DEAL("GOOD DEAL"),
    FAIR("FAIR"),
    OVERPRICED("OVERPRICED"),
}

/** What an item is worth. low/high differ when several printings matched. */
data class MarketValue(
    val amount: Double,
    val source: String,
    val low: Double = amount,
    val high: Double = amount,
    val exact: Boolean = true,
    val note: String? = null,
)

data class CardQuery(val name: String, val set: String?, val number: String?, val rarity: String?)

data class CardCandidate(val id: String, val name: String, val rarity: String?, val market: Double?)

data class ItemCheck(
    val market: MarketValue?,
    val verdict: Verdict?,
    val ratio: Double?,
    val problem: String?,
)

data class DealCheck(
    /** Same order as ParsedListing.items. */
    val items: List<ItemCheck>,
    /** Check of the main listing price, when it's for one product. */
    val main: ItemCheck?,
    /** Market value of everything listed, when the main price is for the whole lot. */
    val lotValue: Double?,
    val lotPriced: Int,
    val lotTotal: Int,
    val pricesUpdated: String,
)

/**
 * Compares asking prices to market prices.
 * Sealed products come from the price list; single cards from a lookup function
 * (TCGdex in the app, a fake in tests). Pure Kotlin, no Android.
 */
object Pricing {

    private const val MAX_CARD_LOOKUPS = 8
    private val SEALED_TYPES = setOf("Elite Trainer Box", "Booster Box", "Booster Bundle")

    fun verdict(ask: Double, market: Double, book: PriceBook): Pair<Verdict, Double> {
        val ratio = ask / market
        val v = when {
            ratio <= book.dealBelow -> Verdict.GOOD_DEAL
            ratio <= book.overpricedAbove -> Verdict.FAIR
            else -> Verdict.OVERPRICED
        }
        return v to ratio
    }

    fun sealedValue(book: PriceBook, set: String?, product: String?): MarketValue? {
        if (set == null || product == null) return null
        val hit = book.sealed.firstOrNull { norm(it.set) == norm(set) && it.product.equals(product, ignoreCase = true) }
            ?: return null
        return MarketValue(hit.market, hit.source)
    }

    private fun norm(s: String) = s.lowercase().replace("&", "and").replace("é", "e").filter { it.isLetterOrDigit() }

    // ---------- Card name extraction ----------

    private val NUMBER = Regex("""(?<![\d$])#?\s?(\d{1,3})\s?/\s?(\d{1,3})(?!\d)""")
    private val STRIP_WORDS = Regex(
        """\b(sir|sar|ir|ar|special illustration rare|special illustration|illustration rare|alt art|alternate art|full art|fa|aa|gold|hyper rare|secret rare|rainbow|rainbow rare|textured|""" +
            """psa|bgs|cgc|sgc|tag|ace|gem mint|mint|nm|lp|mp|hp|dmg|near mint|lightly played|raw|graded|slab|slabbed|""" +
            """holo|reverse holo|rev holo|reverse|non holo|1st edition|1st ed|first edition|shadowless|unlimited|""" +
            """card|cards|single|singles|english|eng|japanese|jp|jpn|pokemon|pokémon|tcg|the|from|with|and|obo|firm|each|ea|pack fresh|pulled)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "Charizard ex SIR Obsidian Flames 223/197" -> name "Charizard ex", set, number "223", rarity hint. */
    fun cardQuery(item: LineItem): CardQuery? {
        val lower = item.raw.lowercase()
        PokemonCatalog.NICKNAME_CARDS.forEach { (nick, card) ->
            if (lower.contains(nick)) return CardQuery(card.first, card.second, card.third, null)
        }
        if (Regex("""\b(bulk|lot|binder|collection|bundle|box|pack|packs|tin|case)\b""", RegexOption.IGNORE_CASE).containsMatchIn(item.raw)) return null

        val number = NUMBER.find(item.raw)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
        var text = item.name
        text = NUMBER.replace(text, " ")
        // Remove the set name and its nicknames.
        item.set?.let { set ->
            (listOf(set) + PokemonCatalog.SETS[set].orEmpty()).forEach { n ->
                text = text.replace(Regex("""(?<![A-Za-z0-9])${Regex.escape(n)}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE), " ")
            }
        }
        text = Regex("""\b(PSA|BGS|CGC|SGC|TAG|ACE)\s?\d+(\.5)?\b""", RegexOption.IGNORE_CASE).replace(text, " ")
        text = STRIP_WORDS.replace(text, " ")
        text = text.replace(Regex("""[^\p{L}\p{N}'\-. ]"""), " ")
            .replace(Regex("""(?<=\s|^)[-.]+|[-.]+(?=\s|$)"""), " ")
            .replace(Regex("""\b\d+\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        if (text.count { it.isLetter() } < 3 || text.split(" ").size > 5) return null

        val rarity = when {
            "Special Illustration Rare" in item.tags -> "special illustration"
            "Illustration Rare" in item.tags -> "illustration rare"
            "Hyper Rare" in item.tags -> "hyper"
            else -> null
        }
        return CardQuery(text, item.set, number, rarity)
    }

    /** Chooses a market value from the printings that matched. */
    fun pickCard(query: CardQuery, candidates: List<CardCandidate>): Pair<MarketValue?, String?> {
        var pool = candidates.filter { it.market != null && it.market > 0 }
        if (pool.isEmpty()) return null to "no market price found for \"${query.name}\""
        query.rarity?.let { hint ->
            val filtered = pool.filter { c ->
                val r = c.rarity?.lowercase().orEmpty()
                r.contains(hint) && !(hint == "illustration rare" && r.contains("special"))
            }
            if (filtered.isNotEmpty()) pool = filtered
        }
        val prices = pool.mapNotNull { it.market }.sorted()
        val median = prices[prices.size / 2]
        val source = "TCGplayer market via TCGdex"
        return when {
            prices.size == 1 -> MarketValue(median, source, note = pool[0].rarity) to null
            prices.last() <= prices.first() * 1.25 -> MarketValue(median, source, prices.first(), prices.last(), exact = false, note = "${prices.size} printings, similar prices") to null
            else -> MarketValue(
                median, source, prices.first(), prices.last(), exact = false,
                note = "${prices.size} printings from ${money(prices.first())} to ${money(prices.last())}; add the card number (like 223/197) for an exact price",
            ) to null
        }
    }

    // ---------- Whole listing ----------

    /**
     * @param cardLookup returns printings for a card, an empty list for no match,
     *   or null when the price service couldn't be reached.
     */
    fun check(parsed: ParsedListing, book: PriceBook, cardLookup: (CardQuery) -> List<CardCandidate>?): DealCheck {
        var lookups = 0
        val cache = HashMap<CardQuery, Pair<MarketValue?, String?>>()

        fun cardValue(item: LineItem): Pair<MarketValue?, String?> {
            val q = cardQuery(item) ?: return null to null
            cache[q]?.let { return it }
            if (lookups >= MAX_CARD_LOOKUPS) return null to "skipped (too many cards in one listing)"
            lookups++
            val found = cardLookup(q) ?: return (null to "couldn't reach the card price service").also { cache[q] = it }
            val result = if (found.isEmpty()) null to "no card named \"${q.name}\"${q.set?.let { " in $it" } ?: ""} found" else pickCard(q, found)
            cache[q] = result
            return result
        }

        fun valueOf(item: LineItem, fallbackSet: String?): Pair<MarketValue?, String?> {
            val product = item.product
            if (product != null) {
                if (product !in SEALED_TYPES) return null to "no market price on file for ${product}s yet"
                val set = item.set ?: fallbackSet ?: return null to "set not named, can't price this $product"
                return (sealedValue(book, set, product) ?: return null to "no market price on file for $set $product") to null
            }
            return cardValue(item)
        }

        fun judge(ask: Price?, value: Pair<MarketValue?, String?>, item: LineItem): ItemCheck {
            val (market, problem) = value
            if (market == null) return ItemCheck(null, null, null, problem)
            if (item.grade != null) {
                return ItemCheck(market.copy(note = listOfNotNull(market.note, "raw card price; a ${item.grade} usually sells for more").joinToString("; ")), null, null, null)
            }
            if (ask == null || ask.amount <= 0.0) return ItemCheck(market, null, null, null)
            if (!market.exact && market.high > market.low * 1.25) return ItemCheck(market, null, null, null)
            val (v, ratio) = verdict(ask.amount, market.amount, book)
            return ItemCheck(market, v, ratio, null)
        }

        val listingSet = parsed.setsMentioned.singleOrNull()
        val itemChecks = parsed.items.map { item -> judge(item.price, valueOf(item, null), item) }

        // Main price, when it's for the title item.
        val main = parsed.mainPrice?.let { price ->
            if (parsed.mainPriceMeaning != MainPriceMeaning.TITLE_ITEM) return@let null
            val titleItem = parsed.title?.let { ListingParser.parseItem(it) } ?: return@let null
            judge(price, valueOf(titleItem, listingSet), titleItem)
        }

        // Lot value: everything listed, times quantity.
        val valued = parsed.items.zip(itemChecks).filter { (_, c) -> c.market != null && (c.market.exact || c.market.high <= c.market.low * 1.25) }
        val lotValue = valued.sumOf { (item, c) -> c.market!!.amount * (item.quantity ?: 1) }.takeIf { valued.isNotEmpty() }
        var lotCheck: ItemCheck? = null
        if (parsed.mainPriceMeaning == MainPriceMeaning.WHOLE_LOT && lotValue != null && valued.size == parsed.items.size) {
            val ask = parsed.mainPrice
            if (ask != null && ask.amount > 0) {
                val (v, ratio) = verdict(ask.amount, lotValue, book)
                lotCheck = ItemCheck(MarketValue(lotValue, "sum of listed items"), v, ratio, null)
            }
        }

        return DealCheck(
            items = itemChecks,
            main = main ?: lotCheck,
            lotValue = lotValue,
            lotPriced = valued.size,
            lotTotal = parsed.items.size,
            pricesUpdated = book.updated,
        )
    }

    /** "GOOD DEAL, 13% under market" */
    fun describe(c: ItemCheck): String? {
        val v = c.verdict ?: return null
        val ratio = c.ratio ?: return v.label
        val pct = abs(((1 - ratio) * 100)).toInt()
        val side = when {
            pct == 0 -> "at market"
            ratio < 1 -> "$pct% under market"
            else -> "$pct% over market"
        }
        return "${v.label}, $side"
    }
}
