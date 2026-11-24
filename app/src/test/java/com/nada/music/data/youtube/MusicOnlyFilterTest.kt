package com.nada.music.data.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicOnlyFilterTest {

    private fun music(title: String, channel: String = "Artis", durationSec: Long = 210) =
        MusicOnlyFilter.isLikelyMusic(title, channel, durationSec)

    @Test
    fun keepsOrdinarySongs() {
        assertTrue(music("Tulus - Hati-Hati di Jalan (Official Music Video)"))
        assertTrue(music("Bohemian Rhapsody (Remastered 2011)", "Queen", 355))
        assertTrue(music("Good News", "Mac Miller", 342))
        assertTrue(music("Sial", "Mahalini"))
        assertTrue(music("Reflections", "The Neighbourhood"))
    }

    @Test
    fun dropsPodcastsAndTalkShows() {
        assertFalse(music("Close The Door Podcast: Ngobrol santai bareng musisi", durationSec = 600))
        assertFalse(music("Eps 12 - Cerita di balik lagu", durationSec = 540))
        assertFalse(music("Episode 3: Behind the album", durationSec = 480))
        assertFalse(music("Wawancara eksklusif dengan Tulus", durationSec = 420))
        assertFalse(music("Lagu baru!", channel = "Kumparan Podcast", durationSec = 300))
        assertFalse(music("REACTION to new single", durationSec = 400))
        assertFalse(music("Kenapa lagu ini viral? #shorts", durationSec = 90))
    }

    @Test
    fun dropsByDurationAndStreamType() {
        assertFalse(music("Lagu galau", durationSec = 45 * 60))
        assertFalse(music("Lagu galau", durationSec = 30))
        assertFalse(music("Lagu galau", durationSec = -1))
        assertFalse(MusicOnlyFilter.isLikelyMusic("Lagu", "Artis", 200, isLive = true))
        assertFalse(MusicOnlyFilter.isLikelyMusic("Lagu", "Artis", 200, isShort = true))
    }

    @Test
    fun keepsLongDjRemixesUpTo90Minutes() {
        assertTrue(music("DJ TikTok Viral Remix Full Bass 2026", durationSec = 30 * 60))
        assertTrue(music("Nonstop Breakbeat Terbaru", durationSec = 55 * 60))
        assertTrue(music("Funkot Jungle Dutch Mix", durationSec = 90 * 60))
        assertFalse(music("DJ Remix 3 Jam Nonstop", durationSec = 3 * 60 * 60))
        // Kata kunci podcast tetap menang walaupun ada "DJ".
        assertFalse(music("Podcast DJ ngobrol santai", durationSec = 30 * 60))
        // Video panjang tanpa kata kunci remix/mix tetap dibuang.
        assertFalse(music("Cerita perjalanan ke Bali", durationSec = 30 * 60))
        // "mix" harus kata utuh, bukan bagian kata lain.
        assertFalse(music("Mixology kelas dasar", durationSec = 30 * 60))
    }

    @Test
    fun matchesWholeWordsOnly() {
        // "episodes" di dalam kata lain atau "eps" tanpa nomor tidak boleh ikut terbuang.
        assertTrue(music("Keepsake"))
        assertTrue(music("Newsflash"))
        assertTrue(music("Previewing Love"))
    }
}
