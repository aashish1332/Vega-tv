package tv.vega.tv.data.repo

import tv.vega.tv.data.model.VegaPost
import java.text.Normalizer

/**
 * Metadata-only safety filter. Provider feeds do not expose age certificates
 * consistently, so this matches the words those listings do publish (titles,
 * tags, corner badges and URLs) across every installed provider. Adult catalog
 * rows are removed before their posts can appear in mixed "Latest" rows, and
 * those posts' links/images are quarantined from the remaining rows.
 */
object FamilyFilter {
    private val BLOCKED = setOf(
        "18+", "19+", "21+", "xxx", "x rated", "x-rated",
        "porn", "porno", "pornography", "pornstar", "pornstars", "nsfw",
        "adult", "adults", "hentai", "ecchi", "erotic", "erotica",
        "nudity", "nude", "nudes", "naked",
        "sex", "sexual", "sexy", "fetish", "bdsm", "incest",
        "stepmom", "stepdad", "stepbrother", "stepsister", "stepdaughter", "stepson",
        "onlyfans", "only fans", "sugar daddy", "sugar baby",
        "milf", "dilf", "camgirl", "cam sex", "sex cam", "live sex",
        "escort", "orgy", "threesome", "striptease", "strip club", "brothel",
        "anal", "orgasm", "orgasms", "masturbation", "blowjob", "blow job",
        "handjob", "hand job", "cumshot", "cum shot", "creampie", "cream pie",
        "gangbang", "gang bang", "deepthroat", "deep throat",
        "pussy", "tits", "boobs", "boob", "nipple", "nipples", "vagina", "penis",
        "busty", "sensual", "seduction", "seductive", "lust", "pleasure",
        "massage parlor", "lingerie", "voyeur", "swinger", "swingers",
        "cuckold", "dominatrix", "sadomasochism", "paraphilia",
        "prostitute", "prostitution", "rape", "molest",
        "brazzers", "pornhub", "xvideos", "xnxx", "xhamster", "redtube",
        "youporn", "bangbros", "realitykings", "mofos", "naughty america",
        "chudai", "chodai", "choda", "chud", "lund", "loda", "lauda",
        "randi", "raand", "bhosda", "bhosdi", "madarchod", "behenchod",
        "bhenchod", "gandu", "gaand", "chuchi", "chuch", "ashleel", "ashlil",
        "nangi", "nanga", "bhabhi", "bhabi", "aunty", "aunties",
        "suhagraat", "kamasutra", "kama sutra", "vasna", "kamvasna", "mallu", "bold",
    )

    private val BLOCKED_WORD = Regex(
        "(?i)(?<![a-z0-9])(?:" +
            BLOCKED.joinToString("|") { Regex.escape(it) } +
            ")(?![a-z0-9])",
    )
    private val AGE_RATING = Regex("""(?i)(?<!\d)(?:18|19|21)\s*\+""")
    private val TV_MA = Regex("""(?i)\btv[- ]?ma\b""")
    private val ADULT_CERTIFICATE =
        Regex("""(?i)^(?:a|r|x|nc-17|tv-ma|18|18\+|19|19\+|21|21\+|ma\s*15\+)$""")

    private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
    private val NOISE = Regex(
        """(?i)\b(?:complete|full|movie|series|watch|online|free|download|""" +
            """hd|hindi|dubbed|dual|audio|webrip|web[.\-]?dl|bluray|brrip|""" +
            """hdrip|dvdrip|hdtv|480p|720p|1080p|2160p|4k|uhd|hevc|x264|x265)\b""",
    )
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun isAllowed(title: String): Boolean = !blocked(title)

    fun isAllowed(post: VegaPost): Boolean {
        val certificates = listOf(post.tag, post.cornerTag, post.badge)
        if (certificates.any { badge -> ADULT_CERTIFICATE.matches(badge?.trim().orEmpty()) }) {
            return false
        }
        val text = buildString {
            append(post.title)
            append(' ')
            append(post.tag.orEmpty())
            append(' ')
            append(post.cornerTag.orEmpty())
            append(' ')
            append(post.badge.orEmpty())
            // Some providers put the category in the link/JSON payload rather
            // than a badge, and CDN paths often carry the section name.
            append(' ')
            append(post.link)
            append(' ')
            append(post.image)
        }
        return !blocked(text)
    }

    /**
     * A catalog row ("Latest", "18+", "Adult Movies") is part of the provider's
     * own taxonomy. Checking both its display title and filter URL lets the
     * repository drop whole adult sections before they contribute cards to the
     * home feed.
     */
    fun isAllowedCatalog(title: String, filter: String): Boolean =
        !blocked("$title $filter")

    fun dedupeKey(title: String): String {
        val cleaned = normalize(title)
            .replace(NOISE, " ")
            .replace(YEAR, " ")
            .replace(NON_ALNUM, " ")
            .trim()
        return cleaned.ifBlank { normalize(title).trim() }
    }

    private fun blocked(raw: String): Boolean {
        if (raw.isBlank()) return false
        val text = normalize(raw)
        return AGE_RATING.containsMatchIn(text) ||
            TV_MA.containsMatchIn(text) ||
            BLOCKED_WORD.containsMatchIn(text)
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
}
