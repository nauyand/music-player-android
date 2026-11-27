package com.nada.music.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Teks / link yang dibagikan ke aplikasi dari aplikasi lain (mis. YouTube → Bagikan → Nautify).
 * MainActivity mengisinya, lalu layar Cari membuka dan memutarnya.
 */
object SharedLink {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(text: String) {
        if (text.isNotBlank()) _pending.value = text.trim()
    }

    fun consume() {
        _pending.value = null
    }
}
