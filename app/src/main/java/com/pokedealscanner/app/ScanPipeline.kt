package com.pokedealscanner.app

import android.content.Context
import com.pokedealscanner.app.parse.ListingFormatter
import com.pokedealscanner.app.parse.ListingParser
import com.pokedealscanner.app.parse.OcrLine
import com.pokedealscanner.app.parse.Pricing
import com.pokedealscanner.app.pricing.PriceBookStore
import com.pokedealscanner.app.pricing.TcgdexClient

/** Lines in, saved result + notification out. Call from a background thread (it uses the network). */
object ScanPipeline {

    fun process(context: Context, lines: List<OcrLine>, screenHeight: Int, rawLabel: String, rawText: String) {
        try {
            val parsed = ListingParser.parse(lines, screenHeight)
            val check = try {
                val book = PriceBookStore.load(context)
                val cards = TcgdexClient(context.cacheDir)
                Pricing.check(parsed, book) { cards.lookup(it) }
            } catch (_: Exception) {
                null // Prices are a bonus; never lose the scan over them.
            }
            ScanResults.publish(
                context,
                ListingFormatter.summary(parsed, check) + "\n\n----- $rawLabel -----\n" + rawText,
                ListingFormatter.headline(parsed, check),
            )
        } catch (e: Exception) {
            ScanResults.publish(context, "Couldn't organize this listing (${e.localizedMessage}).\n\n$rawText")
        }
    }
}
