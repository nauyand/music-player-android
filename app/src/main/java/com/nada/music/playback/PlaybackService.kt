package com.nada.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.nada.music.audio.AudioSettingsStore
import com.nada.music.audio.EffectsAudioProcessor
import android.content.Context
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nada.music.MainActivity
import com.nada.music.NadaApp
import com.nada.music.R
import com.nada.music.data.youtube.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import android.util.Log
import com.nada.music.data.Song
import com.nada.music.data.local.LocalMusic

/**
 * Service pemutar musik. Berjalan di background, menampilkan notifikasi media,
 * dan bisa dikontrol dari lockscreen, headset Bluetooth, atau Android Auto.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    /** Lagu yang sudah dicoba ulang setelah error, supaya tidak berulang tanpa henti. */
    private var retriedMediaId: String? = null
    private var radioLoadingFor: String? = null
    private val effects = EffectsAudioProcessor()

    override fun onCreate() {
        super.onCreate()
        val app = application as NadaApp

        val httpFactory = OkHttpDataSource.Factory(app.okHttpClient)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(PlayerCache.get(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // DefaultDataSource: content:// & file:// (musik lokal) dibaca langsung,
        // http(s) (YouTube) lewat cache + OkHttp.
        val routingFactory = DefaultDataSource.Factory(this, cacheFactory)
        val dataSourceFactory = ResolvingDataSource.Factory(routingFactory, StreamResolver())

        // Sisipkan prosesor efek (vokal, EQ, reverb) ke pipeline audio ExoPlayer.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(effects))
                    .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(PlayerListener())
        // Acak pintar dinyalakan: ganti lagu radio yang belum diputar dengan campuran baru.
        scope.launch {
            SmartShuffle.enabled.drop(1).collect { on -> if (on) refreshRadioForSmartShuffle() }
        }

        // Terapkan pengaturan audio setiap kali diubah dari layar Efek Audio.
        scope.launch {
            AudioSettingsStore.settings.collect { settings ->
                effects.apply(settings)
                player.playbackParameters = PlaybackParameters(settings.speed, settings.pitchFactor)
            }
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        /**
         * MediaController tidak mengirim URI ke session, jadi URI dibangun ulang
         * dari mediaId di sini.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.map { item ->
                    item.buildUpon()
                        .setUri(SongUri.of(item.mediaId))
                        .build()
                }.toMutableList(),
            )
    }

    private inner class PlayerListener : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            val song = mediaItem.toSong()
            scope.launch { (application as NadaApp).repository.recordPlay(song) }
            maybeExtendWithRadio()
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = player.currentMediaItem ?: return
            val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val isNetworkish = error.errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )

            // URL kedaluwarsa (403) atau gangguan jaringan: ambil URL baru lalu coba sekali lagi.
            if ((httpCode == 403 || isNetworkish) && retriedMediaId != current.mediaId) {
                retriedMediaId = current.mediaId
                YouTubeMusic.invalidate(current.mediaId)
                val position = player.currentPosition
                player.prepare()
                player.seekTo(position)
                player.play()
                return
            }

            // Masih gagal: lewati ke lagu berikutnya.
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retriedMediaId = null
        }
    }

    /** Lagu yang ditambahkan otomatis oleh radio (bukan dipilih pengguna). */
    private val radioIds = HashSet<String>()

    /**
     * Radio otomatis: saat lagu terakhir di antrean diputar, tambahkan lagu-lagu
     * terkait supaya musik tidak berhenti. Kalau "acak pintar" aktif, yang ditambahkan
     * campuran riwayat, favorit, dan lagu terkait (lihat [SmartShuffle]).
     */
    private fun maybeExtendWithRadio(force: Boolean = false) {
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.currentMediaItemIndex < player.mediaItemCount - 1) return
        val currentItem = player.currentMediaItem ?: return
        val currentId = currentItem.mediaId
        if (radioLoadingFor == currentId && !force) return
        radioLoadingFor = currentId

        scope.launch {
            val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
            val existing = queue.map { it.id }.toSet()
            val toAdd = runCatching {
                if (SmartShuffle.enabled.value) {
                    smartBatch(currentItem.toSong(), queue)
                } else {
                    (application as NadaApp).repository.related(currentId).filter { it.id !in existing }.take(10)
                }
            }.onFailure { Log.w(TAG, "Radio gagal", it) }.getOrDefault(emptyList())
            if (toAdd.isNotEmpty() && player.currentMediaItem?.mediaId == currentId) {
                radioIds += toAdd.map { it.id }
                player.addMediaItems(toAdd.map { it.toMediaItem() })
            }
        }
    }

    private suspend fun smartBatch(current: Song, queue: List<Song>): List<Song> = coroutineScope {
        val repo = (application as NadaApp).repository
        val personal = (repo.history.first() + repo.mostPlayed.first() + repo.likedSongs.first())
            .distinctBy { it.id }
        val currentKey = SmartShuffle.titleKey(current.title)
        val relatedNow = async { runCatching { repo.related(current.id) }.getOrDefault(emptyList()) }
        // Lagu "benih": lagu lain yang pernah didengar (atau, untuk pengguna baru, lagu terkait).
        val seedPool = personal.ifEmpty { relatedNow.await() }
            .filter { !LocalMusic.isLocal(it.id) && it.id != current.id && SmartShuffle.titleKey(it.title) != currentKey }
            .shuffled()
            .take(SEED_COUNT)
        val relatedSeeds = seedPool.map { seed -> async { runCatching { repo.related(seed.id) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
        SmartShuffle.mix(current, relatedNow.await(), relatedSeeds, personal, queue)
    }

    /**
     * Saat acak pintar baru dinyalakan: lagu-lagu radio yang belum diputar (sering isinya versi
     * lain dari lagu yang sama) diganti dengan campuran acak pintar.
     */
    private fun refreshRadioForSmartShuffle() {
        if (player.mediaItemCount == 0) return
        val current = player.currentMediaItemIndex
        for (i in player.mediaItemCount - 1 downTo current + 1) {
            if (player.getMediaItemAt(i).mediaId in radioIds) player.removeMediaItem(i)
        }
        maybeExtendWithRadio(force = true)
    }

    private companion object {
        const val TAG = "PlaybackService"

        /** Berapa lagu dari riwayat yang dipakai sebagai sumber lagu terkait. */
        const val SEED_COUNT = 2
    }
}
