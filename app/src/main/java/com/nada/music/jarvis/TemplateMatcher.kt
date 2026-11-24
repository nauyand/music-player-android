package com.nada.music.jarvis

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bagian matematika dari wake word berbasis contoh suara (tanpa Android / ONNX, jadi bisa diuji di JVM).
 *
 * Setiap 80 ms audio diubah menjadi satu "embedding" (vektor 96 angka) yang menggambarkan bunyi
 * ±0.76 detik terakhir. Ucapan "Jarvis" menjadi deretan embedding. Deretan terbaru dibandingkan
 * dengan deretan dari contoh suara pengguna memakai DTW (dynamic time warping), supaya ucapan yang
 * sedikit lebih cepat / lambat tetap cocok.
 */
object TemplateMatcher {

    /** Jendela yang dibandingkan boleh 2 embedding (±160 ms) lebih pendek / panjang dari contoh. */
    private val LENGTH_OFFSETS = intArrayOf(-2, 0, 2)

    /** Jendela yang paling keras di bawah batas ini dianggap hening dan dilewati. */
    const val MIN_SPEECH_DBFS = -45f

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val n = sqrt(sum).toFloat() + 1e-9f
        return FloatArray(v.size) { v[it] / n }
    }

    /**
     * Jarak DTW rata-rata antara dua deretan embedding yang sudah dinormalisasi.
     * Biaya per pasangan = 1 - kemiripan kosinus. 0 = identik.
     */
    fun dtw(a: List<FloatArray>, b: List<FloatArray>): Float {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Float.MAX_VALUE
        var prev = FloatArray(m + 1) { Float.POSITIVE_INFINITY }
        var cur = FloatArray(m + 1)
        prev[0] = 0f
        for (i in 1..n) {
            cur[0] = Float.POSITIVE_INFINITY
            for (j in 1..m) {
                val cost = 1f - dot(a[i - 1], b[j - 1])
                cur[j] = cost + min(prev[j], min(cur[j - 1], prev[j - 1]))
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m] / (n + m)
    }

    /**
     * Jarak terkecil antara deretan embedding terbaru ([recent], paling baru di akhir) dan semua [templates].
     * [recentEnergyDbfs] berisi kekerasan suara tiap embedding; jendela yang hening dilewati.
     * Mengembalikan [Float.MAX_VALUE] kalau belum ada yang bisa dibandingkan.
     */
    fun bestDistance(
        recent: List<FloatArray>,
        recentEnergyDbfs: List<Float>,
        templates: List<List<FloatArray>>,
    ): Float {
        var best = Float.MAX_VALUE
        val n = recent.size
        for (t in templates) {
            val len = t.size
            if (n < len + 2) continue
            if (recentEnergyDbfs.subList(n - len, n).max() < MIN_SPEECH_DBFS) continue
            for (d in LENGTH_OFFSETS) {
                val w = len + d
                if (w < 1 || w > n) continue
                best = min(best, dtw(t, recent.subList(n - w, n)))
            }
        }
        return best
    }

    /**
     * Memotong embedding sebuah rekaman contoh menjadi template: mulai sedikit setelah suara terdengar
     * sampai ±0.5 detik setelah suara berhenti (embedding "melihat ke belakang" ±0.76 detik, jadi kata
     * paling lengkap ada di embedding setelah suara berakhir).
     *
     * @return null kalau rekaman tidak berisi suara yang jelas.
     */
    fun trimTemplate(embeddings: List<FloatArray>, energyDbfs: List<Float>): List<FloatArray>? {
        if (embeddings.isEmpty()) return null
        val peak = energyDbfs.max()
        if (peak < MIN_SPEECH_DBFS) return null
        val gate = maxOf(peak - 25f, -50f)
        val voiced = energyDbfs.indices.filter { energyDbfs[it] > gate }
        val start = voiced.first() + 3
        val end = min(embeddings.size - 1, voiced.last() + 6)
        if (end - start + 1 < MIN_TEMPLATE_LENGTH) return null
        return embeddings.subList(start, end + 1).toList()
    }

    /** Template lebih pendek dari ini (±400 ms) terlalu sedikit isinya untuk dicocokkan. */
    const val MIN_TEMPLATE_LENGTH = 5

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
