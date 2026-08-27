package com.musicdownloader.app.data.api

import kotlin.math.abs

/**
 * What the user actually asked for, used to decide whether a search result is the
 * same recording.
 *
 * [title], [artist] and [durationMs] come from Spotify and are trusted. They're null
 * for a free-text or YouTube-URL search, where only [rawQuery] is known and the
 * checks fall back to version-word filtering alone.
 */
data class TrackQuery(
    val rawQuery: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null
)

/**
 * Decides whether a search result is the *same recording* the user asked for.
 *
 * Every source (SoundCloud, Bandcamp, YouTube) is full of remixes, covers, live
 * takes, 8D edits and sped-up versions — and they often rank above or beside the
 * original. If the user didn't ask for one of those, handing them one silently is
 * worse than returning nothing, so callers should skip non-matching results rather
 * than falling back to "first result that downloads".
 */
object TrackMatch {

    // Different masters of the same recording drift by a few seconds; a cover or
    // remix essentially never lands this close.
    private const val DURATION_TOLERANCE_MS = 7_000L

    /**
     * Words that mark a track as a different rendition of a song rather than the
     * song itself. Matched on word boundaries so "Alive" isn't read as "live".
     */
    private val versionKeywords = listOf(
        "remix", "cover", "live", "acoustic", "instrumental", "karaoke", "nightcore",
        "sped up", "spedup", "speed up", "slowed", "reverb", "8d", "mashup", "bootleg",
        "flip", "vip", "tribute", "edit", "demo", "snippet", "teaser", "loop",
        "lofi", "lo-fi", "piano", "orchestral", "remake", "rework", "refix", "mix",
        "medley", "unplugged", "freestyle", "session"
    )

    fun normalize(s: String) = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Uploaders disguise version words with punctuation and digits ("FL!P", "R3MIX"),
     * so fold the usual substitutions back to letters before matching.
     */
    private fun deleet(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            sb.append(
                when (c) {
                    '!', '|', '1' -> 'i'
                    '3' -> 'e'
                    '0' -> 'o'
                    '4', '@' -> 'a'
                    '$', '5' -> 's'
                    '7' -> 't'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** Version words present in [text], e.g. "remix", "live". */
    fun versionWords(text: String): Set<String> = versionTags(text)

    private fun versionTags(text: String): Set<String> {
        val variants = listOf(text.lowercase(), deleet(text))
        return versionKeywords.filterTo(mutableSetOf()) { kw ->
            val re = Regex("(^|[^a-z0-9])" + Regex.escape(kw) + "($|[^a-z0-9])")
            variants.any { re.containsMatchIn(it) }
        }
    }

    fun artistMatches(candidateArtist: String, hint: String): Boolean {
        val a = normalize(candidateArtist)
        val h = normalize(hint.split(",").first())
        if (a.isEmpty() || h.isEmpty()) return false
        return a.contains(h) || h.contains(a) ||
            h.split(" ").filter { it.length > 2 }.any { w -> a.split(" ").any { it.startsWith(w) } }
    }

    /**
     * True when a result plausibly *is* the requested recording.
     *
     * Duration is the strongest signal, so it's decisive whenever Spotify gave us one.
     * [candidateArtist] is the uploader/album artist and may be null or unreliable —
     * the artist can also satisfy the check by appearing in the title.
     */
    fun matches(
        candidateTitle: String,
        candidateArtist: String?,
        candidateDurationMs: Long,
        query: TrackQuery
    ): Boolean {
        // Version words the user actually asked for are allowed through, so a track
        // genuinely titled "... (Live)" still matches.
        val wantedText = listOfNotNull(query.title ?: query.rawQuery, query.artist).joinToString(" ")
        val wantedTags = versionTags(wantedText)

        if ((versionTags(candidateTitle) - wantedTags).isNotEmpty()) return false

        // "Song A / Song B / Song C" is a medley, not the single track.
        if (candidateTitle.contains(" / ") && query.title?.contains(" / ") != true) return false

        val expected = query.durationMs
        if (expected != null && expected > 0 && candidateDurationMs > 0) {
            val tolerance = maxOf(DURATION_TOLERANCE_MS, expected / 20)
            if (abs(candidateDurationMs - expected) > tolerance) return false
        }

        // The song name should actually appear in the title.
        query.title?.let { wantedTitle ->
            val title = normalize(candidateTitle)
            val name = normalize(wantedTitle)
            if (name.isNotEmpty() && !title.contains(name)) {
                // Allow minor punctuation/word-order differences via token coverage.
                val nameWords = name.split(" ").filter { it.length > 2 }
                val covered = nameWords.count { title.contains(it) }
                if (nameWords.isEmpty() || covered < (nameWords.size + 1) / 2) return false
            }
        }

        // The artist should appear somewhere — the uploader/artist field or the title.
        query.artist?.let { wantedArtist ->
            val inTitle = normalize(candidateTitle)
                .contains(normalize(wantedArtist.split(",").first()))
            val inArtist = candidateArtist != null && artistMatches(candidateArtist, wantedArtist)
            if (!inTitle && !inArtist) return false

            if (creditsUnexpectedArtist(candidateTitle, wantedArtist, query.title)) return false
        }

        return true
    }

    /**
     * True when the title credits a performer the original doesn't, as in
     * "Billie Eilish, Justin Bieber - bad guy" — a guest-feature remix that carries
     * no version word and can sit within seconds of the original's duration.
     *
     * Only the artist run before the dash is examined, and only when the real
     * artist list doesn't already include the name, so genuine collaborations
     * ("Calvin Harris, Dua Lipa - One Kiss") still match.
     */
    private fun creditsUnexpectedArtist(
        candidateTitle: String,
        wantedArtist: String,
        wantedTitle: String?
    ): Boolean {
        val prefix = candidateTitle.substringBefore(" - ", "")
            .ifEmpty { candidateTitle.substringBefore("- ", "") }
        // A long prefix isn't an artist run — it's a sentence. Leave it alone.
        if (prefix.isEmpty() || prefix.length > 60) return false

        // The song name can legitimately sit before the dash ("Bad Guy - Billie Eilish").
        val known = normalize("$wantedArtist ${wantedTitle.orEmpty()}")
        val separators = Regex("""[,&/]|\bx\b|\bft\.?\b|\bfeat\.?\b|\bfeaturing\b|\bwith\b""",
            RegexOption.IGNORE_CASE)

        return prefix.split(separators).any { part ->
            val name = normalize(part)
            name.length > 2 && !known.contains(name)
        }
    }
}
