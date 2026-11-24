package com.nada.music.jarvis

/**
 * Logika deteksi per 80 ms, dipisah dari mikrofon supaya kode yang sama bisa diuji di komputer
 * dengan rekaman WAV.
 */
class WakeWordMatcher(embedder: VoiceEmbedder, private val templates: List<List<FloatArray>>) {

    private val stream = embedder.Stream()
    private val recent = ArrayDeque<FloatArray>()
    private val recentEnergy = ArrayDeque<Float>()
    private val maxRecent = (templates.maxOfOrNull { it.size } ?: 0) + 4

    /**
     * Masukkan tepat [VoiceEmbedder.CHUNK] sampel (int16 sebagai float).
     * Mengembalikan jarak terdekat ke contoh suara, atau null kalau belum ada yang bisa dibandingkan.
     */
    fun process(chunk: FloatArray): Float? {
        val embedding = stream.push(chunk) ?: return null
        if (templates.isEmpty()) return null
        recent.addLast(embedding)
        recentEnergy.addLast(VoiceEmbedder.rmsDbfs(chunk))
        while (recent.size > maxRecent) {
            recent.removeFirst()
            recentEnergy.removeFirst()
        }
        return TemplateMatcher.bestDistance(recent.toList(), recentEnergy.toList(), templates)
            .takeIf { it != Float.MAX_VALUE }
    }

    /** Dipanggil setelah "Jarvis" terdeteksi, supaya ucapan yang sama tidak terdeteksi dua kali. */
    fun reset() {
        recent.clear()
        recentEnergy.clear()
    }
}
