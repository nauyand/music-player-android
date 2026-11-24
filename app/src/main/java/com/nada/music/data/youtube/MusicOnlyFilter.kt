package com.nada.music.data.youtube

/**
 * Menyaring "video terkait" dari YouTube supaya radio otomatis dan rekomendasi hanya berisi musik.
 *
 * Daftar terkait diambil dari halaman tonton YouTube biasa, jadi isinya bercampur dengan podcast,
 * wawancara, siaran langsung, Shorts, dan video panjang lain. Penyaring ini membuang yang jelas
 * bukan lagu, berdasarkan jenis stream, durasi, dan kata kunci di judul atau nama channel.
 */
object MusicOnlyFilter {

    /** Lagu lebih pendek dari ini kemungkinan besar Shorts, intro, atau potongan klip. */
    const val MIN_DURATION_SEC = 60L

    /** Lagu biasa lebih panjang dari ini kemungkinan besar podcast, wawancara, atau siaran ulang. */
    const val MAX_DURATION_SEC = 12 * 60L

    /**
     * Batas untuk DJ remix / mix / nonstop. Video seperti ini memang panjang (sering 30–60 menit),
     * jadi boleh masuk sampai 90 menit asal judulnya jelas menyebut remix atau mix.
     */
    const val MAX_MIX_DURATION_SEC = 90 * 60L

    /** Kata kunci di judul yang menandakan DJ remix atau kumpulan lagu tanpa jeda. */
    private val MIX_TITLE = Regex(
        listOf(
            "dj", "remix(es)?", "mix", "megamix", "mixtape", "nonstop", "non\\s*-?\\s*stop",
            "full\\s*bass", "breakbeat", "funkot", "jungle\\s*dutch", "medley", "full album",
        ).joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(", postfix = ")(?![\\p{L}\\p{N}])"),
        RegexOption.IGNORE_CASE,
    )

    /** Kata kunci di judul yang menandakan bukan lagu. Dicocokkan per kata, tidak peka huruf besar. */
    private val NON_MUSIC_TITLE = Regex(
        listOf(
            "podcasts?", "episodes?", "eps?\\.?\\s*\\d+",
            "interviews?", "wawancara", "talk\\s*show", "talkshow", "ngobrol\\w*", "obrolan", "bincang\\w*",
            "q\\s*&\\s*a", "reaction", "react(s|ing)?", "review", "tutorial", "vlog", "trailer",
            "full movie", "berita", "ceramah", "khotbah", "kajian", "audiobook",
            "asmr", "prank", "unboxing", "gameplay", "live\\s*stream(ing)?", "siaran langsung",
            "#shorts?",
        ).joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(", postfix = ")(?![\\p{L}\\p{N}])"),
        RegexOption.IGNORE_CASE,
    )

    /** Kata kunci di nama channel yang menandakan channel podcast / talk show. */
    private val NON_MUSIC_CHANNEL = Regex(
        "(?<![\\p{L}\\p{N}])(podcasts?|talk\\s*show|talkshow)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param durationSec durasi dalam detik, atau angka ≤ 0 kalau tidak diketahui (biasanya siaran langsung).
     * @param isLive true untuk siaran langsung atau rekaman siaran langsung.
     * @param isShort true untuk YouTube Shorts.
     */
    fun isLikelyMusic(
        title: String,
        channel: String,
        durationSec: Long,
        isLive: Boolean = false,
        isShort: Boolean = false,
    ): Boolean {
        if (isLive || isShort) return false
        // Kata kunci podcast dkk. selalu menang, bahkan kalau judulnya juga menyebut "DJ" atau "remix".
        if (NON_MUSIC_TITLE.containsMatchIn(title)) return false
        if (NON_MUSIC_CHANNEL.containsMatchIn(channel)) return false
        val maxDuration = if (MIX_TITLE.containsMatchIn(title)) MAX_MIX_DURATION_SEC else MAX_DURATION_SEC
        return durationSec in MIN_DURATION_SEC..maxDuration
    }
}
