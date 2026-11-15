package com.pokedealscanner.app.parse

/**
 * Static knowledge about Pokémon products: set names, nicknames, product types.
 * Kept as plain data so it can be updated without touching parsing logic.
 */
object PokemonCatalog {

    /** Canonical set name -> extra names/nicknames sellers use. */
    val SETS: Map<String, List<String>> = linkedMapOf(
        // Mega Evolution era
        "Mega Evolution" to listOf(),
        "Phantasmal Flames" to listOf("PFL"),
        "Ascended Heroes" to listOf("ASC"),
        "Perfect Order" to listOf(),
        "Chaos Rising" to listOf(),
        "Pitch Black" to listOf(),
        "30th Celebration" to listOf("30th anniversary"),
        "Delta Reign" to listOf(),
        // Scarlet & Violet era
        "Scarlet & Violet" to listOf("Scarlet and Violet", "SV base"),
        "Paldea Evolved" to listOf(),
        "Obsidian Flames" to listOf(),
        "151" to listOf("SV151", "Pokemon 151", "Pokémon 151"),
        "Paradox Rift" to listOf(),
        "Paldean Fates" to listOf(),
        "Temporal Forces" to listOf(),
        "Twilight Masquerade" to listOf(),
        "Shrouded Fable" to listOf(),
        "Stellar Crown" to listOf(),
        "Surging Sparks" to listOf(),
        "Prismatic Evolutions" to listOf("Prismatic", "Pris Evo", "PRE"),
        "Journey Together" to listOf(),
        "Destined Rivals" to listOf(),
        "Black Bolt" to listOf(),
        "White Flare" to listOf(),
        // Sword & Shield era
        "Sword & Shield" to listOf("Sword and Shield"),
        "Rebel Clash" to listOf(),
        "Darkness Ablaze" to listOf(),
        "Champion's Path" to listOf("Champions Path"),
        "Vivid Voltage" to listOf(),
        "Shining Fates" to listOf(),
        "Battle Styles" to listOf(),
        "Chilling Reign" to listOf(),
        "Evolving Skies" to listOf("Evo Skies", "EVS"),
        "Celebrations" to listOf(),
        "Fusion Strike" to listOf(),
        "Brilliant Stars" to listOf(),
        "Astral Radiance" to listOf(),
        "Pokémon GO" to listOf("Pokemon GO"),
        "Lost Origin" to listOf(),
        "Silver Tempest" to listOf(),
        "Crown Zenith" to listOf(),
        // Older favorites
        "Hidden Fates" to listOf(),
        "Base Set" to listOf(),
        "Jungle" to listOf(),
        "Fossil" to listOf(),
        "Team Rocket" to listOf(),
        "Neo Genesis" to listOf(),
    )

    /** Card nicknames -> (card, set). */
    val CARD_NICKNAMES: Map<String, Pair<String, String>> = mapOf(
        "moonbreon" to ("Umbreon VMAX Alt Art" to "Evolving Skies"),
    )

    /** Nickname -> exact card for price lookups: (card name, set, card number). */
    val NICKNAME_CARDS: Map<String, Triple<String, String, String>> = mapOf(
        "moonbreon" to Triple("Umbreon VMAX", "Evolving Skies", "215"),
    )

    /** Product type label -> phrases that identify it (lowercase). */
    val PRODUCT_TYPES: List<Pair<String, List<String>>> = listOf(
        "Elite Trainer Box" to listOf("elite trainer box", "elite trainer boxes", "etb", "etbs", "pc etb", "pokemon center etb"),
        "Booster Box" to listOf("booster box"),
        "Booster Bundle" to listOf("booster bundle", "bundle of packs"),
        "Ultra Premium Collection" to listOf("ultra premium collection", "upc"),
        "Super Premium Collection" to listOf("super premium collection", "spc"),
        "Booster Pack" to listOf("booster pack", "booster packs", "pack", "packs"),
        "Blister" to listOf("blister", "3 pack blister", "3-pack"),
        "Tin" to listOf("tin", "mini tin", "mini tins"),
        "Collection Box" to listOf("collection box", "premium collection", "binder collection", "poster collection", "surprise box"),
        "Sleeved Booster" to listOf("sleeved booster"),
    )

    /**
     * Very conservative floor prices for sealed English products.
     * A price under these is rarely legit; it's usually an ad, a fake, or a scam.
     */
    val SEALED_FLOOR: Map<String, Double> = mapOf(
        "Elite Trainer Box" to 30.0,
        "Booster Box" to 70.0,
        "Booster Bundle" to 15.0,
        "Ultra Premium Collection" to 60.0,
        "Super Premium Collection" to 40.0,
    )

    val GRADERS = listOf("PSA", "BGS", "CGC", "SGC", "TAG", "ACE")

    val RARITY_TAGS: List<Pair<String, Regex>> = listOf(
        "Special Illustration Rare" to Regex("""\b(sir|special illustration( rare)?)\b""", RegexOption.IGNORE_CASE),
        "Illustration Rare" to Regex("""\b(ir|illustration rare)\b""", RegexOption.IGNORE_CASE),
        "Hyper Rare" to Regex("""\b(hyper rare|gold)\b""", RegexOption.IGNORE_CASE),
        "Alt Art" to Regex("""\b(alt art|alternate art|aa)\b""", RegexOption.IGNORE_CASE),
        "Full Art" to Regex("""\b(full art|fa)\b""", RegexOption.IGNORE_CASE),
        "1st Edition" to Regex("""\b(1st ed(ition)?|first edition)\b""", RegexOption.IGNORE_CASE),
    )
}
