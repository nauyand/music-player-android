package com.nada.music.jarvis

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seberapa mudah Jarvis terpanggil.
 *
 * [threshold] adalah jarak maksimal antara suara yang terdengar dan contoh suara pengguna
 * (0 = identik). Diuji pada rekaman pengguna: ucapan "Jarvis" berjarak 0,04–0,07, kalimat lain ≥ 0,11.
 * Makin peka, makin mudah terpanggil, tapi makin sering juga salah dengar.
 */
enum class JarvisSensitivity(val label: String, val threshold: Float) {
    NORMAL("Normal", threshold = 0.060f),
    HIGH("Peka", threshold = 0.072f),
    VERY_HIGH("Sangat peka", threshold = 0.085f),
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
