package com.pokedealscanner.app.pricing

import android.content.Context
import com.pokedealscanner.app.parse.PriceBook
import com.pokedealscanner.app.parse.SealedPrice
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The sealed price list. Ships inside the app, and refreshes from the GitHub repo
 * (at most every 6 hours) so prices can be updated by editing one file, no reinstall.
 * Call from a background thread.
 */
object PriceBookStore {
    private const val REMOTE =
        "https://raw.githubusercontent.com/mitchelspencer214-bit/poke-deal/main/app/src/main/assets/sealed-prices.json"
    private const val REFRESH_MS = 6L * 60 * 60 * 1000

    fun load(context: Context): PriceBook {
        val cache = File(context.filesDir, "sealed-prices.json")
        if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > REFRESH_MS) {
            download()?.let { body -> if (runCatching { parse(body) }.isSuccess) cache.writeText(body) }
        }
        val bundled = context.assets.open("sealed-prices.json").bufferedReader().use { it.readText() }
        val fromCache = if (cache.exists()) runCatching { parse(cache.readText()) }.getOrNull() else null
        val fromApp = parse(bundled)
        // Use whichever list is newer.
        return if (fromCache != null && fromCache.updated >= fromApp.updated) fromCache else fromApp
    }

    private fun download(): String? = try {
        val conn = URL(REMOTE).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    fun parse(json: String): PriceBook {
        val o = JSONObject(json)
        val arr = o.getJSONArray("sealed")
        val sealed = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            SealedPrice(e.getString("set"), e.getString("product"), e.getDouble("market"), e.optString("source"))
        }
        return PriceBook(
            updated = o.optString("updated"),
            sealed = sealed,
            dealBelow = o.optDouble("dealBelow", 0.85),
            overpricedAbove = o.optDouble("overpricedAbove", 1.05),
        )
    }
}
