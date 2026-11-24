package com.nada.music.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mendengarkan kata "Jarvis" terus-menerus, sepenuhnya di HP (offline), dengan sherpa-onnx.
 *
 * Model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M (int8, ±5 MB) di `assets/kws/`.
 * Suara tidak pernah dikirim ke internet di tahap ini.
 *
 * Diagnostik:
 * - Setiap 3 detik level suara mikrofon ditulis ke log (tag "WakeWord").
 * - Kalau file `jarvis-debug` ada di folder `Android/data/com.nada.music/files/`, 10 detik suara
 *   terakhir disimpan sebagai `jarvis-mic.wav` di folder yang sama, supaya bisa diperiksa di komputer.
 */
class WakeWordDetector(private val context: Context) {

    private val spotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.int8.onnx",
                    decoder = "kws/decoder.int8.onnx",
                    joiner = "kws/joiner.int8.onnx",
                ),
                tokens = "kws/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
            ),
            keywordsFile = "kws/keywords.txt",
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        ),
    )

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Mulai mendengarkan. [onWake] dipanggil dari thread latar setiap kali "Jarvis" terdengar. */
    @SuppressLint("MissingPermission") // izin RECORD_AUDIO dicek sebelum service dijalankan
    fun start(sensitivity: JarvisSensitivity, onWake: () -> Unit) {
        if (running) return
        running = true
        thread = Thread({ listen(sensitivity, onWake) }, "jarvis-wakeword").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun listen(sensitivity: JarvisSensitivity, onWake: () -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Sumber MIC sama dengan contoh resmi sherpa-onnx; VOICE_RECOGNITION di sebagian HP
        // memberi suara yang sangat pelan / sudah diproses.
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, SAMPLE_RATE / 5 * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mikrofon tidak bisa dibuka")
            record.release()
            running = false
            return
        }
        Log.i(TAG, "Mulai mendengarkan, kepekaan=${sensitivity.name} (${sensitivity.keywords})")
        val stream = spotter.createStream(sensitivity.keywords)
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms
        val ring = ShortArray(SAMPLE_RATE * DEBUG_SECONDS)
        var ringPos = 0
        var ringFull = false
        var statSumSquares = 0.0
        var statPeak = 0
        var statSamples = 0
        var lastStatAt = System.currentTimeMillis()
        val debugDir = context.getExternalFilesDir(null)

        record.startRecording()
        try {
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue

                for (i in 0 until n) {
                    val s = buffer[i].toInt()
                    statSumSquares += (s * s).toDouble()
                    if (kotlin.math.abs(s) > statPeak) statPeak = kotlin.math.abs(s)
                    ring[ringPos] = buffer[i]
                    ringPos = (ringPos + 1) % ring.size
                    if (ringPos == 0) ringFull = true
                }
                statSamples += n

                val samples = FloatArray(n) { buffer[it] / 32768f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (spotter.isReady(stream)) spotter.decode(stream)
                val result = spotter.getResult(stream)
                if (result.keyword.isNotBlank()) {
                    Log.i(TAG, "Terdeteksi: ${result.keyword} token=${result.tokens.joinToString(" ")}")
                    spotter.reset(stream)
                    onWake()
                }

                val now = System.currentTimeMillis()
                if (now - lastStatAt >= STAT_INTERVAL_MS) {
                    val rms = sqrt(statSumSquares / statSamples.coerceAtLeast(1))
                    Log.i(TAG, "Level mikrofon: rms=${dbfs(rms)} dBFS, puncak=${dbfs(statPeak.toDouble())} dBFS")
                    statSumSquares = 0.0
                    statPeak = 0
                    statSamples = 0
                    lastStatAt = now
                    if (debugDir != null && File(debugDir, DEBUG_FLAG).exists()) {
                        writeWav(File(debugDir, DEBUG_WAV), ring, ringPos, ringFull)
                    }
                }
            }
        } finally {
            record.stop()
            record.release()
            stream.release()
            Log.i(TAG, "Berhenti mendengarkan")
        }
    }

    private fun dbfs(value: Double): String =
        if (value <= 0.0) "-inf" else "%.1f".format(20 * log10(value / 32768.0))

    /** Menyimpan isi ring buffer (urut dari yang paling lama) sebagai WAV 16 kHz mono. */
    private fun writeWav(file: File, ring: ShortArray, pos: Int, full: Boolean) {
        val count = if (full) ring.size else pos
        val start = if (full) pos else 0
        val data = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = ring[(start + i) % ring.size].toInt()
            data[i * 2] = (s and 0xff).toByte()
            data[i * 2 + 1] = (s shr 8 and 0xff).toByte()
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            f.write("RIFF".toByteArray()); f.writeIntLE(36 + data.size); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.writeIntLE(16); f.writeShortLE(1); f.writeShortLE(1)
            f.writeIntLE(SAMPLE_RATE); f.writeIntLE(SAMPLE_RATE * 2); f.writeShortLE(2); f.writeShortLE(16)
            f.write("data".toByteArray()); f.writeIntLE(data.size); f.write(data)
        }
        tmp.renameTo(file)
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff); write(v shr 16 and 0xff); write(v shr 24 and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff)
    }

    /** Berhenti mendengarkan dan melepas mikrofon (misalnya saat perintah sedang direkam). */
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun release() {
        stop()
        spotter.release()
    }

    private companion object {
        const val TAG = "WakeWord"
        const val SAMPLE_RATE = 16000
        const val STAT_INTERVAL_MS = 3000L
        const val DEBUG_SECONDS = 10
        const val DEBUG_FLAG = "jarvis-debug"
        const val DEBUG_WAV = "jarvis-mic.wav"
    }
}
