package com.nada.music.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatcherTest {

    private fun vec(vararg x: Float) = TemplateMatcher.normalize(x)

    @Test
    fun identicalSequencesHaveZeroDistance() {
        val a = listOf(vec(1f, 0f), vec(0.7f, 0.7f), vec(0f, 1f))
        assertEquals(0f, TemplateMatcher.dtw(a, a), 1e-6f)
    }

    @Test
    fun slowerVersionOfSameSoundIsStillClose() {
        val word = listOf(vec(1f, 0f), vec(0.7f, 0.7f), vec(0f, 1f))
        val slow = listOf(vec(1f, 0f), vec(1f, 0f), vec(0.7f, 0.7f), vec(0.7f, 0.7f), vec(0f, 1f))
        val other = listOf(vec(-1f, 0f), vec(-1f, 0.1f), vec(-0.9f, -0.2f))
        assertTrue(TemplateMatcher.dtw(word, slow) < 0.01f)
        assertTrue(TemplateMatcher.dtw(word, other) > 0.5f)
    }

    @Test
    fun silentWindowsAreSkipped() {
        val t = listOf(vec(1f, 0f), vec(0f, 1f))
        val recent = List(6) { vec(1f, 0f) }
        val quiet = List(6) { -60f }
        assertEquals(Float.MAX_VALUE, TemplateMatcher.bestDistance(recent, quiet, listOf(t)))
        val loud = List(6) { -20f }
        assertTrue(TemplateMatcher.bestDistance(recent, loud, listOf(t)) < Float.MAX_VALUE)
    }

    @Test
    fun trimTemplateRejectsSilenceAndKeepsSpeech() {
        val emb = List(30) { vec(1f, it.toFloat()) }
        assertNull(TemplateMatcher.trimTemplate(emb, List(30) { -70f }))
        val energy = List(30) { if (it in 10..15) -20f else -60f }
        val t = TemplateMatcher.trimTemplate(emb, energy)
        assertNotNull(t)
        // dari 3 embedding setelah suara mulai sampai 6 setelah suara berhenti
        assertEquals(21 - 13 + 1, t!!.size)
    }
}
