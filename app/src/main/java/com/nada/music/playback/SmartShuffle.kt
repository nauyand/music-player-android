package com.nada.music.playback

import android.content.Context
import com.nada.music.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * "Acak pintar": saat antrean hampir habis, lagu berikutnya tidak hanya lagu terkait dari lagu
 * yang sedang diputar (yang sering isinya versi lain dari lagu yang sama), tapi campuran dari
 * riwayat dengar, lagu yang sering diputar, lagu favorit, dan lagu terkait dari lagu-lagu itu.
 */
object SmartShuffle {

    private const val PREFS = "radio"
    private const val KEY_SMART = "smart_shuffle"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun load(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SMART, false)
    }

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SMART, value).apply()
        _enabled.value = value
    }

    /** Kata yang tidak membedakan lagu: "Laut Kidul (Official Video)" = "Laut Kidul Lirik". */
    private val NOISE_WORDS = setOf(
        "official", "video", "music", "musik", "audio", "lirik", "lyric", "lyrics", "cover", "remix", "live",
        "version", "versi", "ft", "feat", "featuring", "mv", "hd", "full", "album", "akustik", "acoustic", "slowed",
        "reverb", "sped", "up", "dj", "koplo", "karaoke", "instrumental", "the", "a",
    )

    /**
     * Kunci judul untuk mengenali "lagu yang sama / mirip": dua kata pertama yang berarti,
     * tanpa isi kurung dan kata seperti "official", "lirik", "cover".
     */
    fun titleKey(title: String): String {
        val words = title.lowercase()
            .replace(Regex("[(\\[{【].*?[)\\]}】]"), " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in NOISE_WORDS }
        return if (words.isEmpty()) title.lowercase().trim() else words.take(2).joinToString(" ")
    }

    /**
     * Menyusun [count] lagu berikutnya.
     *
     * @param current lagu yang sedang diputar.
     * @param relatedNow lagu terkait dari [current] (dipakai sedikit saja).
     * @param relatedSeeds lagu terkait dari lagu lain yang pernah didengar.
     * @param personal riwayat + sering diputar + favorit.
     * @param queue lagu yang sudah ada di antrean (tidak diulang).
     */
    fun mix(
        current: Song,
        relatedNow: List<Song>,
        relatedSeeds: List<Song>,
        personal: List<Song>,
        queue: List<Song>,
        count: Int = 10,
        random: Random = Random.Default,
    ): List<Song> {
        val usedIds = HashSet<String>().apply {
            add(current.id)
            queue.forEach { add(it.id) }
        }
        // Lagu yang mirip dengan lagu sekarang atau beberapa lagu terakhir tidak dipakai.
        val usedKeys = HashSet<String>().apply {
            add(titleKey(current.title))
            queue.takeLast(RECENT_KEYS).forEach { add(titleKey(it.title)) }
        }

        val pools = listOf(
            personal.shuffled(random).toMutableList(),
            relatedSeeds.shuffled(random).toMutableList(),
            relatedNow.shuffled(random).take(MAX_FROM_CURRENT * 3).toMutableList(),
        )
        val fromCurrentLimit = MAX_FROM_CURRENT
        var fromCurrent = 0
        // Urutan bergiliran: riwayat, terkait-lain, terkait-sekarang, riwayat, terkait-lain, ...
        val pattern = intArrayOf(0, 1, 2, 0, 1)
        val result = ArrayList<Song>(count)
        var lastArtist = current.artist.lowercase()
        var step = 0
        var misses = 0

        while (result.size < count && misses < pattern.size * 2) {
            val poolIndex = pattern[step++ % pattern.size]
            if (poolIndex == 2 && fromCurrent >= fromCurrentLimit) {
                misses++
                continue
            }
            val pool = pools[poolIndex]
            fun usable(s: Song) = s.id !in usedIds && titleKey(s.title) !in usedKeys
            // Usahakan penyanyinya beda dengan lagu sebelumnya.
            val pick = pool.firstOrNull { usable(it) && it.artist.lowercase() != lastArtist }
                ?: pool.firstOrNull { usable(it) }
            if (pick == null) {
                misses++
                continue
            }
            misses = 0
            pool.remove(pick)
            result += pick
            usedIds += pick.id
            usedKeys += titleKey(pick.title)
            lastArtist = pick.artist.lowercase()
            if (poolIndex == 2) fromCurrent++
        }
        // Pengguna baru (belum ada riwayat): isi sisanya dengan lagu terkait, tetap tanpa yang mirip.
        if (result.size < count) {
            for (s in relatedNow.shuffled(random)) {
                if (result.size >= count) break
                val key = titleKey(s.title)
                if (s.id in usedIds || key in usedKeys) continue
                result += s
                usedIds += s.id
                usedKeys += key
            }
        }
        return result
    }

    private const val RECENT_KEYS = 6

    /** Paling banyak lagu terkait langsung dari lagu sekarang per tambahan antrean. */
    private const val MAX_FROM_CURRENT = 2
}
