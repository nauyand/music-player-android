package com.nada.music.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mendengarkan kata "Jarvis" terus-menerus, sepenuhnya di HP (offline), dengan sherpa-onnx.
 *
 * Model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M (int8, ±5 MB) di `assets/kws/`.
 * Suara tidak pernah dikirim ke internet di tahap ini.
 *
 * Diagnostik:
 * - Setiap 3 detik level suara mikrofon ditulis ke log (tag "WakeWord").
 * - Kalau file `jarvis-debug` ada di folder `Android/data/com.nada.music/files/`:
 *   - 10 detik suara terakhir disimpan sebagai `jarvis-mic.wav`;
 *   - setiap ada suara keras (misalnya orang memanggil "Jarvis"), potongan 3 detiknya disimpan di
 *     `jarvis-clips/` (paling banyak [MAX_CLIPS] file), lengkap dengan apakah Jarvis terpanggil.
 *   Rekaman ini untuk menyetel Jarvis dengan suara pemakai yang asli, dan hanya ada di HP.
 */
class WakeWordDetector(private val context: Context) {

    private val spotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.int8.onnx",
                    decoder = "kws/decoder.int8.onnx",
                    joiner = "kws/joiner.int8.onnx",
                ),
                tokens = "kws/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
            ),
            keywordsFile = "kws/keywords.txt",
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        ),
    )

    /**
     * Satu sesi mendengarkan. Tiap sesi punya penanda [active] sendiri, supaya thread lama yang
     * terlambat berhenti tidak ikut "hidup lagi" saat sesi baru dimulai.
     */
    private class Session(val active: AtomicBoolean, val thread: Thread)

    @Volatile
    private var session: Session? = null

    /** true selama thread mikrofon benar-benar berjalan. */
    val isListening: Boolean get() = session?.let { it.active.get() && it.thread.isAlive } == true

    /**
     * true kalau mikrofon terbuka tapi suaranya dibungkam sistem, biasanya karena aplikasi lain
     * (telepon WhatsApp / Discord, perekam suara, Google Assistant) sedang memakai mikrofon.
     */
    @Volatile
    var silenced = false
        private set

    /** Mulai mendengarkan. [onWake] dipanggil dari thread latar setiap kali "Jarvis" terdengar. */
    @Synchronized
    fun start(sensitivity: JarvisSensitivity, onWake: () -> Unit) {
        if (isListening) return
        session?.active?.set(false)
        val active = AtomicBoolean(true)
        val thread = Thread({ listen(active, sensitivity, onWake) }, "jarvis-wakeword")
        session = Session(active, thread)
        thread.start()
    }

    @SuppressLint("MissingPermission") // izin RECORD_AUDIO dicek sebelum service dijalankan
    private fun listen(active: AtomicBoolean, sensitivity: JarvisSensitivity, onWake: () -> Unit) {
        try {
            listenLoop(active, sensitivity, onWake)
        } catch (e: Exception) {
            // Jangan sampai aplikasi tertutup; service akan mencoba menyalakan ulang.
            Log.e(TAG, "Mikrofon berhenti karena error", e)
        } finally {
            active.set(false)
            silenced = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun listenLoop(active: AtomicBoolean, sensitivity: JarvisSensitivity, onWake: () -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Sumber MIC sama dengan contoh resmi sherpa-onnx; VOICE_RECOGNITION di sebagian HP
        // memberi suara yang sangat pelan / sudah diproses.
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, SAMPLE_RATE / 5 * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mikrofon tidak bisa dibuka (mungkin sedang dipakai aplikasi lain)")
            record.release()
            return
        }
        Log.i(TAG, "Mulai mendengarkan, kepekaan=${sensitivity.name} (${sensitivity.keywords})")
        val stream = spotter.createStream(sensitivity.keywords)
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms
        val ring = ShortArray(SAMPLE_RATE * DEBUG_SECONDS)
        var ringPos = 0
        var ringFull = false
        var statSumSquares = 0.0
        var statPeak = 0
        var statSamples = 0
        var lastStatAt = System.currentTimeMillis()
        val debugDir = context.getExternalFilesDir(null)
        val debug = debugDir != null && File(debugDir, DEBUG_FLAG).exists()
        val clips = if (debug) ClipRecorder(File(debugDir, CLIP_DIR)) else null
        var lastSilenceCheck = 0L
        var zeroReads = 0

        record.startRecording()
        try {
            while (active.get()) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    // ERROR_DEAD_OBJECT dll.: mikrofon diambil sistem. Keluar; service menyalakan ulang.
                    Log.w(TAG, "Gagal membaca mikrofon: $n")
                    break
                }
                if (n == 0) {
                    if (++zeroReads > 50) {
                        Log.w(TAG, "Mikrofon tidak memberi suara")
                        break
                    }
                    Thread.sleep(20)
                    continue
                }
                zeroReads = 0

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSilenceCheck >= 1000 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    lastSilenceCheck = nowMs
                    val muted = record.activeRecordingConfiguration?.isClientSilenced == true
                    if (muted != silenced) {
                        silenced = muted
                        Log.i(TAG, if (muted) "Mikrofon dibungkam sistem (dipakai aplikasi lain)" else "Mikrofon normal lagi")
                    }
                }

                for (i in 0 until n) {
                    val s = buffer[i].toInt()
                    statSumSquares += (s * s).toDouble()
                    if (kotlin.math.abs(s) > statPeak) statPeak = kotlin.math.abs(s)
                    ring[ringPos] = buffer[i]
                    ringPos = (ringPos + 1) % ring.size
                    if (ringPos == 0) ringFull = true
                }
                statSamples += n

                val samples = FloatArray(n) { buffer[it] / 32768f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (spotter.isReady(stream)) spotter.decode(stream)
                val result = spotter.getResult(stream)
                val woke = result.keyword.isNotBlank()
                clips?.feed(buffer, n, woke)
                if (woke) {
                    Log.i(TAG, "Terdeteksi: ${result.keyword} token=${result.tokens.joinToString(" ")}")
                    spotter.reset(stream)
                    clips?.flush()
                    onWake()
                }

                val now = System.currentTimeMillis()
                if (now - lastStatAt >= STAT_INTERVAL_MS) {
                    val rms = sqrt(statSumSquares / statSamples.coerceAtLeast(1))
                    Log.i(TAG, "Level mikrofon: rms=${dbfs(rms)} dBFS, puncak=${dbfs(statPeak.toDouble())} dBFS")
                    statSumSquares = 0.0
                    statPeak = 0
                    statSamples = 0
                    lastStatAt = now
                    if (debug) {
                        writeWav(File(debugDir, DEBUG_WAV), ring, ringPos, ringFull)
                    }
                }
            }
        } finally {
            record.stop()
            record.release()
            stream.release()
            Log.i(TAG, "Berhenti mendengarkan")
        }
    }

    private fun dbfs(value: Double): String =
        if (value <= 0.0) "-inf" else "%.1f".format(20 * log10(value / 32768.0))

    /**
     * Menyimpan potongan suara keras (kemungkinan orang bicara) untuk menyetel Jarvis.
     * Potongan dimulai 1 detik sebelum suara keras dan disimpan 2 detik setelahnya, dengan nama
     * `clip-<waktu>-wake.wav` kalau Jarvis terpanggil, atau `-miss.wav` kalau tidak.
     */
    private inner class ClipRecorder(private val dir: File) {
        private val pre = ShortArray(SAMPLE_RATE) // 1 detik sebelum suara keras
        private var prePos = 0
        private var preFull = false
        private var clip: ShortArray? = null
        private var clipLen = 0
        private var woke = false

        fun feed(buf: ShortArray, n: Int, wokeNow: Boolean) {
            val current = clip
            if (current != null) {
                val take = minOf(n, current.size - clipLen)
                System.arraycopy(buf, 0, current, clipLen, take)
                clipLen += take
                if (wokeNow) woke = true
                if (clipLen >= current.size) flush()
            } else {
                var peak = 0
                for (i in 0 until n) peak = max(peak, kotlin.math.abs(buf[i].toInt()))
                if (peak >= CLIP_TRIGGER_PEAK || wokeNow) {
                    val c = ShortArray(SAMPLE_RATE * CLIP_SECONDS)
                    val preCount = if (preFull) pre.size else prePos
                    val preStart = if (preFull) prePos else 0
                    for (i in 0 until preCount) c[i] = pre[(preStart + i) % pre.size]
                    clipLen = preCount
                    clip = c
                    woke = wokeNow
                    feed(buf, n, wokeNow)
                    return
                }
            }
            for (i in 0 until n) {
                pre[prePos] = buf[i]
                prePos = (prePos + 1) % pre.size
                if (prePos == 0) preFull = true
            }
        }

        fun flush() {
            val c = clip ?: return
            clip = null
            runCatching {
                dir.mkdirs()
                val old = dir.listFiles()?.sortedBy { it.name }.orEmpty()
                old.take((old.size - MAX_CLIPS + 1).coerceAtLeast(0)).forEach { it.delete() }
                val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
                val name = "clip-$stamp-${if (woke) "wake" else "miss"}.wav"
                writeWav(File(dir, name), c, clipLen, false)
                Log.i(TAG, "Sampel suara disimpan: $name")
            }
            prePos = 0
            preFull = false
        }
    }

    /** Menyimpan isi ring buffer (urut dari yang paling lama) sebagai WAV 16 kHz mono. */
    private fun writeWav(file: File, ring: ShortArray, pos: Int, full: Boolean) {
        val count = if (full) ring.size else pos
        val start = if (full) pos else 0
        val data = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = ring[(start + i) % ring.size].toInt()
            data[i * 2] = (s and 0xff).toByte()
            data[i * 2 + 1] = (s shr 8 and 0xff).toByte()
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            f.write("RIFF".toByteArray()); f.writeIntLE(36 + data.size); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.writeIntLE(16); f.writeShortLE(1); f.writeShortLE(1)
            f.writeIntLE(SAMPLE_RATE); f.writeIntLE(SAMPLE_RATE * 2); f.writeShortLE(2); f.writeShortLE(16)
            f.write("data".toByteArray()); f.writeIntLE(data.size); f.write(data)
        }
        tmp.renameTo(file)
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff); write(v shr 16 and 0xff); write(v shr 24 and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff)
    }

    /** Berhenti mendengarkan dan melepas mikrofon (misalnya saat perintah sedang direkam). */
    @Synchronized
    fun stop() {
        val s = session ?: return
        s.active.set(false)
        s.thread.join(500)
        session = null
        silenced = false
    }

    fun release() {
        stop()
        spotter.release()
    }

    private companion object {
        const val TAG = "WakeWord"
        const val SAMPLE_RATE = 16000
        const val STAT_INTERVAL_MS = 3000L
        const val DEBUG_SECONDS = 10
        const val DEBUG_FLAG = "jarvis-debug"
        const val DEBUG_WAV = "jarvis-mic.wav"
        const val CLIP_DIR = "jarvis-clips"
        const val CLIP_SECONDS = 3
        const val MAX_CLIPS = 60

        /** Kira-kira -18 dBFS: suara orang bicara dekat HP, bukan suara latar. */
        const val CLIP_TRIGGER_PEAK = 4000
    }
}
