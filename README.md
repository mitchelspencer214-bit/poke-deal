# Poke Deal

An Android floating scanner for Pokémon listings on Facebook Marketplace.

## How it works
1. Tap the floating **P** button on a listing.
2. Approve screen sharing (Entire screen).
3. Poke Deal captures one frame, reads it on-device (ML Kit OCR), and organizes it:
   - **Title** and **main price**, plus what the main price likely covers
     (title item, whole lot, one listed item, placeholder, or unclear) and why
   - **Items** from the description, each with its own price, quantity, grade, set, product type, and rarity
   - **Warnings**: sponsored ads, sealed products priced far below normal, descriptions cut off by "See more"
4. Results show in a notification and under **Last Scan**, with the raw screen text below.

## Full-page reading (recommended)
Turn on **Poke Deal full-page reading** in Android Accessibility settings (button in the app).
Then tapping P reads the listing's text directly (no OCR typos), taps "See more",
scrolls through the whole description, and scrolls back. No screen-sharing prompt.
If an app hides its text, P falls back to screen capture + OCR automatically.

## Install the latest test build
Open on your phone: https://github.com/mitchelspencer214-bit/poke-deal/releases/download/latest/Poke-Deal-debug.apk

## Code
- `parse/ListingParser.kt`: turns OCR lines into a structured listing (pure Kotlin, unit-tested)
- `parse/PokemonCatalog.kt`: set names, nicknames, product types, sealed price floors
- `ScreenCaptureService.kt`: screen capture + OCR
- `FloatingButtonService.kt`: the floating P button

## Not built yet
- Market price lookup and deal scoring
