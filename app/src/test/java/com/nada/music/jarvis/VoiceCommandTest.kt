package com.nada.music.jarvis

import com.nada.music.jarvis.VoiceCommand.Companion.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun playbackControlsInIndonesianAndEnglish() {
        assertEquals(VoiceCommand.Pause, parse("Stop"))
        assertEquals(VoiceCommand.Pause, parse("berhenti dulu ya"))
        assertEquals(VoiceCommand.Pause, parse("Jarvis, matikan musik"))
        assertEquals(VoiceCommand.Resume, parse("lanjutkan"))
        assertEquals(VoiceCommand.Resume, parse("Play"))
        assertEquals(VoiceCommand.Resume, parse("putar lagu"))
        assertEquals(VoiceCommand.Next, parse("skip"))
        assertEquals(VoiceCommand.Next, parse("lagu berikutnya dong"))
        assertEquals(VoiceCommand.Next, parse("Next song."))
        assertEquals(VoiceCommand.Previous, parse("lagu sebelumnya"))
        assertEquals(VoiceCommand.Previous, parse("previous"))
    }

    @Test
    fun volume() {
        assertEquals(VoiceCommand.VolumeUp, parse("volume naik"))
        assertEquals(VoiceCommand.VolumeUp, parse("keraskan suara"))
        assertEquals(VoiceCommand.VolumeUp, parse("turn it up"))
        assertEquals(VoiceCommand.VolumeDown, parse("kecilkan"))
        assertEquals(VoiceCommand.VolumeDown, parse("volume turun"))
        assertEquals(VoiceCommand.SetVolume(50), parse("volume 50"))
        assertEquals(VoiceCommand.SetVolume(80), parse("volume ke 80 persen"))
        assertEquals(VoiceCommand.SetVolume(100), parse("volume 150"))
    }

    @Test
    fun searchAndPlay() {
        assertEquals(VoiceCommand.PlaySong("tulus hati-hati di jalan"), parse("putar lagu Tulus Hati-Hati di Jalan"))
        assertEquals(VoiceCommand.PlaySong("sial mahalini"), parse("Jarvis putar Sial dari Mahalini"))
        assertEquals(VoiceCommand.PlaySong("bohemian rhapsody"), parse("play Bohemian Rhapsody"))
        assertEquals(VoiceCommand.PlaySong("dj tiktok viral"), parse("tolong mainkan DJ TikTok viral dong"))
        assertEquals(VoiceCommand.PlaySong("iqro raim laode"), parse("cari lagu iqro raim laode"))
    }

    @Test
    fun sleepTurnsJarvisOffButNotTheMusic() {
        assertEquals(VoiceCommand.Sleep, parse("matikan Jarvis"))
        assertEquals(VoiceCommand.Sleep, parse("Jarvis, go to sleep"))
        assertEquals(VoiceCommand.Sleep, parse("tidur"))
        assertEquals(VoiceCommand.Sleep, parse("turn off Jarvis please"))
        // "matikan musik" tetap menjeda lagu, bukan mematikan Jarvis.
        assertEquals(VoiceCommand.Pause, parse("matikan musik"))
        assertEquals(VoiceCommand.Pause, parse("matikan"))
    }

    @Test
    fun unknownSpeech() {
        assertTrue(parse("apa kabar") is VoiceCommand.Unknown)
        assertTrue(parse("") is VoiceCommand.Unknown)
        assertTrue(parse("jarvis") is VoiceCommand.Unknown)
    }
}
