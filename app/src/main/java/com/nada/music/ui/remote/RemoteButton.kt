package com.nada.music.ui.remote

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nada.music.remote.RemoteService
import kotlinx.coroutines.delay

/** Tombol di beranda untuk menyalakan "Remote PC" (atur musik dari browser komputer). */
@Composable
fun RemoteButton() {
    val info by RemoteService.info.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }) {
        Icon(
            Icons.Rounded.Computer,
            contentDescription = "Remote PC",
            tint = if (info != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) RemoteDialog(info, onDismiss = { showDialog = false })
}

@Composable
private fun RemoteDialog(info: RemoteService.Info?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var addresses by remember { mutableStateOf(RemoteService.addresses()) }
    var pin by remember { mutableStateOf(RemoteService.pin(context)) }

    // Alamat IP bisa berubah (pindah Wi-Fi, hotspot dinyalakan), jadi dicek ulang berkala.
    LaunchedEffect(Unit) {
        while (true) {
            addresses = RemoteService.addresses()
            delay(2000)
        }
    }

    // Izin notifikasi (Android 13+) supaya notifikasi "Remote PC aktif" terlihat; tetap jalan walau ditolak.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        RemoteService.start(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote PC") },
        text = {
            Column {
                if (info == null) {
                    Text(
                        "Atur musik dari browser di PC / laptop: pilih lagu, ganti lagu, jeda, atur volume.\n\n" +
                            "HP dan PC harus tersambung ke Wi-Fi yang sama, atau PC tersambung ke hotspot HP ini.",
                    )
                } else {
                    Text("Buka alamat ini di browser PC:")
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Column {
                            if (addresses.isEmpty()) {
                                Text(
                                    "HP belum tersambung ke Wi-Fi. Sambungkan ke Wi-Fi atau nyalakan hotspot.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            addresses.forEach {
                                Text(
                                    "http://$it:${info.port}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("PIN", style = MaterialTheme.typography.labelMedium)
                    Text(pin, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Selama aktif, HP tidak tidur penuh supaya tetap bisa diakses, jadi baterai lebih cepat habis. " +
                            "Matikan kalau sudah selesai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        // PIN baru berlaku setelah remote dinyalakan ulang.
                        pin = RemoteService.resetPin(context)
                        RemoteService.stop(context)
                        RemoteService.start(context)
                    }) { Text("Buat PIN baru") }
                }
            }
        },
        confirmButton = {
            if (info == null) {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        RemoteService.start(context)
                    }
                }) { Text("Nyalakan") }
            } else {
                TextButton(onClick = {
                    RemoteService.stop(context)
                    onDismiss()
                }) { Text("Matikan") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
