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

/**
 * Mendengarkan kata "Jarvis" terus-menerus, sepenuhnya di HP (offline), dengan sherpa-onnx.
 *
 * Model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M (int8, ±5 MB) di `assets/kws/`.
 * Suara tidak pernah dikirim ke internet di tahap ini.
 */
class WakeWordDetector(context: Context) {

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
            // Nilai ini yang lolos uji: "Jarvis" terdeteksi, kalimat lain (mis. "Paris") tidak.
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
    fun start(onWake: () -> Unit) {
        if (running) return
        running = true
        thread = Thread({
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_RATE / 5 * 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Mikrofon tidak bisa dibuka")
                record.release()
                running = false
                return@Thread
            }
            val stream = spotter.createStream()
            val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms
            record.startRecording()
            try {
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (spotter.isReady(stream)) spotter.decode(stream)
                    if (spotter.getResult(stream).keyword.isNotBlank()) {
                        spotter.reset(stream)
                        onWake()
                    }
                }
            } finally {
                record.stop()
                record.release()
                stream.release()
            }
        }, "jarvis-wakeword").also { it.start() }
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
    }
}
