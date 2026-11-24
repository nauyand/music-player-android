package com.nada.music.ui.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.nada.music.jarvis.JarvisSensitivity
import com.nada.music.jarvis.JarvisSettings
import com.nada.music.jarvis.VoiceSamples
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nada.music.NadaApp
import com.nada.music.jarvis.JarvisService
import com.nada.music.jarvis.KokoroModel

/**
 * Tombol mikrofon di beranda untuk menyalakan / mematikan Jarvis.
 * Saat pertama dinyalakan: minta izin mikrofon, lalu tawarkan unduhan suara Kokoro.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JarvisButton() {
    val context = LocalContext.current
    val running by JarvisService.running.collectAsState()
    val model by KokoroModel.state.collectAsState()
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTraining by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { KokoroModel.refresh(context) }

    fun startJarvis() {
        JarvisService.start(context)
        when {
            // Jarvis mengenali suara dari contoh rekaman pengguna, jadi latih dulu kalau belum ada.
            VoiceSamples.count(context) == 0 -> showTraining = true
            !KokoroModel.isReady(context) -> showVoiceDialog = true
            else -> Toast.makeText(context, "Jarvis aktif. Ucapkan \"Jarvis\" lalu perintahmu.", Toast.LENGTH_LONG).show()
        }
    }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            startJarvis()
        } else {
            Toast.makeText(context, "Jarvis butuh izin mikrofon untuk mendengar.", Toast.LENGTH_LONG).show()
        }
    }

    // Ketuk: nyalakan / matikan. Tekan lama: pengaturan Jarvis (kepekaan, suara).
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = {
                    when {
                        running -> JarvisService.stop(context)
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> startJarvis()
                        else -> permissionLauncher.launch(permissions)
                    }
                },
                onLongClick = { showSettings = true },
                onLongClickLabel = "Pengaturan Jarvis",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (running) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            contentDescription = if (running) "Matikan Jarvis" else "Nyalakan Jarvis",
            tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showVoiceDialog) {
        KokoroDialog(model, onDismiss = { showVoiceDialog = false })
    }
    if (showSettings) {
        JarvisSettingsDialog(
            model = model,
            onOpenVoice = {
                showSettings = false
                showVoiceDialog = true
            },
            onOpenTraining = {
                showSettings = false
                showTraining = true
            },
            onDismiss = { showSettings = false },
        )
    }
    if (showTraining) {
        VoiceTrainingDialog(onDismiss = {
            showTraining = false
            if (running && !KokoroModel.isReady(context)) showVoiceDialog = true
        })
    }
}

@Composable
private fun JarvisSettingsDialog(
    model: KokoroModel.State,
    onOpenVoice: () -> Unit,
    onOpenTraining: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sensitivity by JarvisSettings.sensitivity.collectAsState()
    LaunchedEffect(Unit) { JarvisSettings.load(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pengaturan Jarvis") },
        text = {
            Column {
                Text("Suara Anda", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Jarvis mengenali panggilan dari contoh rekaman suara Anda. " +
                        "Contoh tersimpan: ${VoiceSamples.count(context)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenTraining) { Text("Latih suara Jarvis") }
                Spacer(Modifier.height(8.dp))
                Text("Kepekaan panggilan \"Jarvis\"", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Kalau Jarvis sering tidak menyahut, pilih yang lebih peka. " +
                        "Kalau sering terpanggil sendiri, pilih yang kurang peka.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                JarvisSensitivity.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { JarvisSettings.setSensitivity(context, option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == sensitivity, onClick = { JarvisSettings.setSensitivity(context, option) })
                        Text(option.label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Suara Jarvis", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (model) {
                        KokoroModel.State.Ready -> "Kokoro (bm_lewis) sudah terpasang."
                        is KokoroModel.State.Downloading -> "Sedang diunduh… ${(model.progress * 100).toInt()}%"
                        else -> "Belum diunduh, sementara memakai suara Android."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model !is KokoroModel.State.Ready) {
                    TextButton(onClick = onOpenVoice) { Text("Unduh suara Jarvis") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun KokoroDialog(state: KokoroModel.State, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suara Jarvis") },
        text = {
            Column {
                when (state) {
                    KokoroModel.State.NotDownloaded -> Text(
                        "Unduh suara Jarvis (Kokoro, pria beraksen Inggris) sebesar ±${KokoroModel.DOWNLOAD_SIZE_MB} MB? " +
                            "Disarankan lewat Wi-Fi. Cukup sekali; setelah itu suara dibuat offline di HP.\n\n" +
                            "Sambil menunggu, Jarvis memakai suara bawaan Android.",
                    )
                    is KokoroModel.State.Downloading -> {
                        Text("Mengunduh suara Jarvis… ${(state.progress * 100).toInt()}%")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Boleh ditutup, unduhan tetap berjalan selama aplikasi terbuka.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    KokoroModel.State.Ready -> Text("Suara Jarvis siap dipakai.")
                    is KokoroModel.State.Failed -> Text(
                        "Unduhan gagal: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                KokoroModel.State.NotDownloaded, is KokoroModel.State.Failed -> TextButton(onClick = {
                    KokoroModel.download(context, NadaApp.instance.okHttpClient)
                }) { Text(if (state is KokoroModel.State.Failed) "Coba lagi" else "Unduh") }
                else -> TextButton(onClick = onDismiss) { Text("Tutup") }
            }
        },
        dismissButton = {
            when (state) {
                is KokoroModel.State.Downloading -> TextButton(onClick = {
                    KokoroModel.cancel()
                    onDismiss()
                }) { Text("Batalkan") }
                KokoroModel.State.Ready -> Unit
                else -> TextButton(onClick = onDismiss) { Text("Nanti") }
            }
        },
    )
}
