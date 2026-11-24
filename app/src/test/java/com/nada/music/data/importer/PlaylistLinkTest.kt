package com.nada.music.data.importer

import com.nada.music.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLinkTest {

    @Test
    fun parsesYouTubeMixFromSingleSong() {
        val link = PlaylistLink.parse("https://youtube.com/playlist?list=RDlCIX4hd_geU&playnext=1&si=zH8eNy5HR6JgJG-6")
        assertEquals(
            PlaylistLink.YouTube("RDlCIX4hd_geU", "https://www.youtube.com/watch?v=lCIX4hd_geU&list=RDlCIX4hd_geU"),
            link,
        )
        assertTrue(PlaylistLink.isEndlessMix("RDlCIX4hd_geU"))
    }

    @Test
    fun parsesRegularYouTubeAndMusicPlaylists() {
        assertEquals(
            PlaylistLink.YouTube("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", "https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"),
            PlaylistLink.parse("https://music.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&si=abc"),
        )
        val watch = PlaylistLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123abc_-")
        assertEquals("https://www.youtube.com/playlist?list=PL123abc_-", (watch as PlaylistLink.YouTube).url)
        // Playlist album YouTube Music ("RDCLAK...") bukan mix tanpa ujung.
        assertFalse(PlaylistLink.isEndlessMix("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"))
    }

    @Test
    fun parsesSpotifyLinks() {
        val expected = PlaylistLink.Spotify("playlist", "1QnRPeyCIo3V8EOLYyrqJI")
        assertEquals(
            expected,
            PlaylistLink.parse(
                "https://open.spotify.com/playlist/1QnRPeyCIo3V8EOLYyrqJI?si=nSjCBCD2TbSBwP_T2W0skQ&utm_source=copy-link",
            ),
        )
        assertEquals(expected, PlaylistLink.parse("Dengarkan RomanticHomicide di Spotify: https://open.spotify.com/intl-id/playlist/1QnRPeyCIo3V8EOLYyrqJI"))
        assertEquals(expected, PlaylistLink.parse("spotify:playlist:1QnRPeyCIo3V8EOLYyrqJI"))
        assertEquals(
            PlaylistLink.Spotify("album", "4aawyAB9vmqN3uQ7FjRGTy"),
            PlaylistLink.parse("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"),
        )
    }

    @Test
    fun rejectsUnknownLinks() {
        assertNull(PlaylistLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(PlaylistLink.parse("https://open.spotify.com/track/1sVQuuvFDKu6vklPVeiUXT"))
        assertNull(PlaylistLink.parse("halo"))
    }

    @Test
    fun picksCandidateWithMatchingDuration() {
        val live = Song("a", "Lagu (Live)", "Artis", null, 320)
        val studio = Song("b", "Lagu", "Artis", null, 202)
        assertEquals(studio, pickBestMatch(listOf(live, studio), targetDurationSec = 202))
        // Tidak ada yang durasinya cocok: pakai hasil teratas.
        assertEquals(live, pickBestMatch(listOf(live, studio), targetDurationSec = 100))
        assertNull(pickBestMatch(emptyList(), 200))
    }
}
