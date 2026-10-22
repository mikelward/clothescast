package app.clothescast.alerts

/** A web address in an issuer's alert text: where it sits, and what to open. */
internal data class AlertLink(val range: IntRange, val url: String)

// An http(s) address, or one starting "www.", not glued to a word before it.
private val WEB_ADDRESS = Regex("""(?<![\w.@/-])(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)

// Punctuation that ends the sentence around an address rather than the address.
private const val TRAILING = ".,;:!?'\")]}"

/**
 * The web addresses in [text], so the card can make them tappable: those
 * starting `http://`, `https://` or `www.` (opened as https), less any
 * sentence punctuation they end on. Nothing else in issuer text is linked.
 */
internal fun alertLinks(text: String): List<AlertLink> =
    WEB_ADDRESS.findAll(text).mapNotNull { match ->
        val address = match.value.trimEnd { it in TRAILING }
        if (address.length <= "www.".length || address.endsWith("://")) return@mapNotNull null
        val url = if (address.startsWith("www.", ignoreCase = true)) "https://$address" else address
        AlertLink(match.range.first until match.range.first + address.length, url)
    }.toList()
