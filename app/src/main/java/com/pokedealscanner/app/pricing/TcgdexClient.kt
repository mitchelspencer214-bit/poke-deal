package com.pokedealscanner.app.pricing

import com.pokedealscanner.app.parse.CardCandidate
import com.pokedealscanner.app.parse.CardQuery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Single-card prices from TCGdex (free, no key; TCGplayer USD market prices).
 * Call from a background thread.
 */
class TcgdexClient(private val cacheDir: File) {

    /** Printings matching the query; empty = no match; null = service unreachable. */
    fun lookup(q: CardQuery): List<CardCandidate>? = try {
        val setId = q.set?.let { setIdFor(it) }

        // Exact card when we know set + number.
        if (setId != null && q.number != null) {
            val ids = listOf("$setId-${q.number}", "$setId-${q.number.padStart(3, '0')}").distinct()
            ids.firstNotNullOfOrNull { id -> get("$BASE/cards/$id")?.let { parseCard(JSONObject(it)) } }
                ?.let { return listOf(it) }
        }

        val name = enc(q.name)
        val url = if (setId != null) "$BASE/cards?name=$name&set.id=eq:$setId" else "$BASE/cards?name=eq:$name"
        val list = get(url)?.let { JSONArray(it) } ?: JSONArray()
        val ids = (0 until list.length())
            .map { list.getJSONObject(it).optString("id") }
            .filter { it.isNotEmpty() && !isPocket(it) }
            .take(MAX_DETAILS)
        // Fetch printings in parallel so a scan with several cards stays quick.
        val futures = ids.map { id -> pool.submit<CardCandidate?> { get("$BASE/cards/$id")?.let { parseCard(JSONObject(it)) } } }
        futures.mapNotNull { f ->
            try { f.get(TIMEOUT_MS.toLong() * 2, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } catch (e: java.util.concurrent.TimeoutException) { null }
        }
    } catch (_: IOException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }

    private fun parseCard(o: JSONObject): CardCandidate {
        val tcg = o.optJSONObject("pricing")?.optJSONObject("tcgplayer")
        var market: Double? = null
        if (tcg != null) {
            val keys = PRICE_KEYS + tcg.keys().asSequence().toList()
            for (k in keys) {
                val v = tcg.optJSONObject(k)?.optDouble("marketPrice", Double.NaN) ?: Double.NaN
                if (!v.isNaN() && v > 0) { market = v; break }
            }
        }
        return CardCandidate(o.optString("id"), o.optString("name"), o.optString("rarity").ifEmpty { null }, market)
    }

    // ---------- Set name -> TCGdex set id ----------

    private fun setIdFor(setName: String): String? = setMap()[norm(setName)]

    private fun setMap(): Map<String, String> {
        memorySets?.let { return it }
        val file = File(cacheDir, "tcgdex-sets.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < SET_CACHE_MS
        val json = if (fresh) file.readText() else {
            val body = get("$BASE/sets") ?: throw IOException("no set list")
            file.writeText(body)
            body
        }
        val arr = JSONArray(json)
        val map = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.optString("id")
            if (id.isEmpty() || isPocket(id)) continue
            map.putIfAbsent(norm(s.optString("name")), id)
        }
        memorySets = map
        return map
    }

    // ---------- HTTP ----------

    /** Body, or null on 404. Throws IOException for anything else. */
    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "PokeDeal/1.0 (Android)")
        return try {
            when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP ${conn.responseCode}")
            }
        } catch (_: FileNotFoundException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private const val BASE = "https://api.tcgdex.net/v2/en"
        private const val TIMEOUT_MS = 6000
        private const val MAX_DETAILS = 6
        private const val SET_CACHE_MS = 24L * 60 * 60 * 1000
        private val PRICE_KEYS = listOf("holofoil", "normal", "1st-edition-holofoil", "unlimited-holofoil", "reverse-holofoil")

        @Volatile private var memorySets: Map<String, String>? = null
        private val pool = Executors.newFixedThreadPool(6)

        /** TCG Pocket (the phone game) cards and sets aren't real cards. */
        private fun isPocket(id: String) = Regex("""^(A\d|B\d|P-A)""").containsMatchIn(id)

        private fun norm(s: String) = s.lowercase().replace("&", "and").replace("é", "e").filter { it.isLetterOrDigit() }
    }
}
