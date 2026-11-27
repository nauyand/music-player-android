package com.nada.music.playback

import com.nada.music.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartShuffleTest {

    private fun song(id: String, title: String, artist: String = "Artis $id") = Song(id, title, artist, null, 200)

    @Test
    fun titleKeyIgnoresVersionWords() {
        val key = SmartShuffle.titleKey("Laut Kidul")
        assertEquals(key, SmartShuffle.titleKey("LAUT KIDUL (Official Music Video)"))
        assertEquals(key, SmartShuffle.titleKey("Laut Kidul - Lirik"))
        assertEquals(key, SmartShuffle.titleKey("DJ Laut Kidul Remix Slowed"))
        assertNotEquals(key, SmartShuffle.titleKey("Hati-Hati di Jalan"))
    }

    @Test
    fun mixAvoidsVersionsOfTheSameSong() {
        val current = song("lk", "Laut Kidul", "Kidul Band")
        val relatedNow = listOf(
            song("lk2", "Laut Kidul (Cover)"),
            song("lk3", "LAUT KIDUL - Lirik"),
            song("lk4", "DJ Laut Kidul Remix"),
            song("x1", "Sial", "Mahalini"),
        )
        val personal = listOf(
            song("p1", "Hati-Hati di Jalan", "Tulus"),
            song("p2", "Bohemian Rhapsody", "Queen"),
            song("p3", "Laut Kidul Official", "Kidul Band"),
        )
        val seeds = listOf(song("s1", "Monokrom", "Tulus"), song("s2", "Mardua Holong", "Arvindo"))

        val result = SmartShuffle.mix(current, relatedNow, seeds, personal, queue = listOf(current), random = Random(1))

        assertTrue(result.none { SmartShuffle.titleKey(it.title) == "laut kidul" })
        assertEquals(result.size, result.map { it.id }.toSet().size)
        assertTrue(result.any { it.id.startsWith("p") })
        assertTrue(result.any { it.id.startsWith("s") })
    }

    @Test
    fun mixWorksWithoutHistory() {
        val current = song("a", "Lagu Satu")
        val related = (1..15).map { song("r$it", "Judul$it Berbeda") }
        val result = SmartShuffle.mix(current, related, emptyList(), emptyList(), queue = listOf(current), random = Random(2))
        assertEquals(10, result.size)
        assertFalse(result.any { it.id == "a" })
    }
}
