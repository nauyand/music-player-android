package com.nada.music.jarvis

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Model suara Kokoro (bahasa Inggris, ±300 MB) untuk suara Jarvis.
 * Terlalu besar untuk dimasukkan ke APK, jadi diunduh sekali dari rilis resmi sherpa-onnx
 * lalu disimpan di penyimpanan internal aplikasi.
 */
object KokoroModel {

    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"

    /** Perkiraan ukuran unduhan untuk ditampilkan sebelum mulai. */
    const val DOWNLOAD_SIZE_MB = 304

    /**
     * Suara "bm_lewis" (pria, aksen Inggris) — sama dengan contoh `raw_10.wav`.
     * Urutan speaker kokoro-en-v0_19: 0 af, 1 af_bella, 2 af_nicole, 3 af_sarah, 4 af_sky,
     * 5 am_adam, 6 am_michael, 7 bf_emma, 8 bf_isabella, 9 bm_george, 10 bm_lewis.
     */
    const val JARVIS_SPEAKER_ID = 10

    private const val DIR_NAME = "kokoro-en-v0_19"

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun isReady(context: Context): Boolean {
        val d = dir(context)
        return File(d, "model.onnx").isFile && File(d, "voices.bin").isFile &&
            File(d, "tokens.txt").isFile && File(d, "espeak-ng-data").isDirectory
    }

    fun refresh(context: Context) {
        if (_state.value !is State.Downloading) {
            _state.value = if (isReady(context)) State.Ready else State.NotDownloaded
        }
    }

    /** Mulai mengunduh di latar. Aman dipanggil berkali-kali. */
    fun download(context: Context, client: OkHttpClient) {
        if (isReady(context)) {
            _state.value = State.Ready
            return
        }
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            _state.value = State.Downloading(0f)
            val target = dir(appContext)
            val temp = File(appContext.filesDir, "$DIR_NAME.part")
            try {
                temp.deleteRecursively()
                temp.mkdirs()
                val request = Request.Builder().url(URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Respons kosong")
                    val total = body.contentLength().takeIf { it > 0 } ?: (DOWNLOAD_SIZE_MB * 1024L * 1024L)
                    val counting = CountingStream(body.byteStream()) { read ->
                        _state.value = State.Downloading((read.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                    // Diekstrak langsung sambil mengunduh, tanpa menyimpan file .tar.bz2 sementara.
                    TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered())).use { tar ->
                        // Isi arsip ada di dalam folder "kokoro-en-v0_19/".
                        var entry = tar.nextEntry
                        while (entry != null) {
                            coroutineContext.ensureActive()
                            val relative = entry.name.substringAfter('/', "")
                            if (relative.isNotEmpty()) {
                                val out = File(temp, relative)
                                // Lindungi dari path "../" di dalam arsip.
                                if (!out.canonicalPath.startsWith(temp.canonicalPath + File.separator)) {
                                    throw IllegalStateException("Isi arsip tidak valid: ${entry.name}")
                                }
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { tar.copyTo(it) }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
                target.deleteRecursively()
                if (!temp.renameTo(target)) throw IllegalStateException("Gagal menyimpan model")
                if (!isReady(appContext)) throw IllegalStateException("File model tidak lengkap")
                _state.value = State.Ready
            } catch (e: CancellationException) {
                temp.deleteRecursively()
                _state.value = State.NotDownloaded
                throw e
            } catch (e: Exception) {
                temp.deleteRecursively()
                _state.value = State.Failed(e.message ?: "Unduhan gagal")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.NotDownloaded
    }

    private class CountingStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            // Laporkan setiap ±1 MB supaya UI tidak terlalu sering diperbarui.
            if (count - lastReported >= 1_000_000) {
                lastReported = count
                onRead(count)
            }
        }
    }
}
