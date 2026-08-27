package com.musicdownloader.app.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download sources rank remixes, covers and live takes right beside the real
 * recording, so these cases pin down the boundary between "same song" and
 * "someone else's performance". Titles here are real search results.
 */
class TrackMatchTest {

    private fun query(title: String, artist: String, durationMs: Long) =
        TrackQuery(rawQuery = "$title $artist", title = title, artist = artist, durationMs = durationMs)

    private val badGuy = query("bad guy", "Billie Eilish", 194_087)
    private val shapeOfYou = query("Shape of You", "Ed Sheeran", 233_713)
    private val blindingLights = query("Blinding Lights", "The Weeknd", 200_040)
    private val creep = query("Creep", "Radiohead", 238_640)

    /**
     * [uploader] is the source's artist/channel field — SoundCloud and Bandcamp
     * supply one, YouTube doesn't, so it defaults to null (title-only matching).
     */
    private fun accepts(
        title: String,
        durationMs: Long,
        q: TrackQuery,
        uploader: String? = null
    ) = TrackMatch.matches(title, uploader, durationMs, q)

    @Test
    fun `accepts the original recording`() {
        assertTrue(accepts("Billie Eilish - bad guy (Lyrics)", 195_000, badGuy))
        assertTrue(accepts("Radiohead - Creep", 237_000, creep))
    }

    /** Official uploads are titled with just the song; the artist is the uploader. */
    @Test
    fun `accepts bare titles when the uploader is the artist`() {
        assertTrue(accepts("bad guy", 194_134, badGuy, uploader = "Billie Eilish"))
        assertTrue(accepts("Shape of You", 233_759, shapeOfYou, uploader = "Ed Sheeran"))
    }

    /** Without an uploader field there's nothing tying a bare title to the artist. */
    @Test
    fun `rejects bare titles when no artist is anywhere`() {
        assertFalse(accepts("bad guy", 194_134, badGuy))
    }

    @Test
    fun `accepts title and artist in either order`() {
        assertTrue(accepts("Bad Guy - Billie Eilish", 193_235, badGuy))
        assertTrue(accepts("Creep (RadioHead)", 238_000, creep))
        assertTrue(accepts("The Killers- Mr. Brightside", 223_054,
            query("Mr. Brightside", "The Killers", 222_586)))
    }

    @Test
    fun `rejects remixes covers and edits`() {
        assertFalse(accepts("Billie Eilish - bad guy (PatrickReza Remix)", 181_379, badGuy))
        assertFalse(accepts("Bad Guy - Billie Eilish (8D AUDIO) 2019", 193_097, badGuy))
        assertFalse(accepts("Ed Sheeran - Shape Of You | Oscar cover", 232_588, shapeOfYou))
        assertFalse(accepts("Blinding Lights (Instrumental)", 202_127, blindingLights))
        assertFalse(accepts("Radiohead - Creep (Best live performance)", 274_000, creep))
    }

    /** Version words hidden behind punctuation, e.g. "FL!P" for "flip". */
    @Test
    fun `rejects leetspeak version words`() {
        assertFalse(accepts("The Weeknd - Blinding Lights X Persona 3 FL!P", 203_028, blindingLights))
        assertFalse(accepts("Ed Sheeran - Shape of You (it's different Flip)", 191_921, shapeOfYou))
    }

    @Test
    fun `rejects medleys`() {
        assertFalse(accepts("Ed Sheeran & Sia - Shape of You / The Greatest", 236_015, shapeOfYou))
    }

    /** A guest-feature remix carries no version word and can match on duration. */
    @Test
    fun `rejects uploads crediting an artist the original does not`() {
        assertFalse(accepts("Billie Eilish, Justin Bieber - bad guy", 192_000, badGuy))
    }

    @Test
    fun `accepts genuine collaborations credited on the original`() {
        val oneKiss = query("One Kiss", "Calvin Harris, Dua Lipa", 214_000)
        assertTrue(accepts("Calvin Harris, Dua Lipa - One Kiss", 214_000, oneKiss))
    }

    /** If the user's own track is a live version, live results are what they want. */
    @Test
    fun `accepts version words the user asked for`() {
        val live = query("Where Did You Sleep Last Night (Live)", "Nirvana", 322_000)
        assertTrue(accepts("Nirvana - Where Did You Sleep Last Night (Live)", 322_000, live))
    }

    @Test
    fun `rejects results whose duration is far from the original`() {
        assertFalse(accepts("Creep - Radiohead", 256_000, creep))
        assertFalse(accepts("The Weeknd - Blinding Lights full", 762_820, blindingLights))
    }

    /** A free-text search has no trusted metadata, so only version words filter. */
    @Test
    fun `free text search still rejects obvious renditions`() {
        val free = TrackQuery(rawQuery = "bad guy billie eilish")
        assertTrue(accepts("Billie Eilish - bad guy", 0, free))
        assertFalse(accepts("Billie Eilish - bad guy (ISEEU Remix)", 0, free))
    }

    /**
     * Covers are usually uploaded under the bare song title with nothing in the name
     * to give them away, so duration is the only thing separating them from the
     * original. These are real SoundCloud results for "unwritten".
     */
    @Test
    fun `rejects untitled covers on duration alone`() {
        val unwritten = query("Unwritten", "Natasha Bedingfield", 259_333)
        assertTrue(accepts("Unwritten", 259_356, unwritten, uploader = "Natasha Bedingfield"))
        assertFalse(accepts("Unwritten", 231_526, unwritten, uploader = "Maoli"))
        assertFalse(accepts("Unwritten", 160_046, unwritten, uploader = "BACHLIL"))
        assertFalse(accepts("Unwritten", 125_623, unwritten, uploader = "AVA CROWN"))
        assertFalse(accepts("Unwritten - Natasha Bedingfield", 231_830, unwritten,
            uploader = "Carole-Anne Gagnon"))
    }
}
