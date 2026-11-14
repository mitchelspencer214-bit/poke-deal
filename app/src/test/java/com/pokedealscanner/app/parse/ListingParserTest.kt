package com.pokedealscanner.app.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingParserTest {

    /** Builds OCR lines stacked top to bottom, like a phone screen. */
    private fun screen(vararg lines: String, startY: Int = 150, lineHeight: Int = 50): List<OcrLine> =
        lines.mapIndexed { i, t ->
            val top = startY + i * (lineHeight + 8)
            OcrLine(t, 30, top, 1000, top + lineHeight)
        }

    private val H = 2400

    // ---------- Prices ----------

    @Test fun fixesOcrMoney() {
        assertEquals("$11.99", ListingParser.fixOcrMoney("SJII 99"))
        assertEquals("$80", ListingParser.fixOcrMoney("S8O"))
        assertEquals("$120", ListingParser.fixOcrMoney("$12O"))
        assertEquals("SIR", ListingParser.fixOcrMoney("SIR"))
        assertEquals("Charizard SIR $80", ListingParser.fixOcrMoney("Charizard SIR $80"))
    }

    @Test fun findsItemPricesAndSkipsCardNumbers() {
        assertEquals(300.0, ListingParser.findPrice("Charizard 4/102 - 300")!!.first.amount, 0.001)
        assertEquals(450.0, ListingParser.findPrice("PSA 9 Charizard 450")!!.first.amount, 0.001)
        assertNull(ListingParser.findPrice("Lot of 50 cards"))
        assertNull(ListingParser.findPrice("Charizard PSA 10"))
        assertNull(ListingParser.findPrice("Base set 1999 holo"))
        assertEquals(1200.0, ListingParser.findPrice("Moonbreon $1,200 firm")!!.first.amount, 0.001)
        assertEquals(0.5, ListingParser.findPrice("bulk holos 50c each")!!.first.amount, 0.001)
        assertEquals(55.0, ListingParser.findPrice("151 ETB sealed 55 obo")!!.first.amount, 0.001)
        assertTrue(ListingParser.findPrice("151 ETB sealed 55 obo")!!.first.obo)
        assertNull(ListingParser.findPrice("151 ETB sealed"))
    }

    // ---------- Sets / products ----------

    @Test fun matchesMangledSetNames() {
        val sets = ListingParser.findSets("AsCENDE\nHEROE\nUANTASMAL\nFLAMES")
        assertTrue(sets.toString(), "Ascended Heroes" in sets)
        assertTrue(sets.toString(), "Phantasmal Flames" in sets)
    }

    @Test fun matchesSetNicknames() {
        assertEquals(listOf("Evolving Skies"), ListingParser.findSets("evo skies booster box"))
        assertTrue("Prismatic Evolutions" in ListingParser.findSets("Pris Evo ETB"))
        assertTrue("151" in ListingParser.findSets("151 booster bundle"))
        assertFalse("151" in ListingParser.findSets("$151"))
    }

    @Test fun findsProducts() {
        assertEquals("Elite Trainer Box", ListingParser.findProduct("Prismatic ETB sealed"))
        assertEquals("Blister", ListingParser.findProduct("3 pack blister"))
        assertEquals("Booster Bundle", ListingParser.findProduct("151 booster bundle"))
        assertNull(ListingParser.findProduct("Hide listing"))
    }

    // ---------- Whole listings ----------

    @Test fun mitchsFirstScanIsFlaggedAsAd() {
        val lines = listOf(OcrLine("3:27", 40, 20, 140, 70)) + screen(
            "X", "MK ULTRA - Live", "Rosin Concentrate", "NHL SIHOP", "...", "Nashville Predator..",
            "Up to 60% Off!", "Hideout Robe™", "BABY", "FIND", "SJII 99", "Candy Ribbon House",
            "ETB CLEARANCE", "AsCENDE", "HEROE", "UANTASMAL", "FLAMES", "CHAOS RIS", "EVERYTH",
            "Hide listing",
            "The promotion is not sponsored by Pokémon. | New app users only. T&Cs apply. |AI- generated imagery",
            "Discounts on popular ...",
            "If you don't like this, you can hide this listing and we'll show you fewer listings like this",
        )
        val p = ListingParser.parse(lines, H)
        println(ListingFormatter.summary(p))
        assertTrue(p.sponsored)
        assertEquals(11.99, p.mainPrice!!.amount, 0.001)
        assertTrue("Ascended Heroes" in p.setsMentioned)
        assertTrue("Phantasmal Flames" in p.setsMentioned)
        assertTrue(p.warnings.toString(), p.warnings.any { it.contains("far below") && it.contains("Elite Trainer Box") })
        assertFalse("status bar clock leaked", ListingFormatter.summary(p).contains("3:27"))
    }

    @Test fun itemizedDescription() {
        val p = ListingParser.parse(
            screen(
                "Pokemon cards for sale",
                "$1",
                "Listed 2 hours ago in La Mesa, CA",
                "Details",
                "Condition",
                "Used - Like New",
                "Charizard ex SIR Obsidian Flames $80",
                "Moonbreon PSA 9 - 400",
                "151 ETB sealed $55 obo",
                "bulk holos 50c each",
                "Cash or Venmo, pick up in La Mesa",
                "Seller information",
            ),
            H,
        )
        println(ListingFormatter.summary(p))
        assertEquals("Pokemon cards for sale", p.title)
        assertEquals(MainPriceMeaning.PLACEHOLDER, p.mainPriceMeaning)
        assertEquals(4, p.items.size)
        assertEquals(80.0, p.items[0].price!!.amount, 0.001)
        assertEquals("Obsidian Flames", p.items[0].set)
        assertTrue("Special Illustration Rare" in p.items[0].tags)
        assertEquals("PSA 9", p.items[1].grade)
        assertEquals("Evolving Skies", p.items[1].set)
        assertEquals(400.0, p.items[1].price!!.amount, 0.001)
        assertEquals("Elite Trainer Box", p.items[2].product)
        assertEquals("151", p.items[2].set)
        assertTrue(p.items[2].price!!.obo)
        assertEquals(0.5, p.items[3].price!!.amount, 0.001)
        assertEquals(listOf("Cash or Venmo, pick up in La Mesa"), p.notes)
        assertFalse(p.sponsored)
    }

    @Test fun wholeLotWithoutItemPrices() {
        val p = ListingParser.parse(
            screen(
                "Pokemon collection lot",
                "$250",
                "Listed 1 day ago in El Cajon, CA",
                "Details",
                "Condition",
                "Used - Good",
                "Selling all for 250",
                "- Surging Sparks ETB",
                "- 3 Prismatic booster packs",
                "- Binder of 200 cards",
            ),
            H,
        )
        println(ListingFormatter.summary(p))
        assertEquals(MainPriceMeaning.WHOLE_LOT, p.mainPriceMeaning)
        assertEquals(3, p.items.size)
        assertEquals("Surging Sparks", p.items[0].set)
    }

    @Test fun mainPriceMatchesTitleItem() {
        val p = ListingParser.parse(
            screen(
                "Prismatic Evolutions ETB",
                "$85",
                "Listed 3 hours ago in Santee, CA",
                "Details",
                "Condition",
                "New",
                "Brand new sealed from Pokemon Center",
            ),
            H,
        )
        println(ListingFormatter.summary(p))
        assertEquals(MainPriceMeaning.TITLE_ITEM, p.mainPriceMeaning)
        assertEquals("Elite Trainer Box", p.titleProduct)
        assertTrue(p.warnings.none { it.contains("far below") })
    }

    @Test fun mainPriceMatchesOneListedItem() {
        val p = ListingParser.parse(
            screen(
                "Prismatic ETB plus more",
                "$90",
                "Details",
                "Prismatic ETB $90",
                "Journey Together booster bundle $35",
                "Destined Rivals booster bundle $40",
            ),
            H,
        )
        assertEquals(MainPriceMeaning.ONE_ITEM, p.mainPriceMeaning)
        assertEquals(3, p.items.size)
    }

    @Test fun wrappedLinesAndTruncation() {
        val p = ListingParser.parse(
            screen(
                "Cards",
                "$40",
                "Details",
                "Charizard ex special illustration rare from",
                "obsidian flames $80",
                "Pikachu promo $10, Mew ex $15, Eevee $5",
                "See more",
            ),
            H,
        )
        println(ListingFormatter.summary(p))
        assertTrue(p.descriptionTruncated)
        assertEquals(p.items.joinToString("\n") { it.raw }, 4, p.items.size)
        assertEquals(80.0, p.items[0].price!!.amount, 0.001)
        assertEquals("Obsidian Flames", p.items[0].set)
    }

    @Test fun lowPricedSealedFlagged() {
        val p = ListingParser.parse(screen("Surging Sparks Booster Box", "$45", "Details", "sealed"), H)
        assertTrue(p.warnings.any { it.contains("far below") })
    }

    @Test fun formatterHasNoEmDashes() {
        val p = ListingParser.parse(screen("Cards", "$40", "Details", "Charizard $80, Pikachu $20"), H)
        assertFalse(ListingFormatter.summary(p).contains('—'))
        assertNotNull(ListingFormatter.headline(p))
    }
}
