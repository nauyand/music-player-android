package com.nada.music.jarvis

/** Perintah yang dipahami Jarvis setelah kata "Jarvis" diucapkan. */
sealed interface VoiceCommand {
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    data object VolumeUp : VoiceCommand
    data object VolumeDown : VoiceCommand

    /** Matikan Jarvis sendiri, misalnya "matikan Jarvis" atau "go to sleep". */
    data object Sleep : VoiceCommand

    /** Atur volume ke persentase tertentu, misalnya "volume 50". */
    data class SetVolume(val percent: Int) : VoiceCommand

    /** Cari lagu lalu putar, misalnya "putar Sial Mahalini". */
    data class PlaySong(val query: String) : VoiceCommand

    /** Ucapan tidak dikenali. */
    data class Unknown(val text: String) : VoiceCommand

    companion object {

        /** Kata yang menandakan "cari dan putar lagu ini". */
        private val PLAY_PREFIX = Regex(
            "^(tolong\\s+)?(putarkan|putar|mainkan|setel|setelkan|nyalakan|play|cari|carikan|search)" +
                "(\\s+(lagu|lagunya|musik|song|the song))?\\s+(.+)$",
        )

        private val SET_VOLUME = Regex("^(volume|suara)\\s+(ke\\s+|jadi\\s+|to\\s+)?(\\d{1,3})(\\s*(persen|%|percent))?$")

        private val PAUSE = setOf(
            "stop", "pause", "berhenti", "hentikan", "stop lagu", "stop musik", "jeda", "jeda dulu",
            "matikan", "matikan lagu", "matikan musik", "diam", "pause musik", "pause lagu",
            "berhenti dulu", "stop dulu", "pause dulu",
        )
        private val RESUME = setOf(
            "play", "putar", "lanjut", "lanjutkan", "lanjutkan lagu", "putar lagi", "mainkan", "resume",
            "continue", "putar musik", "nyalakan musik", "play music", "lanjutin",
            "putar lagu", "mainkan lagu", "mainkan musik", "play song", "setel lagu",
        )
        private val NEXT = setOf(
            "next", "skip", "lewati", "lewat", "selanjutnya", "berikutnya", "lagu berikutnya", "lagu selanjutnya",
            "ganti lagu", "ganti", "next song", "skip lagu", "skip this", "lagu lain",
        )
        private val PREVIOUS = setOf(
            "previous", "sebelumnya", "lagu sebelumnya", "kembali", "mundur", "back", "go back",
            "previous song", "lagu yang tadi", "yang tadi", "balik",
        )
        private val VOLUME_UP = setOf(
            "volume naik", "naikkan volume", "naikin volume", "volume up", "keraskan", "kerasin",
            "keraskan suara", "besarkan suara", "besarkan volume", "louder", "turn it up", "suara naik",
            "tambah volume", "volume tambah", "lebih keras",
        )
        private val VOLUME_DOWN = setOf(
            "volume turun", "turunkan volume", "turunin volume", "volume down", "kecilkan", "kecilin",
            "kecilkan suara", "kecilkan volume", "pelankan", "pelanin", "quieter", "turn it down",
            "suara turun", "kurangi volume", "volume kurang", "lebih pelan",
        )

        /** Kata sapaan di awal / akhir yang tidak mengubah arti, termasuk "Jarvis" sendiri. */
        private val FILLER_START = Regex("^((hey|hei|hai|halo|ok|oke|okay|jarvis|tolong|please|dong)[\\s,]+)+")
        private val FILLER_END = Regex("([\\s,]+(dong|ya|yah|please|jarvis|sekarang|now|aja|saja))+$")

        /** "matikan Jarvis" dsb. Dicek sebelum kata "Jarvis" dibuang sebagai sapaan. */
        private val SLEEP_WITH_NAME = Regex(
            "\\b(matikan|nonaktifkan|tidurkan|istirahatkan|turn off|shut down|shutdown|disable)\\s+jarvis\\b",
        )
        private val SLEEP = setOf(
            "tidur", "tidur dulu", "istirahat", "sleep", "go to sleep", "offline", "go offline",
            "shut down", "shutdown", "jarvis off", "nonaktif", "selamat tidur", "bye", "dadah",
        )

        fun parse(spoken: String): VoiceCommand {
            if (SLEEP_WITH_NAME.containsMatchIn(spoken.lowercase())) return Sleep
            val text = normalize(spoken)
            if (text.isEmpty()) return Unknown(spoken)

            when (text) {
                in SLEEP -> return Sleep
                in PAUSE -> return Pause
                in RESUME -> return Resume
                in NEXT -> return Next
                in PREVIOUS -> return Previous
                in VOLUME_UP -> return VolumeUp
                in VOLUME_DOWN -> return VolumeDown
            }

            SET_VOLUME.find(text)?.let { m ->
                val n = m.groupValues[3].toInt().coerceIn(0, 100)
                return SetVolume(n)
            }

            PLAY_PREFIX.find(text)?.let { m ->
                val query = cleanQuery(m.groupValues[5])
                if (query.isNotEmpty()) return PlaySong(query)
            }
            return Unknown(spoken)
        }

        /** Huruf kecil, tanpa tanda baca, tanpa sapaan. */
        internal fun normalize(spoken: String): String {
            var t = spoken.lowercase()
                .replace(Regex("[.!?\"“”]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            t = t.replace(FILLER_START, "").replace(FILLER_END, "").trim(' ', ',')
            return t
        }

        /** "tulus hati-hati di jalan" tetap; "sial dari mahalini" → "sial mahalini". */
        private fun cleanQuery(raw: String): String =
            raw.replace(Regex("\\s+(dari|oleh|by|punya|nya)\\s+"), " ")
                .replace(Regex("^(yang\\s+judulnya|judulnya|berjudul)\\s+"), "")
                .trim()
    }
}
