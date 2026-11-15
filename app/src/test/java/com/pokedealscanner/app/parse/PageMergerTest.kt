package com.pokedealscanner.app.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMergerTest {

    private fun line(t: String, top: Int) = OcrLine(t, 30, top, 1000, top + 50)

    @Test fun stitchesScrolledPagesIgnoringPinnedFooter() {
        // Page 1: title, price, start of description. "Send" is pinned at the bottom.
        val page1 = listOf(
            line("Pokemon cards lot", 1500), line("$300", 1560), line("Description", 1700),
            line("Charizard ex SIR $80", 1800), line("Moonbreon PSA 9 $400", 1900), line("Send", 2200),
        )
        // Page 2: scrolled up by 1000px. Pinned "Send" didn't move.
        val page2 = listOf(
            line("Charizard ex SIR $80", 800), line("Moonbreon PSA 9 $400", 900),
            line("151 ETB $55", 1000), line("Mew ex $15", 1100), line("Seller information", 1400), line("Send", 2200),
        )
        val merged = PageMerger.merge(listOf(page1, page2))
        val texts = merged.map { it.text }
        assertEquals(
            listOf("Pokemon cards lot", "$300", "Description", "Charizard ex SIR $80", "Moonbreon PSA 9 $400",
                "151 ETB $55", "Mew ex $15", "Send", "Seller information"),
            texts,
        )
        assertEquals(2000, merged.first { it.text == "151 ETB $55" }.top)

        val p = ListingParser.parse(merged, 0)
        assertEquals(4, p.items.size)
        assertEquals("Pokemon cards lot", p.title)
    }

    @Test fun noOverlapAppendsBelow() {
        val merged = PageMerger.merge(listOf(listOf(line("A one", 100)), listOf(line("B two", 100))))
        assertTrue(merged[1].top > merged[0].bottom)
    }
}
