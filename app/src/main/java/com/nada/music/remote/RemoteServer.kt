package com.nada.music.remote

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.Player
import com.nada.music.NadaApp
import com.nada.music.data.Song
import com.nada.music.playback.PlayerConnection
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Server web kecil di HP supaya musik bisa dikendalikan dari browser PC / laptop / HP lain
 * yang satu Wi-Fi (atau tersambung ke hotspot HP ini).
 *
 * - `GET /`                 halaman remote (assets/remote/index.html)
 * - `GET /api/state`        lagu sekarang, posisi, volume, antrean
 * - `POST /api/cmd`         {action: play|pause|toggle|next|prev|seek|volume|shuffle|repeat|skip|remove|restart}
 * - `GET /api/search?q=`    cari lagu
 * - `GET /api/library`      lagu disukai, riwayat, daftar playlist
 * - `GET /api/playlist?id=` isi playlist
 * - `POST /api/play`        {songs: [...], index, shuffle} putar daftar lagu
 * - `POST /api/enqueue`     {song, next} tambahkan ke antrean
 *
 * Semua `/api/...` butuh header `X-Pin` berisi PIN yang tampil di aplikasi, supaya orang lain
 * di Wi-Fi yang sama (mis. Wi-Fi kafe) tidak bisa ikut mengatur musik.
 */
class RemoteServer(
    private val context: Context,
    private val player: PlayerConnection,
    private val pin: String,
    port: Int,
) : NanoHTTPD(port) {

    private val app = context.applicationContext as NadaApp
    private val audio = context.getSystemService(AudioManager::class.java)

    private var failedAttempts = 0
    private var lockedUntil = 0L

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        Log.w(TAG, "Permintaan gagal: ${session.uri}", e)
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: e.javaClass.simpleName))
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith("/api/")) return page()

        if (!checkPin(session)) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "PIN salah"))
        }
        return when (uri) {
            "/api/state" -> json(state())
            "/api/cmd" -> json(command(body(session)))
            "/api/search" -> json(search(session.parameters["q"]?.firstOrNull().orEmpty()))
            "/api/library" -> json(library())
            "/api/playlist" -> json(playlist(session.parameters["id"]?.firstOrNull()?.toLongOrNull() ?: -1))
            "/api/play" -> json(play(body(session)))
            "/api/enqueue" -> json(enqueue(body(session)))
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "Tidak ada"))
        }
    }

    // ---- Keamanan ----

    @Synchronized
    private fun checkPin(session: IHTTPSession): Boolean {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return false
        val given = session.headers["x-pin"]
        if (given == pin) {
            failedAttempts = 0
            return true
        }
        // Batasi tebak-tebakan PIN: 10 kali salah → dikunci 1 menit.
        if (++failedAttempts >= MAX_FAILED) {
            failedAttempts = 0
            lockedUntil = now + LOCK_MS
        }
        return false
    }

    // ---- Halaman ----

    private fun page(): Response {
        val html = context.assets.open("remote/index.html").use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.inputStream(), html.size.toLong())
            .apply { addHeader("Cache-Control", "no-cache") }
    }

    // ---- API ----

    /** Semua akses ke pemutar (MediaController) harus di main thread. */
    private fun <T> onMain(block: suspend () -> T): T = runBlocking(Dispatchers.Main) { block() }

    private fun state(): JSONObject = onMain {
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        JSONObject()
            .put("connected", player.isConnected)
            .put("song", player.currentSong.value?.toJson() ?: JSONObject.NULL)
            .put("playing", player.isPlaying.value)
            .put("buffering", player.isBuffering.value)
            .put("position", player.position)
            .put("duration", player.duration)
            .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVol)
            .put("shuffle", player.shuffle.value)
            .put("repeat", repeatName(player.repeatMode.value))
            .put("index", player.currentIndex.value)
            .put("queue", player.queue.value.toJson())
            .put("error", player.error.value ?: JSONObject.NULL)
    }

    private fun command(body: JSONObject): JSONObject {
        val action = body.optString("action")
        onMain {
            when (action) {
                "play" -> player.play()
                "pause" -> player.pause()
                "toggle" -> player.togglePlayPause()
                "next" -> player.next()
                "prev" -> player.previous()
                "restart" -> player.restart()
                "seek" -> player.seekTo(body.optLong("ms"))
                "seekBy" -> player.seekBy(body.optLong("ms"))
                "shuffle" -> player.setShuffle(if (body.has("value")) body.optBoolean("value") else !player.shuffle.value)
                "repeat" -> if (body.has("value")) {
                    player.setRepeat(repeatMode(body.optString("value")))
                } else {
                    player.cycleRepeat()
                }
                "skip" -> player.skipTo(body.optInt("index"))
                "remove" -> player.removeFromQueue(body.optInt("index"))
                "volume" -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val pct = body.optInt("value").coerceIn(0, 100)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (pct / 100f * max).roundToInt(), 0)
                }
                "volumeUp" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                "volumeDown" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                else -> throw IllegalArgumentException("Perintah tidak dikenal: $action")
            }
        }
        return state()
    }

    private fun search(query: String): JSONObject {
        if (query.isBlank()) return JSONObject().put("songs", JSONArray())
        val songs = runBlocking { withTimeout(SEARCH_TIMEOUT_MS) { app.repository.search(query).songs } }
        return JSONObject().put("songs", songs.toJson())
    }

    private fun library(): JSONObject = runBlocking {
        val repo = app.repository
        JSONObject()
            .put("liked", repo.likedSongs.first().toJson())
            .put("history", repo.history.first().toJson())
            .put(
                "playlists",
                JSONArray().apply {
                    repo.playlists.first().forEach {
                        put(
                            JSONObject()
                                .put("id", it.id)
                                .put("name", it.name)
                                .put("count", it.songCount)
                                .put("thumb", it.thumbnailUrl ?: JSONObject.NULL),
                        )
                    }
                },
            )
    }

    private fun playlist(id: Long): JSONObject = runBlocking {
        JSONObject().put("songs", app.repository.playlistSongs(id).first().toJson())
    }

    private fun play(body: JSONObject): JSONObject {
        val songs = body.optJSONArray("songs")?.toSongs().orEmpty()
        require(songs.isNotEmpty()) { "Tidak ada lagu" }
        val index = body.optInt("index").coerceIn(0, songs.lastIndex)
        onMain { player.playSongs(songs, index, body.optBoolean("shuffle")) }
        return state()
    }

    private fun enqueue(body: JSONObject): JSONObject {
        val song = body.optJSONObject("song")?.toSong() ?: throw IllegalArgumentException("Tidak ada lagu")
        onMain { if (body.optBoolean("next")) player.playNext(song) else player.addToQueue(song) }
        return state()
    }

    // ---- Bantuan ----

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun json(obj: JSONObject) = json(Response.Status.OK, obj)

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
            .apply { addHeader("Cache-Control", "no-store") }

    private fun Song.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        // Gambar lagu lokal (content://) tidak bisa dibuka browser PC.
        .put("thumb", thumbnailUrl?.takeIf { it.startsWith("http") } ?: JSONObject.NULL)
        .put("duration", durationSec)

    private fun List<Song>.toJson(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }

    private fun JSONObject.toSong(): Song? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        return Song(
            id = id,
            title = optString("title"),
            artist = optString("artist"),
            thumbnailUrl = optString("thumb").takeIf { it.startsWith("http") },
            durationSec = optLong("duration", -1L),
        )
    }

    private fun JSONArray.toSongs(): List<Song> = (0 until length()).mapNotNull { optJSONObject(it)?.toSong() }

    private fun repeatName(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    private fun repeatMode(name: String) = when (name) {
        "one" -> Player.REPEAT_MODE_ONE
        "all" -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    private companion object {
        const val TAG = "RemoteServer"
        const val MAX_FAILED = 10
        const val LOCK_MS = 60_000L
        const val SEARCH_TIMEOUT_MS = 25_000L
    }
}
