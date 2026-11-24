package com.nada.music.ui.home

import androidx.compose.ui.res.stringResource
import com.nada.music.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nada.music.NadaApp
import com.nada.music.data.MusicRepository
import com.nada.music.data.Song
import com.nada.music.ui.LocalPlayer
import com.nada.music.ui.components.Artwork
import com.nada.music.ui.components.EmptyState
import com.nada.music.ui.components.SectionTitle
import com.nada.music.ui.components.SongRow
import com.nada.music.ui.components.friendlyError
import com.nada.music.ui.jarvis.JarvisButton
import com.nada.music.ui.jarvis.JarvisHelpButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

sealed interface Recommendations {
    data object Loading : Recommendations
    data class Ready(val basedOn: Song?, val songs: List<Song>) : Recommendations
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val repository: MusicRepository) : ViewModel() {
    val history: StateFlow<List<Song>?> = repository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mostPlayed: StateFlow<List<Song>> = repository.mostPlayed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Rekomendasi = lagu-lagu terkait dari lagu yang terakhir diputar. */
    val recommendations: StateFlow<Recommendations> = repository.history
        .map { history -> history.firstOrNull { !com.nada.music.data.local.LocalMusic.isLocal(it.id) } }
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        .mapLatest { seed ->
            if (seed == null) return@mapLatest Recommendations.Ready(null, emptyList())
            val songs = runCatching { repository.related(seed.id) }.getOrDefault(emptyList())
            Recommendations.Ready(seed, songs)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Recommendations.Loading)

    private val _trending = MutableStateFlow<TrendingState>(TrendingState.Loading)

    /** Tangga lagu Top 50 Indonesia. Setiap lagu dicari di YouTube di latar belakang. */
    val trending: StateFlow<TrendingState> = _trending

    private var trendingJob: Job? = null

    init {
        loadTrending()
    }

    fun loadTrending(forceRefresh: Boolean = false) {
        trendingJob?.cancel()
        trendingJob = viewModelScope.launch {
            _trending.value = TrendingState.Loading
            val tracks = try {
                repository.topSongsIndonesia(forceRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _trending.value = TrendingState.Failed("Tangga lagu tidak bisa dimuat. ${friendlyError(e)}")
                return@launch
            }
            _trending.value = TrendingState.Ready(tracks.mapIndexed { i, t -> ChartEntry(rank = i + 1, track = t) })
            // Cari dari peringkat teratas dulu, beberapa sekaligus.
            val gate = Semaphore(MusicRepository.SEARCH_PARALLELISM)
            tracks.indices.map { i -> launch { gate.withPermit { resolve(i + 1) } } }.joinAll()
        }
    }

    /** Memastikan lagu di peringkat [rank] sudah dicari di YouTube. */
    private suspend fun resolve(rank: Int): Song? {
        val entry = (trending.value as? TrendingState.Ready)?.entries?.getOrNull(rank - 1) ?: return null
        if (entry.searched) return entry.song
        val song = repository.findOnYouTube(entry.track)
        _trending.update { state ->
            if (state !is TrendingState.Ready) return@update state
            state.copy(
                entries = state.entries.map { if (it.rank == rank) it.copy(song = song, searched = true) else it },
            )
        }
        return song
    }

    /**
     * Memutar tangga lagu mulai dari [rank]. Kalau lagu itu belum selesai dicari, dicari dulu.
     * Antrean berisi semua lagu yang sudah ketemu, sesuai urutan peringkat.
     */
    fun playFrom(rank: Int, play: (songs: List<Song>, startIndex: Int) -> Unit) {
        viewModelScope.launch {
            val target = resolve(rank)
            val ready = (trending.value as? TrendingState.Ready)?.entries ?: return@launch
            val songs = ready.mapNotNull { it.song }.distinctBy { it.id }
            if (songs.isEmpty()) return@launch
            // Kalau lagu yang diketuk tidak ketemu (misalnya "Putar semua" dan #1 tidak ada), mulai dari yang pertama.
            play(songs, target?.let { t -> songs.indexOfFirst { it.id == t.id } }?.coerceAtLeast(0) ?: 0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSearch: () -> Unit, contentPadding: PaddingValues) {
    val vm: HomeViewModel = viewModel { HomeViewModel(NadaApp.instance.repository) }
    val player = LocalPlayer.current
    val history by vm.history.collectAsState()
    val mostPlayed by vm.mostPlayed.collectAsState()
    val recommendations by vm.recommendations.collectAsState()
    val currentSong by player.currentSong.collectAsState()
    val trending by vm.trending.collectAsState()
    var trendingExpanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = com.nada.music.ui.glass.transparentTopBarColors(),
            title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
            actions = {
                JarvisHelpButton()
                JarvisButton()
            },
        )

        val list = history ?: return@Column

        LazyColumn(contentPadding = contentPadding) {
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = "Selamat datang di Nautify",
                        message = "Cari lagu favoritmu dan mulai dengarkan. Riwayat dan rekomendasi akan muncul di sini.",
                        modifier = Modifier.padding(top = 24.dp),
                        action = { Button(onClick = onOpenSearch) { Text("Cari lagu") } },
                    )
                }
            }

            if (list.isNotEmpty()) {
                item { SectionTitle("Terakhir diputar") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        itemsIndexed(list.take(20), key = { _, s -> s.id }) { index, song ->
                            SongCard(song) { player.playSongs(list.take(20), index) }
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }

            trendingSection(
                state = trending,
                expanded = trendingExpanded,
                currentSongId = currentSong?.id,
                onToggleExpanded = { trendingExpanded = !trendingExpanded },
                onPlay = { rank -> vm.playFrom(rank) { songs, index -> player.playSongs(songs, index) } },
                onPlayAll = { vm.playFrom(1) { songs, _ -> player.playSongs(songs, 0) } },
                onRetry = { vm.loadTrending(forceRefresh = true) },
            )

            if (mostPlayed.size >= 3) {
                item {
                    SectionTitle("Paling sering diputar") {
                        TextButton(onClick = { player.playSongs(mostPlayed, shuffle = true) }) { Text("Acak") }
                    }
                }
                itemsIndexed(mostPlayed.take(5), key = { _, s -> "top-" + s.id }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = song.id == currentSong?.id,
                        onClick = { player.playSongs(mostPlayed, index) },
                    )
                }
            }

            when (val rec = recommendations) {
                Recommendations.Loading -> item {
                    SectionTitle("Rekomendasi untukmu")
                    CircularProgressIndicator(Modifier.padding(16.dp))
                }
                is Recommendations.Ready -> if (rec.songs.isNotEmpty()) {
                    item {
                        SectionTitle("Karena kamu mendengarkan ${rec.basedOn?.title.orEmpty()}")
                    }
                    itemsIndexed(rec.songs, key = { _, s -> "rec-" + s.id }) { index, song ->
                        SongRow(
                            song = song,
                            isCurrent = song.id == currentSong?.id,
                            onClick = { player.playSongs(rec.songs, index) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        Artwork(song.thumbnailUrl, Modifier.fillMaxWidth().height(140.dp), size = null, corner = 12.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

