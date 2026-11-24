package com.nada.music.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nada.music.data.Song
import com.nada.music.data.formatDuration
import com.nada.music.data.importer.SpotifyTrack
import com.nada.music.ui.components.Artwork
import com.nada.music.ui.components.SectionTitle

/** Satu baris tangga lagu. [song] terisi setelah lagunya ketemu di YouTube. */
data class ChartEntry(
    val rank: Int,
    val track: SpotifyTrack,
    val song: Song? = null,
    /** true kalau pencarian di YouTube sudah selesai (berhasil atau tidak). */
    val searched: Boolean = false,
)

sealed interface TrendingState {
    data object Loading : TrendingState
    data class Failed(val message: String) : TrendingState
    data class Ready(val entries: List<ChartEntry>) : TrendingState
}

/** Jumlah baris yang tampil sebelum "Lihat semua" diketuk. */
private const val COLLAPSED_ROWS = 10

/**
 * Tabel "Trending di Indonesia": peringkat, cover, judul, artis, dan durasi.
 * Lagu bisa langsung diketuk walaupun belum selesai dicocokkan ke YouTube.
 */
fun LazyListScope.trendingSection(
    state: TrendingState,
    expanded: Boolean,
    currentSongId: String?,
    onToggleExpanded: () -> Unit,
    onPlay: (rank: Int) -> Unit,
    onPlayAll: () -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "trending-title") {
        SectionTitle("Trending di Indonesia") {
            if (state is TrendingState.Ready) TextButton(onClick = onPlayAll) { Text("Putar semua") }
        }
        Text(
            "Top 50 Indonesia dari Spotify, diperbarui setiap hari",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
    }
    when (state) {
        TrendingState.Loading -> item(key = "trending-loading") {
            CircularProgressIndicator(Modifier.padding(16.dp))
        }
        is TrendingState.Failed -> item(key = "trending-error") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("Coba lagi") }
            }
        }
        is TrendingState.Ready -> {
            val shown = if (expanded) state.entries else state.entries.take(COLLAPSED_ROWS)
            items(shown, key = { "trending-${it.rank}" }) { entry ->
                ChartRow(entry, isCurrent = entry.song?.id != null && entry.song.id == currentSongId) {
                    onPlay(entry.rank)
                }
            }
            if (state.entries.size > COLLAPSED_ROWS) {
                item(key = "trending-toggle") {
                    TextButton(onClick = onToggleExpanded, modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (expanded) "Tampilkan lebih sedikit" else "Lihat semua ${state.entries.size} lagu")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(entry: ChartEntry, isCurrent: Boolean, onClick: () -> Unit) {
    val notFound = entry.searched && entry.song == null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !notFound, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.rank.toString(),
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            fontSize = if (entry.rank <= 3) 20.sp else 16.sp,
            color = if (entry.rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Artwork(entry.song?.thumbnailUrl, size = 48.dp)
            if (isCurrent) Icon(Icons.Rounded.GraphicEq, "Sedang diputar", tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (notFound) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (notFound) "${entry.track.artist} · tidak ditemukan di YouTube" else entry.track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(entry.track.durationSec.takeIf { it > 0 } ?: -1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
