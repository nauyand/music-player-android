package com.nada.music.ui.jarvis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nada.music.jarvis.VoiceEmbedder
import com.nada.music.jarvis.VoiceSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10

/** Lama satu rekaman contoh. Cukup untuk "Jarvis" atau "Hey Jarvis" dengan jeda di depan. */
private const val RECORD_MS = 2500

private sealed interface TrainingState {
    data object Idle : TrainingState
    data class Recording(val progress: Float) : TrainingState
    data object Checking : TrainingState
}

/**
 * Merekam contoh suara "Jarvis" milik pengguna. Jarvis kemudian mengenali suara yang mirip
 * dengan contoh-contoh ini, jadi pelafalan apa pun (aksen Indonesia, "Jarvis" / "Hey Jarvis") bisa dipakai.
 */
@Composable
fun VoiceTrainingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(VoiceSamples.count(context)) }
    var state by remember { mutableStateOf<TrainingState>(TrainingState.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val embedder = remember { runCatching { VoiceEmbedder(context) }.getOrNull() }

    // Selama dialog terbuka, detektor Jarvis melepas mikrofon.
    DisposableEffect(Unit) {
        VoiceSamples.enrolling.value = true
        onDispose {
            VoiceSamples.enrolling.value = false
            embedder?.close()
        }
    }

    fun record() {
        message = null
        scope.launch {
            state = TrainingState.Recording(0f)
            val samples = withContext(Dispatchers.IO) {
                recordClip(RECORD_MS) { p -> progress = p }
            }
            state = TrainingState.Checking
            val peakDb = samples?.let { peakDbfs(it) } ?: -120f
            val usable = samples != null && peakDb > -35f &&
                withContext(Dispatchers.Default) { embedder?.templateFrom(samples) != null }
            if (usable && samples != null) {
                VoiceSamples.save(context, samples)
                count = VoiceSamples.count(context)
                message = when {
                    count < 3 -> "Bagus! Rekam ${3 - count} kali lagi."
                    else -> "Tersimpan. Jarvis sudah bisa dipakai; rekam lagi kalau ingin lebih akurat."
                }
            } else {
                message = if (peakDb <= -35f) {
                    "Suaranya terlalu pelan atau tidak terdengar. Coba lebih dekat ke HP."
                } else {
                    "Suaranya kurang jelas. Coba ucapkan \"Jarvis\" sekali saja, lalu diam."
                }
            }
            state = TrainingState.Idle
        }
    }

    AlertDialog(
        onDismissRequest = { if (state == TrainingState.Idle) onDismiss() },
        title = { Text("Latih suara Jarvis") },
        text = {
            Column {
                Text(
                    "Ketuk Rekam, lalu ucapkan \"Jarvis\" seperti biasa Anda memanggilnya. " +
                        "Ulangi 3–5 kali. Jarvis akan mengenali suara Anda dari contoh-contoh ini.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Contoh tersimpan: $count",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(12.dp))
                when (val s = state) {
                    is TrainingState.Recording -> {
                        Text("🎙️ Ucapkan \"Jarvis\" sekarang…", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    TrainingState.Checking -> Text("Memeriksa rekaman…")
                    TrainingState.Idle -> message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (embedder == null) {
                    Text("Model suara tidak bisa dimuat.", color = MaterialTheme.colorScheme.error)
                }
                if (count > 0 && state == TrainingState.Idle) {
                    TextButton(onClick = {
                        VoiceSamples.clear(context)
                        count = 0
                        message = "Semua contoh dihapus."
                    }) { Text("Hapus semua contoh") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { record() }, enabled = state == TrainingState.Idle && embedder != null) {
                Text(if (count == 0) "Rekam" else "Rekam lagi")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = state == TrainingState.Idle) { Text("Selesai") }
        },
    )
}

@SuppressLint("MissingPermission") // izin mikrofon sudah diminta sebelum dialog ini dibuka
private suspend fun recordClip(durationMs: Int, onProgress: (Float) -> Unit): ShortArray? {
    val rate = VoiceEmbedder.SAMPLE_RATE
    val total = rate * durationMs / 1000
    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record = AudioRecord(
        MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuffer, rate / 2),
    )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        return null
    }
    // Beri jeda singkat supaya detektor Jarvis sempat melepas mikrofon.
    delay(300)
    val out = ShortArray(total)
    var filled = 0
    record.startRecording()
    try {
        while (filled < total) {
            val n = record.read(out, filled, minOf(1600, total - filled))
            if (n <= 0) break
            filled += n
            onProgress(filled.toFloat() / total)
        }
    } finally {
        record.stop()
        record.release()
    }
    return if (filled > rate / 2) out.copyOf(filled) else null
}

private fun peakDbfs(samples: ShortArray): Float {
    var peak = 1
    for (s in samples) peak = maxOf(peak, abs(s.toInt()))
    return (20 * log10(peak / 32768.0)).toFloat()
}
