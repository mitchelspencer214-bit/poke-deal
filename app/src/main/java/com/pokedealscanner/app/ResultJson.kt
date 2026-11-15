package com.pokedealscanner.app

import com.pokedealscanner.app.parse.DealCheck
import com.pokedealscanner.app.parse.ItemCheck
import com.pokedealscanner.app.parse.LineItem
import com.pokedealscanner.app.parse.MainPriceMeaning
import com.pokedealscanner.app.parse.ParsedListing
import com.pokedealscanner.app.parse.Pricing
import com.pokedealscanner.app.parse.money
import org.json.JSONArray
import org.json.JSONObject

/** A scan saved as JSON so the results screen can draw it as cards instead of plain text. */
object ResultJson {

    fun build(p: ParsedListing, check: DealCheck?, rawLabel: String, raw: String): String {
        val o = JSONObject()
        o.put("title", p.title ?: "Untitled listing")
        o.put("mainPrice", p.mainPrice?.display() ?: JSONObject.NULL)
        o.put("mainMeaning", meaningLabel(p))
        o.put("mainReason", p.mainPriceReason ?: JSONObject.NULL)
        check?.main?.let { o.put("mainCheck", checkJson(it)) }
        o.put("sponsored", p.sponsored)
        o.put("warnings", JSONArray(p.warnings))
        o.put("sets", JSONArray(p.setsMentioned))

        val items = JSONArray()
        p.items.forEachIndexed { i, item ->
            val j = JSONObject()
            j.put("name", item.name)
            j.put("details", JSONArray(details(item)))
            j.put("ask", item.price?.display() ?: JSONObject.NULL)
            check?.items?.getOrNull(i)?.let { j.put("check", checkJson(it)) }
            items.put(j)
        }
        o.put("items", items)

        check?.let { c ->
            val verdicts = (c.items + listOfNotNull(c.main)).mapNotNull { it.verdict?.name }
            o.put("verdictCounts", JSONObject().apply {
                verdicts.groupingBy { it }.eachCount().forEach { (k, v) -> put(k, v) }
            })
            c.lotValue?.let { v ->
                o.put("lotValue", money(v))
                if (c.lotPriced < c.lotTotal) o.put("lotPartial", "${c.lotPriced} of ${c.lotTotal} items priced")
            }
            o.put("pricesUpdated", c.pricesUpdated)
        }
        o.put("notes", JSONArray(p.notes))
        o.put("rawLabel", rawLabel)
        o.put("raw", raw)
        return o.toString()
    }

    private fun meaningLabel(p: ParsedListing): String? {
        val m = p.mainPriceMeaning ?: return null
        if (p.mainPrice?.raw.equals("Free", ignoreCase = true) && p.mainPriceReason?.contains("trade") == true) return "Trade post"
        return when (m) {
            MainPriceMeaning.TITLE_ITEM -> "For the title item"
            MainPriceMeaning.WHOLE_LOT -> "For the whole lot"
            MainPriceMeaning.ONE_ITEM -> "For one listed item"
            MainPriceMeaning.PLACEHOLDER -> "Placeholder price"
            MainPriceMeaning.UNCLEAR -> "Unclear what it covers"
        }
    }

    private fun details(item: LineItem): List<String> = (
        listOfNotNull(
            item.quantity?.takeIf { it > 1 }?.let { "×$it" },
            item.grade,
            item.product?.let { SHORT[it] ?: it },
            item.set,
        ) + item.tags.filter { it != "Sealed" || item.product == null }.map { SHORT[it] ?: it }
        ).distinct()

    private fun checkJson(c: ItemCheck): JSONObject = JSONObject().apply {
        val m = c.market
        if (m != null) {
            put("market", if (!m.exact && m.high > m.low) "${money(m.low)} to ${money(m.high)}" else money(m.amount))
            put("source", m.source)
            m.note?.let { put("note", it) }
        }
        c.verdict?.let { put("verdict", it.name) }
        Pricing.describe(c)?.let { put("verdictText", it.substringAfter(", ", "")) }
        c.problem?.let { put("problem", it) }
    }

    private val SHORT = mapOf(
        "Elite Trainer Box" to "ETB",
        "Ultra Premium Collection" to "UPC",
        "Super Premium Collection" to "SPC",
        "Special Illustration Rare" to "SIR",
        "Illustration Rare" to "IR",
    )
}
