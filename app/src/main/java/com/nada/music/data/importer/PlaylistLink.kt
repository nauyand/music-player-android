package com.nada.music.data.importer

import com.nada.music.data.Song
import kotlin.math.abs

/** Link playlist yang bisa diimpor. */
sealed interface PlaylistLink {

    /** Playlist atau mix YouTube / YouTube Music. [url] sudah dirapikan untuk NewPipeExtractor. */
    data class YouTube(val listId: String, val url: String) : PlaylistLink

    /** Playlist atau album Spotify. [kind] berisi "playlist" atau "album". */
    data class Spotify(val kind: String, val id: String) : PlaylistLink

    companion object {

        private val SPOTIFY = Regex(
            "(?:open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{22})",
        )
        private val YOUTUBE_HOST = Regex("(?:^|[/.])(?:youtube\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE)
        private val LIST_PARAM = Regex("[?&]list=([A-Za-z0-9_-]+)")
        private val VIDEO_PARAM = Regex("[?&]v=([A-Za-z0-9_-]{11})")
        private val SHORT_VIDEO = Regex("youtu\\.be/([A-Za-z0-9_-]{11})")

        /**
         * Membaca link dari teks yang ditempel atau dibagikan. Teks boleh berisi kata lain
         * (aplikasi Spotify sering menambahkan judul di depan link).
         * Mengembalikan null kalau tidak ada link playlist yang dikenali.
         */
        fun parse(text: String): PlaylistLink? {
            SPOTIFY.find(text)?.let { return Spotify(it.groupValues[1], it.groupValues[2]) }

            val url = text.split(Regex("\\s+")).firstOrNull { YOUTUBE_HOST.containsMatchIn(it) } ?: return null
            val listId = LIST_PARAM.find(url)?.groupValues?.get(1) ?: return null
            val videoId = VIDEO_PARAM.find(url)?.groupValues?.get(1)
                ?: SHORT_VIDEO.find(url)?.groupValues?.get(1)
                // Mix dari satu lagu: "RD" + id video 11 karakter, misalnya RDlCIX4hd_geU.
                ?: listId.takeIf { it.length == 13 && it.startsWith("RD") }?.substring(2)
            // Mix ("RD...") butuh lagu awalnya; playlist biasa cukup id-nya.
            val clean = if (listId.startsWith("RD") && videoId != null) {
                "https://www.youtube.com/watch?v=$videoId&list=$listId"
            } else {
                "https://www.youtube.com/playlist?list=$listId"
            }
            return YouTube(listId, clean)
        }

        /** Mix YouTube ("RD...") tidak ada ujungnya, jadi jumlah lagu yang diambil dibatasi. */
        fun isEndlessMix(listId: String): Boolean = listId.startsWith("RD") && !listId.startsWith("RDCLAK")
    }
}

/**
 * Memilih hasil pencarian YouTube yang paling cocok dengan lagu Spotify.
 * Hasil teratas biasanya sudah benar; durasi dipakai untuk menghindari versi live, remix,
 * atau versi panjang kalau ada hasil lain yang durasinya lebih pas.
 */
fun pickBestMatch(candidates: List<Song>, targetDurationSec: Long, toleranceSec: Long = 15): Song? {
    if (candidates.isEmpty()) return null
    if (targetDurationSec <= 0) return candidates.first()
    val top = candidates.take(5)
    return top.firstOrNull { it.durationSec > 0 && abs(it.durationSec - targetDurationSec) <= toleranceSec }
        ?: top.first()
}
