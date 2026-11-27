package com.nada.music.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Suara balasan Jarvis.
 * - Kalau model Kokoro sudah diunduh: suara "bm_lewis" (seperti raw_10.wav), dibuat offline di HP.
 * - Kalau belum: suara bawaan Android (bahasa Inggris) sebagai cadangan.
 */
class JarvisVoice(private val context: Context) {

    private val mutex = Mutex()
    private var kokoro: OfflineTts? = null

    /** Kalimat yang sering dipakai (mis. "Yes, sir?"), disimpan supaya langsung terdengar tanpa jeda. */
    private val cache = HashMap<String, GeneratedAudio>()
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false

    init {
        systemTts = TextToSpeech(context) { status ->
            systemTtsReady = status == TextToSpeech.SUCCESS
            if (systemTtsReady) systemTts?.language = Locale.UK
        }
    }

    /** Mengucapkan [text] dan menunggu sampai selesai. */
    suspend fun speak(text: String) = mutex.withLock {
        val engine = withContext(Dispatchers.Default) { kokoroOrNull() }
        if (engine != null) {
            runCatching { speakWithKokoro(engine, text) }
                .onFailure { Log.w(TAG, "Kokoro gagal, pakai suara Android", it) }
                .onSuccess { return@withLock }
        }
        speakWithSystem(text)
    }

    /**
     * Membuat suara [texts] lebih dulu dan menyimpannya di memori. Kalau model Kokoro belum ada,
     * tidak melakukan apa-apa (suara Android tidak perlu disiapkan).
     */
    suspend fun preload(vararg texts: String) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val engine = kokoroOrNull() ?: return@withContext
            texts.filter { it !in cache }.forEach { text ->
                runCatching { engine.generate(text, sid = KokoroModel.JARVIS_SPEAKER_ID, speed = 1.0f) }
                    .onSuccess { cache[text] = it }
            }
        }
    }

    /** Memuat Kokoro sekali (±1–3 detik) setelah modelnya tersedia. */
    private fun kokoroOrNull(): OfflineTts? {
        kokoro?.let { return it }
        if (!KokoroModel.isReady(context)) return null
        val dir = KokoroModel.dir(context)
        return runCatching {
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(dir, "model.onnx").absolutePath,
                            voices = File(dir, "voices.bin").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                        ),
                        // 1 thread: dengan 2 thread, thread pool onnxruntime pernah crash
                        // ("Scudo: invalid chunk state") di tengah generate() pada HP vivo.
                        numThreads = 1,
                    ),
                ),
            )
        }.onFailure { Log.e(TAG, "Model Kokoro tidak bisa dimuat", it) }
            .getOrNull()
            ?.also { kokoro = it }
    }

    private suspend fun speakWithKokoro(tts: OfflineTts, text: String) = withContext(Dispatchers.Default) {
        val audio = cache[text] ?: tts.generate(text, sid = KokoroModel.JARVIS_SPEAKER_ID, speed = 1.0f)
        if (audio.samples.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(audio.samples.size * 4)
            .build()
        try {
            track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            // Tunggu sampai selesai diputar.
            val durationMs = audio.samples.size * 1000L / audio.sampleRate
            Thread.sleep(durationMs + 150)
        } finally {
            track.stop()
            track.release()
        }
    }

    private suspend fun speakWithSystem(text: String) {
        val tts = systemTts ?: return
        if (!systemTtsReady) return
        suspendCancellableCoroutine { cont ->
            val id = "jarvis-${System.nanoTime()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    /**
     * Melepas model. Ditunggu sampai suara yang sedang dibuat selesai, karena membebaskan model
     * saat generate() masih berjalan di thread lain membuat aplikasi crash (memori native rusak).
     */
    fun release() {
        runBlocking {
            withTimeoutOrNull(RELEASE_WAIT_MS) { mutex.lock() }
        }
        cache.clear()
        kokoro?.free()
        kokoro = null
        systemTts?.shutdown()
        systemTts = null
    }

    private companion object {
        const val TAG = "JarvisVoice"
        const val RELEASE_WAIT_MS = 5_000L
    }
}
