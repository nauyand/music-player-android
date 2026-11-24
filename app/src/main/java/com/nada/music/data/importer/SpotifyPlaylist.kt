package com.nada.music.data.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Satu lagu dari playlist Spotify: hanya metadata, audionya nanti dicari di YouTube. */
data class SpotifyTrack(val title: String, val artist: String, val durationSec: Long)

data class SpotifyPlaylistContent(val name: String, val tracks: List<SpotifyTrack>)

/**
 * Membaca isi playlist / album Spotify publik tanpa login dan tanpa API key,
 * lewat halaman "embed" (pemutar mini yang biasa ditempel di website).
 *
 * Batasan: hanya playlist publik, dan halaman embed hanya memuat sekitar 100 lagu pertama.
 */
class SpotifyPlaylist(private val client: OkHttpClient) {

    suspend fun fetch(link: PlaylistLink.Spotify): SpotifyPlaylistContent = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/${link.kind}/${link.id}")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "id,en;q=0.8")
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    if (response.code == 404) "Playlist Spotify tidak ditemukan atau bersifat privat"
                    else "Spotify membalas HTTP ${response.code}",
                )
            }
            response.body?.string().orEmpty()
        }
        parse(html)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private val NEXT_DATA = Regex(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL,
        )

        fun parse(html: String): SpotifyPlaylistContent {
            val json = NEXT_DATA.find(html)?.groupValues?.get(1)
                ?: throw IllegalStateException("Format halaman Spotify berubah, isi playlist tidak terbaca")
            val entity = JSONObject(json)
                .getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
            val list = entity.optJSONArray("trackList")
            val tracks = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                val t = list!!.getJSONObject(i)
                val title = t.optString("title").trim()
                if (title.isEmpty()) return@mapNotNull null
                SpotifyTrack(
                    title = title,
                    // Nama artis dipisah koma dengan spasi tak terputus (U+00A0).
                    artist = t.optString("subtitle").replace(' ', ' ').trim(),
                    durationSec = t.optLong("duration", 0L) / 1000,
                )
            }
            val name = entity.optString("name").ifBlank { entity.optString("title") }
            return SpotifyPlaylistContent(name, tracks)
        }
    }
}
