package com.nada.music.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinkTest {

    @Test
    fun shortShareLink() {
        assertEquals("FI9C6sYi8cs", VideoLink.parseId("https://youtu.be/FI9C6sYi8cs?si=J3xZ5utwnkk6nL7L"))
    }

    @Test
    fun watchAndMusicLinks() {
        assertEquals("FI9C6sYi8cs", VideoLink.parseId("https://www.youtube.com/watch?v=FI9C6sYi8cs&t=42s"))
        assertEquals("FI9C6sYi8cs", VideoLink.parseId("https://m.youtube.com/watch?feature=share&v=FI9C6sYi8cs"))
        assertEquals("lCIX4hd_geU", VideoLink.parseId("https://music.youtube.com/watch?v=lCIX4hd_geU&list=RDAMVMlCIX4hd_geU"))
        assertEquals("FI9C6sYi8cs", VideoLink.parseId("https://youtube.com/shorts/FI9C6sYi8cs?feature=share"))
        assertEquals("FI9C6sYi8cs", VideoLink.parseId("https://www.youtube.com/live/FI9C6sYi8cs"))
    }

    @Test
    fun linkInsideSharedText() {
        assertEquals(
            "FI9C6sYi8cs",
            VideoLink.parseId("Lagu jawa enak nih https://youtu.be/FI9C6sYi8cs?si=abc dengerin ya"),
        )
    }

    @Test
    fun notAVideo() {
        assertNull(VideoLink.parseId("laut kidul"))
        assertNull(VideoLink.parseId("https://youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))
        assertNull(VideoLink.parseId("https://www.youtube.com/@hasezs"))
        assertNull(VideoLink.parseId("https://example.com/watch?v=FI9C6sYi8cs"))
        assertTrue(VideoLink.isYouTubeLink("https://www.youtube.com/@hasezs"))
        assertFalse(VideoLink.isYouTubeLink("putar lagu youtube"))
    }
}
