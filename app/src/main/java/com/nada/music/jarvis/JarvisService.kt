package com.nada.music.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nada.music.MainActivity
import com.nada.music.NadaApp
import com.nada.music.R
import com.nada.music.playback.PlayerConnection
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Asisten suara "Jarvis".
 *
 * Alurnya:
 * 1. [WakeWordDetector] mendengarkan kata "Jarvis" terus-menerus (offline, di HP).
 * 2. Begitu terdengar: musik dikecilkan dan Jarvis menjawab "Yes, sir?".
 * 3. [CommandListener] merekam perintah, [VoiceCommand.parse] mengartikannya, lalu dijalankan.
 * 4. Jarvis membalas dengan [JarvisVoice] (suara Kokoro bm_lewis kalau sudah diunduh).
 *
 * Selama telepon (biasa, WhatsApp, dll.) Jarvis otomatis berhenti mendengarkan dan lanjut lagi
 * setelah telepon selesai.
 */
class JarvisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var detector: WakeWordDetector
    private lateinit var listener: CommandListener
    private lateinit var voice: JarvisVoice
    private lateinit var player: PlayerConnection
    private lateinit var audioManager: AudioManager
    private var busy = false
    private var started = false

    /** Berapa kali berturut-turut Jarvis terpanggil tapi perintahnya tidak dimengerti. */
    private var missStreak = 0
    private var sleepTimer: Job? = null

    /** true selama HP sedang dipakai telepon / video call. */
    private var pausedForCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        player = PlayerConnection(this)
        listener = CommandListener(this)
        voice = JarvisVoice(this)
        detector = WakeWordDetector(this)
        JarvisSettings.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        if (!started) {
            started = true
            _running.value = true
            // Siapkan "Yes, sir?" lebih dulu supaya jawabannya langsung terdengar.
            scope.launch { voice.preload(WAKE_REPLY) }
            watchCalls()
            watchSensitivity()
        }
        return START_NOT_STICKY
    }

    // ---- Telepon ----

    private fun isInCall(): Boolean = audioManager.mode in CALL_MODES

    /**
     * Mengecek setiap detik apakah HP sedang menelepon. Cara ini tidak butuh izin tambahan
     * (seperti READ_PHONE_STATE) dan juga mengenali panggilan WhatsApp / Telegram / Meet.
     */
    private fun watchCalls() = scope.launch {
        pausedForCall = isInCall()
        if (pausedForCall) updateNotification() else startDetector()
        while (isActive) {
            delay(1000)
            val inCall = isInCall()
            if (inCall == pausedForCall) continue
            pausedForCall = inCall
            if (inCall) {
                detector.stop()
            } else if (!busy) {
                startDetector()
            }
            updateNotification()
        }
    }

    // ---- Wake word & perintah ----

    /** Kalau kepekaan diubah di aplikasi, detektor dimulai ulang dengan pengaturan baru. */
    private fun watchSensitivity() = scope.launch {
        var first = true
        JarvisSettings.sensitivity.collect {
            if (first) {
                first = false
                return@collect
            }
            if (!busy && !pausedForCall) {
                detector.stop()
                startDetector()
            }
        }
    }

    private fun startDetector() {
        if (pausedForCall) return
        detector.start(JarvisSettings.sensitivity.value) {
            // Dipanggil dari thread mikrofon; tangani di main thread.
            scope.launch { onWake() }
        }
    }

    private suspend fun onWake() {
        if (busy || pausedForCall) return
        busy = true
        // Lepas mikrofon dulu supaya pengenal suara Android bisa memakainya.
        detector.stop()
        val oldVolume = player.playerVolume
        player.playerVolume = oldVolume * DUCK_FACTOR
        var understood = false
        try {
            // Tidak melakukan apa-apa kalau sudah siap; berguna kalau model Kokoro baru selesai diunduh.
            voice.preload(WAKE_REPLY)
            voice.speak(WAKE_REPLY)
            // Saat merekam perintah musik dikecilkan lebih jauh, supaya tidak ikut terdengar.
            player.playerVolume = oldVolume * LISTEN_DUCK_FACTOR
            // Pengaman tambahan: apa pun yang terjadi pada pengenal suara, Jarvis tidak
            // boleh mendengarkan lebih dari LISTEN_TIMEOUT_MS.
            val heard = if (listener.isAvailable) {
                withTimeoutOrNull(LISTEN_TIMEOUT_MS) { listener.listenOnce() }.orEmpty()
            } else {
                emptyList()
            }
            Log.d(TAG, "Terdengar: $heard")
            player.playerVolume = oldVolume
            if (heard.isEmpty()) {
                say("I'm sorry sir, I didn't catch that.")
                return
            }
            // Pilih kemungkinan pertama yang berupa perintah yang dikenali.
            val command = heard.map(VoiceCommand::parse).firstOrNull { it !is VoiceCommand.Unknown }
                ?: VoiceCommand.Unknown(heard.first())
            understood = command !is VoiceCommand.Unknown
            execute(command)
        } catch (e: Exception) {
            Log.e(TAG, "Perintah gagal", e)
        } finally {
            player.playerVolume = oldVolume
            busy = false
            // Di tempat bising "Jarvis" bisa terpanggil sendiri berulang-ulang. Kalau perintah
            // tidak dimengerti, tunggu sebentar lebih lama sebelum mendengarkan lagi.
            if (understood) {
                missStreak = 0
                delay(300)
            } else {
                missStreak++
                delay(if (missStreak >= 2) MISS_COOLDOWN_LONG_MS else MISS_COOLDOWN_MS)
            }
            if (_running.value) startDetector()
        }
    }

    private suspend fun execute(command: VoiceCommand) {
        when (command) {
            VoiceCommand.Pause -> {
                player.pause()
                say("Paused, sir.")
            }
            VoiceCommand.Resume -> {
                say("Right away, sir.")
                player.play()
            }
            VoiceCommand.Next -> {
                say("Next song, sir.")
                player.next()
            }
            VoiceCommand.Previous -> {
                say("Going back, sir.")
                player.previous()
            }
            VoiceCommand.VolumeUp -> {
                repeat(VOLUME_STEPS) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                say("Volume up, sir. ${volumePercent()} percent.")
            }
            VoiceCommand.VolumeDown -> {
                repeat(VOLUME_STEPS) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
                say("Volume down, sir. ${volumePercent()} percent.")
            }
            is VoiceCommand.SetVolume -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (command.percent / 100f * max).roundToInt(),
                    AudioManager.FLAG_SHOW_UI,
                )
                say("Volume set to ${command.percent} percent, sir.")
            }
            VoiceCommand.Sleep -> {
                say("Going offline, sir.")
                stopSelf()
            }
            is VoiceCommand.PlaySong -> playSong(command.query)
            VoiceCommand.Cancel -> say("Very well, sir.")
            VoiceCommand.WhatIsPlaying -> {
                val song = player.currentSong.value
                if (song == null) say("Nothing is playing right now, sir.")
                else say("This is ${song.title}, by ${song.artist}, sir.")
            }
            VoiceCommand.Restart -> {
                say("From the top, sir.")
                player.restart()
            }
            VoiceCommand.ShuffleOn -> {
                player.setShuffle(true)
                say("Shuffle is on, sir.")
            }
            VoiceCommand.ShuffleOff -> {
                player.setShuffle(false)
                say("Shuffle is off, sir.")
            }
            VoiceCommand.RepeatOne -> {
                player.setRepeat(Player.REPEAT_MODE_ONE)
                say("Repeating this song, sir.")
            }
            VoiceCommand.RepeatAll -> {
                player.setRepeat(Player.REPEAT_MODE_ALL)
                say("Repeating the whole queue, sir.")
            }
            VoiceCommand.RepeatOff -> {
                player.setRepeat(Player.REPEAT_MODE_OFF)
                say("Repeat is off, sir.")
            }
            VoiceCommand.Like -> {
                val song = player.currentSong.value
                if (song == null) {
                    say("Nothing is playing right now, sir.")
                } else {
                    (application as NadaApp).repository.setLiked(song, true)
                    say("Added to your favourites, sir.")
                }
            }
            VoiceCommand.PlayLiked -> {
                val liked = (application as NadaApp).repository.likedSongs.first()
                if (liked.isEmpty()) {
                    say("You have no favourite songs yet, sir.")
                } else {
                    say("Playing your favourites, sir.")
                    player.playSongs(liked, shuffle = true)
                }
            }
            VoiceCommand.Mute -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            }
            VoiceCommand.TellTime -> {
                val now = java.time.LocalTime.now()
                say("It's ${now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))}, sir.")
            }
            is VoiceCommand.Seek -> {
                player.seekBy(command.seconds * 1000L)
                say(if (command.seconds >= 0) "Forward ${command.seconds} seconds, sir." else "Back ${-command.seconds} seconds, sir.")
            }
            is VoiceCommand.SleepTimer -> {
                sleepTimer?.cancel()
                sleepTimer = scope.launch {
                    delay(command.minutes * 60_000L)
                    player.pause()
                    sleepTimer = null
                }
                say("Music will stop in ${command.minutes} minutes, sir.")
            }
            VoiceCommand.CancelSleepTimer -> {
                if (sleepTimer == null) {
                    say("There is no timer running, sir.")
                } else {
                    sleepTimer?.cancel()
                    sleepTimer = null
                    say("Timer cancelled, sir.")
                }
            }
            is VoiceCommand.Unknown -> say("I'm sorry sir, I don't know that command yet.")
        }
    }

    private suspend fun playSong(query: String) {
        val repository = (application as NadaApp).repository
        val song = runCatching { repository.search(query).songs.firstOrNull() }.getOrNull()
        if (song == null) {
            say("I'm sorry sir, I couldn't find that song.")
            return
        }
        // Jawab dulu, baru putar, supaya suara Jarvis tidak bertabrakan dengan lagunya.
        say("Playing ${song.title}, by ${song.artist}, sir.")
        player.playSongs(listOf(song))
    }

    /** Jarvis bicara sambil mengecilkan musik, lalu volume musik dikembalikan. */
    private suspend fun say(text: String) {
        val old = player.playerVolume
        player.playerVolume = old * DUCK_FACTOR
        try {
            voice.speak(text)
        } finally {
            player.playerVolume = old
        }
    }

    private fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    // ---- Notifikasi ----

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tanda bahwa Jarvis sedang mendengarkan kata \"Jarvis\""
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (pausedForCall) "Jarvis dijeda" else "Jarvis aktif")
            .setContentText(
                if (pausedForCall) "Sedang telepon. Jarvis lanjut mendengarkan setelah telepon selesai."
                else "Ucapkan \"Jarvis\" lalu perintahmu",
            )
            .setContentIntent(open)
            .addAction(0, "Matikan Jarvis", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        _running.value = false
        detector.release()
        voice.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Jarvis"
        private const val CHANNEL_ID = "jarvis"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.nada.music.jarvis.STOP"
        private const val DUCK_FACTOR = 0.15f
        private const val VOLUME_STEPS = 2

        /** Volume musik saat perintah sedang direkam. */
        private const val LISTEN_DUCK_FACTOR = 0.05f

        /** Batas paling lama Jarvis mendengarkan satu perintah (pengaman kalau pengenal suara macet). */
        private const val LISTEN_TIMEOUT_MS = 11_000L

        /** Jeda sebelum mendengarkan "Jarvis" lagi setelah perintah tidak dimengerti. */
        private const val MISS_COOLDOWN_MS = 1_500L
        private const val MISS_COOLDOWN_LONG_MS = 5_000L

        /** Jawaban saat dipanggil "Jarvis". Disiapkan sekali lalu diputar dari memori. */
        const val WAKE_REPLY = "Yes, sir?"

        /** Mode audio saat ada telepon biasa, telepon berdering, atau panggilan VoIP. */
        private val CALL_MODES = setOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_CALL_SCREENING,
        )

        private val _running = MutableStateFlow(false)

        /** true selama Jarvis sedang aktif (untuk tombol di UI). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }
}
