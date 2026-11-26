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

    /** Batalkan, misalnya "nggak jadi" atau "cancel". Jarvis diam saja dan kembali menunggu. */
    data object Cancel : VoiceCommand

    /** "Lagu apa ini?": Jarvis menyebut judul dan penyanyi lagu yang sedang diputar. */
    data object WhatIsPlaying : VoiceCommand

    /** Putar ulang lagu ini dari awal. */
    data object Restart : VoiceCommand

    data object ShuffleOn : VoiceCommand
    data object ShuffleOff : VoiceCommand

    /** Ulangi lagu ini terus. */
    data object RepeatOne : VoiceCommand

    /** Ulangi seluruh antrean. */
    data object RepeatAll : VoiceCommand
    data object RepeatOff : VoiceCommand

    /** Tandai lagu yang sedang diputar sebagai disukai. */
    data object Like : VoiceCommand

    /** Putar semua lagu yang disukai (acak). */
    data object PlayLiked : VoiceCommand

    data object Mute : VoiceCommand

    /** "Jam berapa?" */
    data object TellTime : VoiceCommand

    /** Maju / mundur beberapa detik; [seconds] negatif berarti mundur. */
    data class Seek(val seconds: Int) : VoiceCommand

    /** Jeda musik otomatis setelah [minutes] menit. */
    data class SleepTimer(val minutes: Int) : VoiceCommand
    data object CancelSleepTimer : VoiceCommand

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

        private val CANCEL = setOf(
            "batal", "batalkan", "gak jadi", "nggak jadi", "ga jadi", "enggak jadi", "tidak jadi", "cancel",
            "never mind", "nevermind", "nothing", "bukan apa-apa", "gak ada", "nggak ada", "tidak ada", "salah",
            "salah panggil",
        )
        private val WHAT_IS_PLAYING = setOf(
            "lagu apa ini", "ini lagu apa", "lagu apa", "judul lagu", "judulnya apa", "apa judulnya",
            "siapa penyanyinya", "ini lagu siapa", "lagu siapa ini", "lagu apa yang diputar", "what song is this",
            "what's playing", "what is playing", "whats playing", "what is this song", "who sings this",
        )
        private val RESTART = setOf(
            "ulang dari awal", "dari awal", "mulai dari awal", "ulangi dari awal", "putar dari awal", "restart",
            "start over", "from the beginning",
        )
        private val SHUFFLE_ON = setOf(
            "acak", "acak lagu", "acak lagunya", "shuffle", "shuffle on", "putar acak", "mode acak", "nyalakan acak",
        )
        private val SHUFFLE_OFF = setOf(
            "jangan acak", "matikan acak", "acak mati", "shuffle off", "stop shuffle", "urut", "putar berurutan",
        )
        private val REPEAT_ONE = setOf(
            "ulangi lagu ini", "ulangi lagu", "ulang lagu ini", "repeat", "repeat one", "repeat this song",
            "putar terus lagu ini", "lagu ini terus", "loop", "ulangi terus",
        )
        private val REPEAT_ALL = setOf("ulangi semua", "repeat all", "ulangi antrean", "ulangi playlist")
        private val REPEAT_OFF = setOf(
            "jangan diulang", "matikan ulang", "matikan repeat", "repeat off", "stop repeat", "jangan ulangi",
        )
        private val LIKE = setOf(
            "suka", "suka lagu ini", "aku suka lagu ini", "saya suka lagu ini", "like", "like lagu ini",
            "like this song", "i like this song", "simpan lagu ini", "tambahkan ke favorit", "favoritkan",
            "masukkan ke favorit", "love this song",
        )
        private val PLAY_LIKED = setOf(
            "putar lagu favorit", "putar favorit", "lagu favorit", "putar lagu yang disukai", "lagu yang disukai",
            "putar yang disukai", "mainkan lagu favorit", "play my favorites", "play favorites", "play liked songs",
            "putar lagu kesukaan", "lagu kesukaan",
        )
        private val MUTE = setOf("mute", "bisukan", "senyap", "tanpa suara", "matikan suara")
        private val TELL_TIME = setOf(
            "jam berapa", "jam berapa sekarang", "sekarang jam berapa", "what time is it", "what's the time", "time",
        )
        private val CANCEL_TIMER = setOf(
            "batalkan timer", "matikan timer", "hapus timer", "cancel timer", "batalkan sleep timer", "stop timer",
        )

        /** "maju 30 detik", "mundur 10 detik", "skip 15 seconds", "rewind 10 seconds". */
        private val SEEK = Regex(
            "^(maju|majukan|percepat|forward|skip|mundur|mundurkan|rewind|back)\\s+(\\d{1,3})\\s*(detik|seconds?|secs?|s)$",
        )

        /** "matikan musik dalam 30 menit", "sleep timer 15 menit", "timer 20". */
        private val SLEEP_TIMER = Regex(
            "^(?:(?:matikan|stop|berhenti|hentikan|pause|jeda)(?:\\s+(?:musik|lagu|musiknya|lagunya))?" +
                "\\s+(?:dalam|setelah|sesudah|in|after)|(?:sleep\\s+)?timer|tidur\\s+dalam)" +
                "\\s+(\\d{1,3})\\s*(?:menit|minutes?|mins?|m)?$",
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
                in CANCEL -> return Cancel
                in WHAT_IS_PLAYING -> return WhatIsPlaying
                in RESTART -> return Restart
                in SHUFFLE_ON -> return ShuffleOn
                in SHUFFLE_OFF -> return ShuffleOff
                in REPEAT_ONE -> return RepeatOne
                in REPEAT_ALL -> return RepeatAll
                in REPEAT_OFF -> return RepeatOff
                in LIKE -> return Like
                in PLAY_LIKED -> return PlayLiked
                in MUTE -> return Mute
                in TELL_TIME -> return TellTime
                in CANCEL_TIMER -> return CancelSleepTimer
            }

            SEEK.find(text)?.let { m ->
                val n = m.groupValues[2].toInt()
                val back = m.groupValues[1] in setOf("mundur", "mundurkan", "rewind", "back")
                return Seek(if (back) -n else n)
            }

            SLEEP_TIMER.find(text)?.let { m ->
                val n = m.groupValues[1].toInt()
                if (n > 0) return SleepTimer(n.coerceAtMost(240))
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
                .replace('’', '\'')
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
