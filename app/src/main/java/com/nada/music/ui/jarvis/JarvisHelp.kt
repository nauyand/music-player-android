package com.nada.music.ui.jarvis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Satu kelompok perintah: apa yang dilakukan, contoh ucapan, dan jawaban Jarvis. */
private data class CommandInfo(val title: String, val examples: String, val reply: String)

/** Harus sama dengan yang dipahami `jarvis/VoiceCommand.kt`. */
private val COMMANDS = listOf(
    CommandInfo("Jeda lagu", "\"stop\", \"jeda\", \"pause\", \"berhenti\", \"matikan musik\"", "Paused, sir."),
    CommandInfo("Lanjut putar", "\"lanjut\", \"lanjutkan\", \"play\", \"putar lagu\"", "Right away, sir."),
    CommandInfo("Lagu berikutnya", "\"skip\", \"next\", \"lagu berikutnya\", \"ganti lagu\", \"lewati\"", "Next song, sir."),
    CommandInfo("Lagu sebelumnya", "\"lagu sebelumnya\", \"previous\", \"kembali\", \"balik\"", "Going back, sir."),
    CommandInfo("Volume naik", "\"volume naik\", \"keraskan\", \"besarkan suara\", \"louder\"", "Volume up, sir."),
    CommandInfo("Volume turun", "\"volume turun\", \"kecilkan\", \"pelankan\", \"quieter\"", "Volume down, sir."),
    CommandInfo("Atur volume", "\"volume 50\", \"volume ke 80 persen\"", "Volume set to 50 percent, sir."),
    CommandInfo(
        "Cari & putar lagu",
        "\"putar <judul / artis>\", \"mainkan …\", \"play …\", \"cari lagu …\"\n" +
            "Contoh: \"putar Sial Mahalini\", \"play Bohemian Rhapsody\"",
        "Playing …, by …, sir.",
    ),
    CommandInfo("Matikan Jarvis", "\"matikan Jarvis\", \"tidur\", \"go to sleep\"", "Going offline, sir."),
)

/** Tombol ⓘ di sebelah mikrofon: daftar perintah Jarvis. */
@Composable
fun JarvisHelpButton() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Info, contentDescription = "Perintah Jarvis", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) JarvisHelpDialog(onDismiss = { open = false })
}

@Composable
private fun JarvisHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perintah Jarvis") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "1. Ucapkan \"Jarvis\" (atau \"Hey Jarvis\").\n" +
                        "2. Tunggu jawaban \"Yes, sir?\".\n" +
                        "3. Ucapkan salah satu perintah di bawah.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                COMMANDS.forEach { c ->
                    Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(c.examples, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Jarvis: \"${c.reply}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text("Tips", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "• Ketuk 🎤 untuk menyalakan / mematikan Jarvis.\n" +
                        "• Tekan lama 🎤 untuk melatih suara dan mengatur kepekaan.\n" +
                        "• Selama telepon, Jarvis otomatis berhenti mendengarkan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
