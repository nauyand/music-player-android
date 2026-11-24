package com.nada.music.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Mengubah audio 16 kHz menjadi embedding suara, memakai dua model kecil dari openWakeWord
 * (`assets/oww/melspectrogram.onnx` dan `assets/oww/embedding_model.onnx`, total ±2.4 MB).
 *
 * Alurnya sama persis dengan prototipe yang diuji di komputer:
 * setiap 80 ms (1280 sampel) → spektrogram mel → 8 frame terbaru disimpan →
 * kalau sudah ada 76 frame → 1 embedding (96 angka) untuk ±0.76 detik terakhir.
 */
class VoiceEmbedder(melModel: ByteArray, embeddingModel: ByteArray) {

    /** Memuat model dari `assets/oww/`. */
    constructor(context: Context) : this(
        context.assets.open("oww/melspectrogram.onnx").use { it.readBytes() },
        context.assets.open("oww/embedding_model.onnx").use { it.readBytes() },
    )

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val melSession = env.createSession(melModel, options)
    private val embSession = env.createSession(embeddingModel, options)
    private val melInput = melSession.inputNames.first()
    private val embInput = embSession.inputNames.first()

    /** Satu aliran audio (mis. mikrofon yang sedang didengar, atau satu rekaman contoh). */
    inner class Stream {
        private var raw = FloatArray(0)
        private val mel = ArrayDeque<FloatArray>()

        /**
         * Masukkan tepat [CHUNK] sampel (nilai int16 sebagai float, bukan -1..1).
         * Mengembalikan embedding yang sudah dinormalisasi, atau null kalau belum cukup data.
         */
        fun push(chunk: FloatArray): FloatArray? {
            raw = (raw + chunk).let { if (it.size > CONTEXT) it.copyOfRange(it.size - CONTEXT, it.size) else it }
            val frames = melFrames(raw)
            for (f in frames.takeLast(8)) {
                mel.addLast(f)
                if (mel.size > MAX_MEL_FRAMES) mel.removeFirst()
            }
            if (mel.size < EMBEDDING_FRAMES) return null
            return TemplateMatcher.normalize(embed(mel.toList().takeLast(EMBEDDING_FRAMES)))
        }
    }

    private fun melFrames(samples: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            melSession.run(mapOf(melInput to input)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape // [1, 1, frames, 32]
                val frames = shape[shape.size - 2].toInt()
                val buf = out.floatBuffer
                return List(frames) { f -> FloatArray(MEL_BINS) { b -> buf.get(f * MEL_BINS + b) / 10f + 2f } }
            }
        }
    }

    private fun embed(frames: List<FloatArray>): FloatArray {
        val data = FloatArray(EMBEDDING_FRAMES * MEL_BINS)
        frames.forEachIndexed { i, f -> f.copyInto(data, i * MEL_BINS) }
        val shape = longArrayOf(1, EMBEDDING_FRAMES.toLong(), MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            embSession.run(mapOf(embInput to input)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(out.remaining()) { out.get(it) }
            }
        }
    }

    /**
     * Membuat template dari satu rekaman contoh (16 kHz, int16).
     * Diberi 1 detik hening di depan dan 0.5 detik di belakang, sama seperti saat diuji.
     * @return null kalau rekamannya tidak berisi suara yang jelas.
     */
    fun templateFrom(samples: ShortArray): List<FloatArray>? {
        val padded = FloatArray(SAMPLE_RATE + samples.size + SAMPLE_RATE / 2)
        for (i in samples.indices) padded[SAMPLE_RATE + i] = samples[i].toFloat()
        val stream = Stream()
        val embeddings = mutableListOf<FloatArray>()
        val energy = mutableListOf<Float>()
        var i = 0
        while (i + CHUNK <= padded.size) {
            val chunk = padded.copyOfRange(i, i + CHUNK)
            stream.push(chunk)?.let {
                embeddings += it
                energy += rmsDbfs(chunk)
            }
            i += CHUNK
        }
        return TemplateMatcher.trimTemplate(embeddings, energy)
    }

    fun close() {
        melSession.close()
        embSession.close()
        options.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1280 // 80 ms
        private const val CONTEXT = CHUNK + 480
        private const val MEL_BINS = 32
        private const val EMBEDDING_FRAMES = 76
        private const val MAX_MEL_FRAMES = 120

        /** Kekerasan suara (dBFS) dari sampel int16 yang disimpan sebagai float. */
        fun rmsDbfs(chunk: FloatArray): Float {
            var sum = 0.0
            for (x in chunk) sum += (x * x).toDouble()
            val rms = sqrt(sum / chunk.size) + 1e-9
            return (20 * log10(rms / 32768.0)).toFloat()
        }
    }
}
