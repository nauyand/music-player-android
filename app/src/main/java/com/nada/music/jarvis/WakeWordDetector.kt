package com.nada.music.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mendengarkan kata "Jarvis" terus-menerus, sepenuhnya di HP (offline).
 *
 * Bukan model bahasa Inggris "standar", melainkan dicocokkan dengan **contoh suara pengguna sendiri**
 * (lihat [VoiceSamples] dan [TemplateMatcher]). Model bahasa Inggris terbukti tidak mengenali
 * pelafalan pengguna, sedangkan contoh suara pengguna cocok satu sama lain dengan jelas.
 *
 * Diagnostik:
 * - Setiap 3 detik level mikrofon dan jarak terdekat ke contoh suara ditulis ke log (tag "WakeWord").
 * - Kalau file `jarvis-debug` ada di `Android/data/com.nada.music/files/`, 10 detik suara terakhir
 *   disimpan sebagai `jarvis-mic.wav` di folder yang sama.
 */
class WakeWordDetector(private val context: Context) {

    private val embedder = VoiceEmbedder(context)
    @Volatile
    private var templates: List<List<FloatArray>> = emptyList()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Jumlah contoh suara yang berhasil dimuat. 0 berarti Jarvis belum dilatih. */
    val templateCount: Int get() = templates.size

    /** Memuat (ulang) contoh suara pengguna menjadi template. */
    fun reloadTemplates() {
        templates = VoiceSamples.files(context).mapNotNull { file ->
            VoiceSamples.read(file)?.let { embedder.templateFrom(it) }
                .also { if (it == null) Log.w(TAG, "Contoh suara ${file.name} tidak berisi suara yang jelas") }
        }
        Log.i(TAG, "Contoh suara dimuat: ${templates.size} (panjang ${templates.map { it.size }})")
    }

    /** Mulai mendengarkan. [onWake] dipanggil dari thread latar setiap kali "Jarvis" terdengar. */
    fun start(sensitivity: JarvisSensitivity, onWake: () -> Unit) {
        if (running) return
        running = true
        thread = Thread({ listen(sensitivity, onWake) }, "jarvis-wakeword").also { it.start() }
    }

    @SuppressLint("MissingPermission") // izin RECORD_AUDIO dicek sebelum service dijalankan
    private fun listen(sensitivity: JarvisSensitivity, onWake: () -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, VoiceEmbedder.CHUNK * 4 * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mikrofon tidak bisa dibuka")
            record.release()
            running = false
            return
        }
        Log.i(TAG, "Mulai mendengarkan, kepekaan=${sensitivity.name} (ambang ${sensitivity.threshold}), contoh=${templates.size}")

        val matcher = WakeWordMatcher(embedder, templates)
        val buffer = ShortArray(VoiceEmbedder.CHUNK)
        var lastWakeAt = 0L

        val ring = ShortArray(SAMPLE_RATE * DEBUG_SECONDS)
        var ringPos = 0
        var ringFull = false
        var statSumSquares = 0.0
        var statPeak = 0
        var statSamples = 0
        var statBest = Float.MAX_VALUE
        var lastStatAt = System.currentTimeMillis()
        val debugDir = context.getExternalFilesDir(null)

        record.startRecording()
        try {
            while (running) {
                // Baca tepat 1280 sampel (80 ms) supaya alurnya sama dengan saat diuji.
                var filled = 0
                while (filled < buffer.size && running) {
                    val n = record.read(buffer, filled, buffer.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < buffer.size) continue

                for (s in buffer) {
                    val v = s.toInt()
                    statSumSquares += (v * v).toDouble()
                    if (abs(v) > statPeak) statPeak = abs(v)
                    ring[ringPos] = s
                    ringPos = (ringPos + 1) % ring.size
                    if (ringPos == 0) ringFull = true
                }
                statSamples += buffer.size

                val chunk = FloatArray(buffer.size) { buffer[it].toFloat() }
                val distance = matcher.process(chunk)
                if (distance != null) {
                    if (distance < statBest) statBest = distance
                    val now = System.currentTimeMillis()
                    if (distance < sensitivity.threshold && now - lastWakeAt > COOLDOWN_MS) {
                        lastWakeAt = now
                        Log.i(TAG, "Terdeteksi: jarak=${"%.3f".format(distance)} (ambang ${sensitivity.threshold})")
                        matcher.reset()
                        onWake()
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastStatAt >= STAT_INTERVAL_MS) {
                    val rms = sqrt(statSumSquares / statSamples.coerceAtLeast(1))
                    val best = if (statBest == Float.MAX_VALUE) "-" else "%.3f".format(statBest)
                    Log.i(TAG, "Level mikrofon: rms=${dbfs(rms)} dBFS, puncak=${dbfs(statPeak.toDouble())} dBFS, jarak terdekat=$best")
                    statSumSquares = 0.0
                    statPeak = 0
                    statSamples = 0
                    statBest = Float.MAX_VALUE
                    lastStatAt = now
                    if (debugDir != null && File(debugDir, DEBUG_FLAG).exists()) {
                        val count = if (ringFull) ring.size else ringPos
                        val start = if (ringFull) ringPos else 0
                        VoiceSamples.writeWav(File(debugDir, DEBUG_WAV), ShortArray(count) { ring[(start + it) % ring.size] })
                    }
                }
            }
        } finally {
            record.stop()
            record.release()
            Log.i(TAG, "Berhenti mendengarkan")
        }
    }

    private fun dbfs(value: Double): String =
        if (value <= 0.0) "-inf" else "%.1f".format(20 * log10(value / 32768.0))

    /** Berhenti mendengarkan dan melepas mikrofon (misalnya saat perintah sedang direkam). */
    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    fun release() {
        stop()
        embedder.close()
    }

    private companion object {
        const val TAG = "WakeWord"
        const val SAMPLE_RATE = VoiceEmbedder.SAMPLE_RATE
        const val STAT_INTERVAL_MS = 3000L
        const val COOLDOWN_MS = 2000L
        const val DEBUG_SECONDS = 10
        const val DEBUG_FLAG = "jarvis-debug"
        const val DEBUG_WAV = "jarvis-mic.wav"
    }
}
