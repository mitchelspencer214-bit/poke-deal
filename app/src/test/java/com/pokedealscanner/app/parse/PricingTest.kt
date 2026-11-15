package com.pokedealscanner.app.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTest {

    private val book = PriceBook(
        updated = "2026-11-14",
        sealed = listOf(
            SealedPrice("Delta Reign", "Elite Trainer Box", 103.18, "test"),
            SealedPrice("Delta Reign", "Booster Box", 222.95, "test"),
            SealedPrice("Surging Sparks", "Elite Trainer Box", 124.95, "test"),
            SealedPrice("Pokémon GO", "Elite Trainer Box", 156.25, "test"),
        ),
    )

    private fun screen(vararg lines: String): List<OcrLine> =
        lines.mapIndexed { i, t -> OcrLine(t, 30, 150 + i * 58, 1000, 200 + i * 58) }

    private val noCards: (CardQuery) -> List<CardCandidate>? = { emptyList() }

    @Test fun verdictCutoffs() {
        assertEquals(Verdict.GOOD_DEAL, Pricing.verdict(85.0, 100.0, book).first)
        assertEquals(Verdict.FAIR, Pricing.verdict(100.0, 100.0, book).first)
        assertEquals(Verdict.FAIR, Pricing.verdict(105.0, 100.0, book).first)
        assertEquals(Verdict.OVERPRICED, Pricing.verdict(106.0, 100.0, book).first)
    }

    @Test fun sealedLookupIgnoresAccentsAndAmpersands() {
        assertEquals(156.25, Pricing.sealedValue(book, "Pokemon GO", "Elite Trainer Box")!!.amount, 0.001)
        assertNull(Pricing.sealedValue(book, "Delta Reign", "Booster Bundle"))
    }

    @Test fun cardQueryExtraction() {
        val q = Pricing.cardQuery(ListingParser.parseItem("Charizard ex SIR Obsidian Flames 223/197 $80"))!!
        assertEquals("Charizard ex", q.name)
        assertEquals("Obsidian Flames", q.set)
        assertEquals("223", q.number)
        assertEquals("special illustration", q.rarity)

        val nick = Pricing.cardQuery(ListingParser.parseItem("Moonbreon PSA 9 - 400"))!!
        assertEquals("Umbreon VMAX", nick.name)
        assertEquals("215", nick.number)

        assertEquals("Pikachu ex", Pricing.cardQuery(ListingParser.parseItem("Pikachu ex NM raw $120"))!!.name)
        assertNull(Pricing.cardQuery(ListingParser.parseItem("bulk holos 50c each")))
        assertNull(Pricing.cardQuery(ListingParser.parseItem("Binder of 200 cards")))
    }

    @Test fun pickCardUsesRarityHintAndFlagsWideRanges() {
        val q = CardQuery("Charizard ex", "Obsidian Flames", null, "special illustration")
        val printings = listOf(
            CardCandidate("sv03-125", "Charizard ex", "Double rare", 5.27),
            CardCandidate("sv03-215", "Charizard ex", "Ultra Rare", 21.22),
            CardCandidate("sv03-223", "Charizard ex", "Special illustration rare", 84.61),
            CardCandidate("sv03-228", "Charizard ex", "Hyper rare", 35.11),
        )
        val (exact, _) = Pricing.pickCard(q, printings)
        assertEquals(84.61, exact!!.amount, 0.001)
        assertTrue(exact.exact)

        val (wide, _) = Pricing.pickCard(q.copy(rarity = null), printings)
        assertFalse(wide!!.exact)
        assertEquals(5.27, wide.low, 0.001)
        assertEquals(84.61, wide.high, 0.001)
    }

    @Test fun mitchsTradePostGetsVerdicts() {
        val parsed = ListingParser.parse(
            screen(
                "LOOKING FOR TRADE", "Free", "Description",
                "Delta Reign ETB- 90 ×3",
                "Delta Reign Booster Box- 190 ×2",
                "Charizard UPC- 220",
                "Surging Sparks ETB- 120",
            ),
            0,
        )
        val c = Pricing.check(parsed, book, noCards)
        println(ListingFormatter.summary(parsed, c))
        assertEquals(Verdict.FAIR, c.items[0].verdict)          // $90 vs $103.18 = 87%
        assertEquals(Verdict.FAIR, c.items[1].verdict)          // $190 vs $222.95 = 85.2%, just above the 85% cutoff
        assertNull(c.items[2].verdict)                          // no UPC prices on file
        assertNotNull(c.items[2].problem)
        assertEquals(Verdict.FAIR, c.items[3].verdict)          // $120 vs $124.95
        assertEquals(103.18 * 3 + 222.95 * 2 + 124.95, c.lotValue!!, 0.01)
        assertEquals(3, c.lotPriced)
    }

    @Test fun titleItemComparedToMarket() {
        val parsed = ListingParser.parse(screen("Pokemon TCG Delta Reign Booster Box", "$200", "Description", "Sealed booster box"), 0)
        val c = Pricing.check(parsed, book, noCards)
        assertEquals(Verdict.FAIR, c.main!!.verdict) // $200 vs $222.95 = 90%
    }

    @Test fun singleCardFromLookup() {
        val parsed = ListingParser.parse(screen("Cards", "$1", "Description", "Charizard ex SIR Obsidian Flames $60"), 0)
        var asked: CardQuery? = null
        val c = Pricing.check(parsed, book) { q ->
            asked = q
            listOf(CardCandidate("sv03-223", "Charizard ex", "Special illustration rare", 84.61))
        }
        println(ListingFormatter.summary(parsed, c))
        assertEquals("Charizard ex", asked!!.name)
        assertEquals(Verdict.GOOD_DEAL, c.items[0].verdict)
        assertTrue(ListingFormatter.summary(parsed, c).contains("GOOD DEAL, 29% under market"))
    }

    @Test fun gradedCardsShowRawPriceWithoutVerdict() {
        val parsed = ListingParser.parse(screen("Slabs", "$1", "Description", "Moonbreon PSA 9 - 400"), 0)
        val c = Pricing.check(parsed, book) { listOf(CardCandidate("swsh7-215", "Umbreon VMAX", "Secret Rare", 2324.94)) }
        assertNull(c.items[0].verdict)
        assertTrue(c.items[0].market!!.note!!.contains("raw card price"))
    }

    @Test fun serviceDownIsReportedNotFatal() {
        val parsed = ListingParser.parse(screen("Cards", "$1", "Description", "Pikachu ex $50"), 0)
        val c = Pricing.check(parsed, book) { null }
        assertTrue(c.items[0].problem!!.contains("couldn't reach"))
    }
}
