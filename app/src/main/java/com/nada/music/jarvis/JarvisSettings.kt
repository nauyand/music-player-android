package com.nada.music.jarvis

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seberapa mudah Jarvis terpanggil.
 *
 * [boost] menaikkan skor kata "Jarvis" saat dicari, [threshold] adalah batas keyakinan minimal.
 * Makin peka, makin mudah terpanggil, tapi makin sering juga salah dengar.
 */
enum class JarvisSensitivity(val label: String, val boost: Float, val threshold: Float) {
    NORMAL("Normal", boost = 1.0f, threshold = 0.25f),
    HIGH("Peka", boost = 2.0f, threshold = 0.15f),
    // boost 3.0 malah melewatkan sebagian rekaman suara asli, jadi dibatasi 2.5.
    VERY_HIGH("Sangat peka", boost = 2.5f, threshold = 0.10f),
    ;

    /**
     * Kata kunci untuk sherpa-onnx: token BPE, lalu boost (:), threshold (#), dan nama (@).
     * Beberapa pelafalan dipisah "/" dan semuanya dihitung sebagai "Jarvis".
     */
    val keywords: String get() = PRONUNCIATIONS.joinToString("/") { "$it :$boost #$threshold @JARVIS" }

    companion object {
        /**
         * Model wake word dilatih dengan bahasa Inggris, sedangkan lidah Indonesia sering
         * mengucapkan "Jarvis" sebagai "jar-fis", "jar-pis", "jer-vis", atau "ja-vis".
         * Diuji dengan suara sintetis: deteksi naik, kalimat sehari-hari tidak ikut terpanggil.
         *
         * Rekaman suara pemakai asli lewat mikrofon HP terdengar oleh model sebagai "go to fish",
         * "data fish", "dervish": "j" terdengar seperti d/g, "r" yang digetarkan seperti t/d, dan
         * "vis" seperti "fish". Varian di bawah "dengan suara asli" menangkap semua rekaman itu,
         * dan tidak terpanggil oleh kalimat bahasa Indonesia sehari-hari.
         */
        val PRONUNCIATIONS = listOf(
            "▁JA R VI S", // jarvis
            "▁JA R F IS", // jarfis
            "▁JA R P IS", // jarpis
            "▁JA R B IS", // jarbis
            "▁JE R VI S", // jervis
            "▁JA VI S", // ja-vis (r hilang)
            // dengan suara asli
            "▁JA R V ISH",
            "▁JA R F ISH",
            "▁DE R V ISH",
            "▁DA R V ISH",
            "▁DA R F ISH",
            "▁DE R ▁F ISH",
            "▁DA TA ▁F ISH",
            "▁JA TA ▁F ISH",
            "▁GO ▁TO ▁F ISH",
            "▁GO D O ▁F ISH",
        )
    }
}

/** Pengaturan Jarvis yang disimpan di HP. */
object JarvisSettings {

    private const val PREFS = "jarvis"
    private const val KEY_SENSITIVITY = "sensitivity"

    private val _sensitivity = MutableStateFlow(JarvisSensitivity.HIGH)
    val sensitivity: StateFlow<JarvisSensitivity> = _sensitivity.asStateFlow()

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SENSITIVITY, null)
        _sensitivity.value = JarvisSensitivity.entries.firstOrNull { it.name == saved } ?: JarvisSensitivity.HIGH
    }

    fun setSensitivity(context: Context, value: JarvisSensitivity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SENSITIVITY, value.name).apply()
        _sensitivity.value = value
    }
}
