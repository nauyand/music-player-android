package com.nada.music.data.importer

/** Link satu video YouTube / YouTube Music yang ditempel di kolom cari atau dibagikan ke aplikasi. */
object VideoLink {

    private val YOUTUBE_URL = Regex(
        "(?:https?://)?(?:[a-z0-9-]+\\.)?(?:youtube\\.com|youtube-nocookie\\.com|youtu\\.be)/\\S*",
        RegexOption.IGNORE_CASE,
    )
    private val VIDEO_PARAM = Regex("[?&]v=([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])")
    private val PATH_VIDEO = Regex(
        "(?:youtu\\.be/|/shorts/|/live/|/embed/|/v/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Mengambil id video (11 karakter) dari teks yang berisi link YouTube, misalnya
     * `https://youtu.be/FI9C6sYi8cs?si=...` atau `https://music.youtube.com/watch?v=...&list=...`.
     * Teks boleh berisi kata lain di sekitar link. Null kalau tidak ada link video.
     */
    fun parseId(text: String): String? {
        val url = YOUTUBE_URL.find(text)?.value ?: return null
        return VIDEO_PARAM.find(url)?.groupValues?.get(1)
            ?: PATH_VIDEO.find(url)?.groupValues?.get(1)
    }

    /** true kalau teks berisi link YouTube apa pun (video, playlist, channel, ...). */
    fun isYouTubeLink(text: String): Boolean = YOUTUBE_URL.containsMatchIn(text)
}
