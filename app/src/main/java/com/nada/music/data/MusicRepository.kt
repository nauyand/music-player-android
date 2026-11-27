package com.nada.music.data

import com.nada.music.data.db.MusicDao
import com.nada.music.data.db.PlaylistEntity
import com.nada.music.data.db.PlaylistSongEntity
import com.nada.music.data.db.PlaylistSummary
import com.nada.music.data.db.toEntity
import com.nada.music.data.importer.PlaylistLink
import com.nada.music.data.importer.SpotifyPlaylist
import com.nada.music.data.importer.SpotifyTrack
import com.nada.music.data.importer.VideoLink
import com.nada.music.data.importer.pickBestMatch
import com.nada.music.data.local.LocalMusic
import com.nada.music.data.lyrics.LrcLib
import com.nada.music.data.lyrics.Lyrics
import com.nada.music.data.youtube.SearchResult
import com.nada.music.data.youtube.YouTubeMusic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.map
import org.schabi.newpipe.extractor.Page
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Satu pintu untuk semua data: YouTube Music, database lokal, dan lirik. */
class MusicRepository(
    private val dao: MusicDao,
    private val lrcLib: LrcLib,
    private val spotify: SpotifyPlaylist,
) {
    // ---- Online ----

    suspend fun search(query: String): SearchResult = YouTubeMusic.search(query)

    /** Pencarian YouTube biasa, untuk video yang tidak ada di katalog YouTube Music. */
    suspend fun searchYouTube(query: String): SearchResult = YouTubeMusic.searchYouTube(query)

    /**
     * Lagu dari link yang ditempel atau dibagikan: link video → lagu itu saja,
     * link playlist / mix YouTube → isi playlistnya. Null kalau teks bukan link.
     * @throws LinkError kalau link dikenali tapi tidak bisa dipakai di sini.
     */
    suspend fun songsFromLink(text: String): List<Song>? {
        VideoLink.parseId(text)?.let { return listOf(YouTubeMusic.video(it)) }
        when (val link = PlaylistLink.parse(text)) {
            is PlaylistLink.YouTube -> return YouTubeMusic.playlist(link.url, if (PlaylistLink.isEndlessMix(link.listId)) MAX_MIX_SONGS else MAX_PLAYLIST_SONGS).songs
            is PlaylistLink.Spotify -> throw LinkError("Link Spotify bisa diimpor lewat Pustaka → Impor playlist.")
            null -> Unit
        }
        if (VideoLink.isYouTubeLink(text)) throw LinkError("Link YouTube ini bukan link video atau playlist.")
        return null
    }

    /** Link dikenali tapi tidak bisa dibuka dari kolom cari. Pesannya langsung ditampilkan. */
    class LinkError(message: String) : Exception(message)

    suspend fun searchMore(query: String, filter: String, page: Page): SearchResult =
        YouTubeMusic.searchMore(query, filter, page)

    suspend fun suggestions(query: String): List<String> = YouTubeMusic.suggestions(query)

    /** Lagu terkait dari YouTube. Lagu lokal tidak punya lagu terkait. */
    suspend fun related(songId: String): List<Song> =
        if (LocalMusic.isLocal(songId)) emptyList() else YouTubeMusic.related(songId)

    suspend fun lyrics(song: Song): Lyrics = lrcLib.lyricsFor(song)

    // ---- Lokal ----

    val history: Flow<List<Song>> = dao.observeHistory(50).map { list -> list.map { it.toSong() } }

    val mostPlayed: Flow<List<Song>> = dao.observeMostPlayed(20).map { list -> list.map { it.toSong() } }

    val likedSongs: Flow<List<Song>> = dao.observeLikedSongs().map { list -> list.map { it.toSong() } }

    val playlists: Flow<List<PlaylistSummary>> = dao.observePlaylists()

    fun isLiked(songId: String): Flow<Boolean> = dao.observeLiked(songId).map { it == true }

    suspend fun recordPlay(song: Song) {
        dao.upsertSong(song.toEntity())
        dao.markPlayed(song.id, System.currentTimeMillis())
    }

    suspend fun setLiked(song: Song, liked: Boolean) {
        dao.upsertSong(song.toEntity())
        dao.setLiked(song.id, liked, if (liked) System.currentTimeMillis() else null)
    }

    suspend fun clearHistory() = dao.clearHistory()

    // ---- Musik lokal ----

    val localSongs: Flow<List<Song>> = dao.observeLocalSongs().map { list -> list.map { it.toSong() } }

    suspend fun addLocalSongs(songs: List<Song>) {
        val now = System.currentTimeMillis()
        songs.forEach { song ->
            dao.upsertSong(song.toEntity())
            dao.markLocal(song.id, now)
        }
    }

    suspend fun removeLocalSong(songId: String) = dao.unmarkLocal(songId)

    fun playlist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)

    fun playlistSongs(id: Long): Flow<List<Song>> =
        dao.observePlaylistSongs(id).map { list -> list.map { it.toSong() } }

    suspend fun createPlaylist(name: String): Long = dao.insertPlaylist(PlaylistEntity(name = name.trim()))

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name.trim())

    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    suspend fun addToPlaylist(playlistId: Long, song: Song) {
        dao.upsertSong(song.toEntity())
        dao.insertPlaylistSong(PlaylistSongEntity(playlistId, song.id))
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: String) =
        dao.removePlaylistSong(playlistId, songId)

    // ---- Pencocokan lagu Spotify ke YouTube ----

    /** Hasil pencarian yang sudah pernah dilakukan, supaya lagu yang sama tidak dicari berulang kali. */
    private val youTubeMatches = ConcurrentHashMap<String, Song>()

    /**
     * Mencari lagu Spotify di YouTube Music (artis + judul, dicocokkan dengan durasi).
     * Mengembalikan null kalau tidak ketemu atau jaringan bermasalah.
     */
    suspend fun findOnYouTube(track: SpotifyTrack): Song? {
        val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
        youTubeMatches[query.lowercase()]?.let { return it }
        val match = runCatching { pickBestMatch(YouTubeMusic.search(query).songs, track.durationSec) }.getOrNull()
        if (match != null) youTubeMatches[query.lowercase()] = match
        return match
    }

    // ---- Trending Indonesia ----

    private var chartCache: Pair<Long, List<SpotifyTrack>>? = null

    /**
     * Tangga lagu "Top 50 - Indonesia" dari Spotify (diperbarui Spotify setiap hari).
     * Disimpan di memori selama 1 jam supaya tidak diambil ulang setiap kali membuka beranda.
     */
    suspend fun topSongsIndonesia(forceRefresh: Boolean = false): List<SpotifyTrack> {
        val now = System.currentTimeMillis()
        chartCache?.let { (at, tracks) ->
            if (!forceRefresh && now - at < CHART_CACHE_MS) return tracks
        }
        val tracks = spotify.fetch(PlaylistLink.Spotify("playlist", TOP_50_INDONESIA)).tracks
        chartCache = now to tracks
        return tracks
    }

    // ---- Impor playlist ----

    data class ImportProgress(val message: String, val done: Int = 0, val total: Int = 0)

    data class ImportResult(
        val playlistId: Long,
        val name: String,
        val added: Int,
        /** Lagu Spotify yang tidak ketemu di YouTube, dalam format "Artis - Judul". */
        val notFound: List<String>,
    )

    /**
     * Mengimpor playlist YouTube / Spotify menjadi playlist lokal baru.
     * Lagu Spotify dicari satu per satu di YouTube Music (judul + artis, dicocokkan dengan durasi).
     */
    suspend fun importPlaylist(link: PlaylistLink, onProgress: (ImportProgress) -> Unit): ImportResult {
        val name: String
        val songs: List<Song>
        val notFound = mutableListOf<String>()

        when (link) {
            is PlaylistLink.YouTube -> {
                onProgress(ImportProgress("Mengambil playlist YouTube…"))
                val endless = PlaylistLink.isEndlessMix(link.listId)
                val content = YouTubeMusic.playlist(link.url, maxSongs = if (endless) MAX_MIX_SONGS else MAX_PLAYLIST_SONGS)
                name = content.name.ifBlank { if (endless) "Mix YouTube" else "Playlist YouTube" }
                songs = content.songs
            }
            is PlaylistLink.Spotify -> {
                onProgress(ImportProgress("Mengambil playlist Spotify…"))
                val content = spotify.fetch(link)
                name = content.name.ifBlank { "Playlist Spotify" }
                val total = content.tracks.size
                val done = AtomicInteger(0)
                onProgress(ImportProgress("Mencari lagu di YouTube…", 0, total))
                val gate = Semaphore(SEARCH_PARALLELISM)
                val matches = coroutineScope {
                    content.tracks.map { track ->
                        async {
                            val match = gate.withPermit { findOnYouTube(track) }
                            onProgress(ImportProgress("Mencari lagu di YouTube…", done.incrementAndGet(), total))
                            track to match
                        }
                    }.awaitAll()
                }
                songs = matches.mapNotNull { (track, match) ->
                    if (match == null) notFound += listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" - ")
                    match
                }.distinctBy { it.id }
            }
        }

        if (songs.isEmpty()) throw IllegalStateException("Tidak ada lagu yang bisa diimpor dari link ini")

        onProgress(ImportProgress("Menyimpan playlist…", songs.size, songs.size))
        val playlistId = createPlaylist(name)
        // addedAt dibuat berurutan supaya urutan lagu sama dengan playlist aslinya.
        val base = System.currentTimeMillis()
        songs.forEachIndexed { i, song ->
            dao.upsertSong(song.toEntity())
            dao.insertPlaylistSong(PlaylistSongEntity(playlistId, song.id, addedAt = base + i))
        }
        return ImportResult(playlistId, name, songs.size, notFound)
    }

    companion object {
        const val MAX_PLAYLIST_SONGS = 500
        const val MAX_MIX_SONGS = 100
        const val SEARCH_PARALLELISM = 4

        /** Playlist editorial Spotify "Top 50 - Indonesia". */
        const val TOP_50_INDONESIA = "37i9dQZEVXbObFQZ3JLcXt"
        const val CHART_CACHE_MS = 60 * 60 * 1000L
    }
}
