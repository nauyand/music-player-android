package com.nada.music.data.youtube

import com.nada.music.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap

/** URL stream audio yang sudah siap diputar oleh ExoPlayer. */
data class ResolvedStream(
    val url: String,
    val itag: Int,
    val headers: Map<String, String>,
    val expiresAtMs: Long,
)

data class SearchResult(
    val songs: List<Song>,
    val nextPage: Page?,
    /** Filter yang benar-benar memberi hasil (songs atau videos). */
    val filter: String,
)

/**
 * Akses ke YouTube Music lewat NewPipeExtractor (InnerTube API).
 */
object YouTubeMusic {

    private val service get() = ServiceList.YouTube

    private class CachedInfo(val stream: ResolvedStream, val related: List<Song>)

    private val cache = ConcurrentHashMap<String, CachedInfo>()

    private val SEARCH_FILTERS = listOf(
        YoutubeSearchQueryHandlerFactory.MUSIC_SONGS,
        // Di beberapa wilayah / jaringan YouTube Music tidak memberi kategori "Songs".
        // Kalau kosong, pakai kategori "Videos" milik YouTube Music.
        YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS,
    )

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        for (filter in SEARCH_FILTERS) {
            val extractor = service.getSearchExtractor(query, listOf(filter), "")
            extractor.fetchPage()
            val page = extractor.initialPage
            val songs = page.items.mapNotNull { (it as? StreamInfoItem)?.toSong() }
            if (songs.isNotEmpty()) return@withContext SearchResult(songs, page.nextPage, filter)
        }
        SearchResult(emptyList(), null, SEARCH_FILTERS.first())
    }

    suspend fun searchMore(query: String, filter: String, page: Page): SearchResult =
        withContext(Dispatchers.IO) {
            val result = service.getSearchExtractor(query, listOf(filter), "").getPage(page)
            SearchResult(
                songs = result.items.mapNotNull { (it as? StreamInfoItem)?.toSong() },
                nextPage = result.nextPage,
                filter = filter,
            )
        }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    /** Judul dan lagu-lagu sebuah playlist / mix YouTube. */
    data class PlaylistContent(val name: String, val songs: List<Song>)

    /**
     * Mengambil isi playlist atau mix YouTube, halaman demi halaman, sampai [maxSongs] lagu.
     * Mix YouTube tidak ada ujungnya, jadi batas ini juga yang menghentikannya.
     */
    suspend fun playlist(url: String, maxSongs: Int): PlaylistContent = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(service, url)
        val songs = LinkedHashMap<String, Song>()
        fun addAll(items: List<*>) = items.forEach { item ->
            val song = (item as? StreamInfoItem)?.toSong() ?: return@forEach
            if (songs.size < maxSongs) songs.putIfAbsent(song.id, song)
        }
        addAll(info.relatedItems)
        var page = info.nextPage
        var emptyPages = 0
        while (page != null && songs.size < maxSongs && emptyPages < 3) {
            val before = songs.size
            val more = PlaylistInfo.getMoreItems(service, url, page)
            addAll(more.items)
            // Mix kadang mengulang lagu yang sama terus; berhenti kalau tidak ada lagu baru.
            emptyPages = if (songs.size == before) emptyPages + 1 else 0
            page = more.nextPage
        }
        PlaylistContent(info.name.orEmpty(), songs.values.toList())
    }

    /** Lagu terkait (dipakai untuk rekomendasi dan radio otomatis). */
    suspend fun related(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        cache[videoId]?.takeIf { it.related.isNotEmpty() }?.let { return@withContext it.related }
        fetch(videoId).related
    }

    /**
     * Mengambil URL audio untuk [videoId]. Blocking, panggil dari thread background
     * (ExoPlayer memanggilnya dari thread loader).
     */
    fun resolveStreamBlocking(videoId: String): ResolvedStream {
        cache[videoId]?.let { cached ->
            if (cached.stream.expiresAtMs > System.currentTimeMillis()) return cached.stream
        }
        return fetch(videoId).stream
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private fun fetch(videoId: String): CachedInfo {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val audio = pickBestAudio(info.audioStreams)
            ?: throw IllegalStateException("Tidak ada stream audio untuk $videoId")
        val url = audio.content
        val stream = ResolvedStream(
            url = url,
            itag = audio.itag,
            headers = headersFor(url),
            expiresAtMs = expiryOf(url),
        )
        // Hanya lagu: podcast, siaran langsung, Shorts, dan video panjang dibuang dari radio & rekomendasi.
        val related = info.relatedItems
            .mapNotNull { it as? StreamInfoItem }
            .filter { it.isLikelyMusic() }
            .mapNotNull { it.toSong() }
            .filter { it.id != videoId }
        return CachedInfo(stream, related).also { cache[videoId] = it }
    }

    private fun pickBestAudio(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            // Lewati trek dub/terjemahan otomatis kalau ada; ambil trek asli.
            .sortedWith(
                compareByDescending<AudioStream> { it.audioTrackType == null || it.isOriginalTrack() }
                    .thenByDescending { it.averageBitrate },
            )
            .firstOrNull()

    private fun AudioStream.isOriginalTrack(): Boolean =
        audioTrackType?.name == "ORIGINAL"

    /**
     * Header yang dibutuhkan server googlevideo, tergantung klien InnerTube mana
     * yang menghasilkan URL tersebut (logika ini sama dengan aplikasi NewPipe).
     */
    private fun headersFor(url: String): Map<String, String> = buildMap {
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            put("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null))
        } else {
            put("User-Agent", NewPipeDownloader.USER_AGENT)
        }
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
        }
    }

    /** URL googlevideo punya parameter `expire` (detik epoch). Simpan dengan margin 30 menit. */
    private fun expiryOf(url: String): Long {
        val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val fallback = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
        return expire?.let { it * 1000L - 30 * 60 * 1000L } ?: fallback
    }

    private fun StreamInfoItem.toSong(): Song? {
        val id = runCatching { service.streamLHFactory.getId(url) }.getOrNull() ?: return null
        return Song(
            id = id,
            title = name,
            artist = uploaderName.orEmpty().removeSuffix(" - Topic"),
            thumbnailUrl = thumbnails.bestUrl() ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationSec = duration,
        )
    }

    private fun StreamInfoItem.isLikelyMusic(): Boolean = MusicOnlyFilter.isLikelyMusic(
        title = name.orEmpty(),
        channel = uploaderName.orEmpty(),
        durationSec = duration,
        isLive = streamType in LIVE_TYPES,
        isShort = isShortFormContent,
    )

    private val LIVE_TYPES = setOf(
        StreamType.LIVE_STREAM,
        StreamType.AUDIO_LIVE_STREAM,
        StreamType.POST_LIVE_STREAM,
        StreamType.POST_LIVE_AUDIO_STREAM,
    )

    private fun List<Image>.bestUrl(): String? = maxByOrNull { it.height }?.url
}
