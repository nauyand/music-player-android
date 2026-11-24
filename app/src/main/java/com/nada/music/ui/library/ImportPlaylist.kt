package com.nada.music.ui.library

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nada.music.data.MusicRepository.ImportProgress
import com.nada.music.data.importer.PlaylistLink
import com.nada.music.ui.LocalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Dialog "Impor playlist": tempel link playlist YouTube / YouTube Music / Spotify,
 * lalu playlist itu disalin menjadi playlist lokal baru.
 */
@Composable
fun ImportPlaylistDialog(onDismiss: () -> Unit, onImported: (playlistId: Long) -> Unit) {
    val context = LocalContext.current
    val repository = LocalRepository.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Kalau di clipboard sudah ada link playlist, langsung isi.
    var text by remember {
        mutableStateOf(clipboard.getText()?.text?.takeIf { PlaylistLink.parse(it) != null }.orEmpty())
    }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    val link = PlaylistLink.parse(text)
    val running = progress != null

    fun start(link: PlaylistLink) {
        progress = ImportProgress("Memulai…")
        scope.launch {
            try {
                val result = repository.importPlaylist(link) { progress = it }
                val message = buildString {
                    append("${result.added} lagu diimpor ke \"${result.name}\"")
                    if (result.notFound.isNotEmpty()) append(". ${result.notFound.size} lagu tidak ketemu di YouTube")
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                onImported(result.playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null
                Toast.makeText(context, "Gagal mengimpor: ${e.message ?: "kesalahan jaringan"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !running, dismissOnBackPress = !running),
        title = { Text("Impor playlist") },
        text = {
            Column {
                val current = progress
                if (current == null) {
                    Text(
                        "Tempel link playlist atau mix dari YouTube, YouTube Music, atau Spotify.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Link playlist") },
                        singleLine = true,
                        isError = text.isNotBlank() && link == null,
                        supportingText = {
                            Text(
                                when {
                                    text.isBlank() -> ""
                                    link is PlaylistLink.Spotify -> "Spotify: lagu akan dicari satu per satu di YouTube"
                                    link is PlaylistLink.YouTube && PlaylistLink.isEndlessMix(link.listId) ->
                                        "Mix YouTube: diambil 100 lagu pertama"
                                    link is PlaylistLink.YouTube -> "Playlist YouTube"
                                    else -> "Link tidak dikenali"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(current.message)
                    Spacer(Modifier.height(12.dp))
                    if (current.total > 0) {
                        LinearProgressIndicator(
                            progress = { current.done.toFloat() / current.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${current.done} / ${current.total} lagu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(onClick = { link?.let(::start) }, enabled = link != null) { Text("Impor") }
            }
        },
        dismissButton = {
            if (!running) TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}
