package com.nada.music.jarvis

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contoh suara "Jarvis" milik pengguna, disimpan sebagai WAV 16 kHz mono di
 * `Android/data/com.nada.music/files/jarvis-voice/`. Hanya ada di HP, tidak pernah diunggah.
 */
object VoiceSamples {

    private const val DIR = "jarvis-voice"

    /** Bertambah setiap kali contoh suara berubah, supaya detektor tahu harus memuat ulang. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** true selama layar "Latih suara" sedang merekam (mikrofon dipakai di sana, bukan oleh detektor). */
    val enrolling = MutableStateFlow(false)

    fun dir(context: Context): File? = context.getExternalFilesDir(DIR)?.also { it.mkdirs() }

    fun files(context: Context): List<File> =
        dir(context)?.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()

    fun count(context: Context): Int = files(context).size

    fun save(context: Context, samples: ShortArray): File? {
        val d = dir(context) ?: return null
        val file = File(d, "contoh-${System.currentTimeMillis()}.wav")
        writeWav(file, samples)
        _version.value++
        return file
    }

    fun clear(context: Context) {
        files(context).forEach { it.delete() }
        _version.value++
    }

    /** Dipanggil kalau file contoh ditambahkan dari luar aplikasi (mis. lewat adb). */
    fun notifyChanged() {
        _version.value++
    }

    fun read(file: File): ShortArray? = runCatching {
        val bytes = file.readBytes()
        // Cari chunk "data" (header WAV standar 44 byte, tapi jangan diasumsikan).
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val end = minOf(bytes.size, pos + 8 + size)
                val sb = ByteBuffer.wrap(bytes, pos + 8, end - pos - 8).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return@runCatching ShortArray(sb.remaining()).also { sb.get(it) }
            }
            pos += 8 + size
        }
        null
    }.getOrNull()

    fun writeWav(file: File, samples: ShortArray, sampleRate: Int = VoiceEmbedder.SAMPLE_RATE) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            f.write(header.array())
            f.write(data.array())
        }
        tmp.renameTo(file)
    }
}
