package com.nada.music.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nada.music.MainActivity
import com.nada.music.R
import com.nada.music.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/**
 * Menjalankan [RemoteServer] di latar belakang, supaya musik bisa diatur dari browser PC
 * lewat alamat seperti `http://192.168.1.5:8080`.
 */
class RemoteService : Service() {

    private var server: RemoteServer? = null
    private var player: PlayerConnection? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val pin = pin(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null, pin),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (server == null) startServer(pin)
        return START_NOT_STICKY
    }

    private fun startServer(pin: String) {
        val connection = PlayerConnection(this).also { player = it }
        for (port in PORTS) {
            val s = RemoteServer(this, connection, pin, port)
            try {
                s.start(SOCKET_TIMEOUT_MS, false)
                server = s
                _info.value = Info(port, pin)
                Log.i(TAG, "Remote aktif di port $port: ${addresses().joinToString { "http://$it:$port" }}")
                break
            } catch (e: IOException) {
                Log.w(TAG, "Port $port tidak bisa dipakai", e)
            }
        }
        if (server == null) {
            Log.e(TAG, "Tidak ada port yang bisa dipakai")
            stopSelf()
            return
        }
        acquireLocks()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(_info.value, pin))
    }

    /** Supaya Wi-Fi dan CPU tidak tidur saat layar mati, server tetap bisa dibuka dari PC. */
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nautify:remote")
            ?.apply { setReferenceCounted(false); acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nautify:remote")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun buildNotification(info: Info?, pin: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote PC", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tanda bahwa musik bisa diatur dari browser PC"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val url = info?.let { i -> addresses().firstOrNull()?.let { "http://$it:${i.port}" } }
        val text = when {
            info == null -> "Menyiapkan…"
            url == null -> "Sambungkan HP ke Wi-Fi atau nyalakan hotspot. PIN $pin"
            else -> "Buka $url di PC · PIN $pin"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Remote PC aktif")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Matikan remote", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        player?.release()
        player = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        _info.value = null
        super.onDestroy()
    }

    /** Port dan PIN server yang sedang berjalan. */
    data class Info(val port: Int, val pin: String)

    companion object {
        private const val TAG = "Remote"
        private const val CHANNEL_ID = "remote"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "com.nada.music.remote.STOP"
        private const val SOCKET_TIMEOUT_MS = 30_000
        private val PORTS = listOf(8080, 8081, 8888, 8090)
        private const val PREFS = "remote"
        private const val KEY_PIN = "pin"

        private val _info = MutableStateFlow<Info?>(null)

        /** null kalau remote tidak aktif. */
        val info: StateFlow<Info?> = _info.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteService::class.java))
        }

        /** PIN 4 angka, dibuat sekali lalu disimpan supaya browser PC tidak perlu memasukkan ulang. */
        fun pin(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_PIN, null)?.let { return it }
            val pin = "%04d".format(Random.nextInt(10_000))
            prefs.edit().putString(KEY_PIN, pin).apply()
            return pin
        }

        /** Buat PIN baru (mis. kalau PIN lama sudah diketahui orang lain). */
        fun resetPin(context: Context): String {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PIN).apply()
            return pin(context)
        }

        /** Alamat IPv4 HP di jaringan lokal (Wi-Fi, hotspot, USB tethering). */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                // Wi-Fi (wlan) dan hotspot (ap / swlan) didahulukan, data seluler (rmnet) tidak dipakai.
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.contains("ap")) 1 else 2 }
                .flatMap { nif -> nif.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
