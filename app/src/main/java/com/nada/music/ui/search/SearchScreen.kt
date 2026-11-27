package com.nada.music.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nada.music.NadaApp
import com.nada.music.data.MusicRepository
import com.nada.music.data.Song
import com.nada.music.data.importer.PlaylistLink
import com.nada.music.data.importer.VideoLink
import com.nada.music.data.youtube.YouTubeMusic
import com.nada.music.ui.SharedLink
import com.nada.music.ui.LocalPlayer
import com.nada.music.ui.components.EmptyState
import com.nada.music.ui.components.SongRow
import com.nada.music.ui.components.friendlyError
import com.nada.music.ui.glass.glass
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Tempat mencari: katalog YouTube Music (default) atau YouTube biasa. */
enum class SearchSource { MUSIC, YOUTUBE }

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Error(val message: String) : SearchState
    data class Results(
        val query: String,
        val songs: List<Song>,
        val filter: String,
        val nextPage: Page?,
        val loadingMore: Boolean = false,
        /** Hasil dari link yang ditempel / dibagikan, bukan dari pencarian. */
        val fromLink: Boolean = false,
        /** Lagu pertama langsung diputar (link dibagikan dari aplikasi lain). */
        val autoPlay: Boolean = false,
    ) : SearchState {
        /** YouTube Music tidak punya hasil, jadi yang tampil hasil dari YouTube biasa. */
        val fellBackToYouTube get() = !fromLink && filter == YouTubeMusic.YOUTUBE_FILTER
    }
}

@OptIn(FlowPreview::class)
class SearchViewModel(private val repository: MusicRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
    val recentQueries: StateFlow<List<String>> = _recentQueries.asStateFlow()

    /** true saat pengguna sedang mengetik (tampilkan saran, bukan hasil). */
    private val _source = MutableStateFlow(SearchSource.MUSIC)
    val source: StateFlow<SearchSource> = _source.asStateFlow()

    private val _editing = MutableStateFlow(true)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _query.debounce(300)
                .distinctUntilChanged()
                .filter { _editing.value }
                .collect { q ->
                    _suggestions.value = if (q.isBlank()) emptyList() else repository.suggestions(q)
                }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
        _editing.value = true
    }

    fun setSource(value: SearchSource) {
        if (_source.value == value) return
        _source.value = value
        val current = _state.value
        if (current is SearchState.Results && !current.fromLink) search(current.query)
    }

    /**
     * @param autoPlay true untuk link yang dibagikan dari aplikasi lain: lagunya langsung diputar.
     */
    fun search(q: String = _query.value, autoPlay: Boolean = false) {
        val text = q.trim()
        if (text.isEmpty()) return
        _query.value = text
        _editing.value = false
        val isLink = VideoLink.isYouTubeLink(text) || PlaylistLink.parse(text) != null
        // Link tidak masuk riwayat pencarian, karena isinya panjang dan jarang dicari ulang.
        if (!isLink) {
            _recentQueries.update { (listOf(text) + it.filter { old -> !old.equals(text, true) }).take(10) }
        }
        val source = _source.value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = SearchState.Loading
            _state.value = runCatching {
                val linkSongs = repository.songsFromLink(text)
                if (linkSongs != null) {
                    SearchState.Results(text, linkSongs, LINK_FILTER, null, fromLink = true, autoPlay = autoPlay)
                } else {
                    val result = when (source) {
                        SearchSource.MUSIC -> repository.search(text)
                        SearchSource.YOUTUBE -> repository.searchYouTube(text)
                    }
                    SearchState.Results(text, result.songs, result.filter, result.nextPage)
                }
            }.getOrElse {
                SearchState.Error(if (it is MusicRepository.LinkError) it.message.orEmpty() else friendlyError(it))
            }
        }
    }

    fun autoPlayHandled() {
        _state.update { if (it is SearchState.Results) it.copy(autoPlay = false) else it }
    }

    fun loadMore() {
        val current = _state.value as? SearchState.Results ?: return
        val page = current.nextPage ?: return
        if (current.loadingMore) return
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            val more = runCatching { repository.searchMore(current.query, current.filter, page) }.getOrNull()
            val latest = _state.value as? SearchState.Results ?: return@launch
            if (latest.query != current.query) return@launch
            _state.value = if (more == null) {
                latest.copy(loadingMore = false, nextPage = null)
            } else {
                val known = latest.songs.map { it.id }.toSet()
                latest.copy(
                    songs = latest.songs + more.songs.filter { it.id !in known },
                    nextPage = more.nextPage,
                    loadingMore = false,
                )
            }
        }
    }

    fun clear() {
        _query.value = ""
        _editing.value = true
        _suggestions.value = emptyList()
        _state.value = SearchState.Idle
    }

    private companion object {
        const val LINK_FILTER = "link"
    }
}

@Composable
fun SearchScreen(contentPadding: PaddingValues) {
    val vm: SearchViewModel = viewModel { SearchViewModel(NadaApp.instance.repository) }
    val player = LocalPlayer.current
    val query by vm.query.collectAsState()
    val state by vm.state.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val recent by vm.recentQueries.collectAsState()
    val editing by vm.editing.collectAsState()
    val source by vm.source.collectAsState()
    val currentSong by player.currentSong.collectAsState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (state is SearchState.Idle && SharedLink.pending.value == null) focusRequester.requestFocus()
    }

    // Link yang dibagikan dari aplikasi lain (YouTube -> Bagikan -> Nautify).
    LaunchedEffect(Unit) {
        SharedLink.pending.collect { text ->
            if (text != null) {
                SharedLink.consume()
                focusManager.clearFocus()
                vm.search(text, autoPlay = true)
            }
        }
    }

    LaunchedEffect(state) {
        val s = state
        if (s is SearchState.Results && s.autoPlay) {
            vm.autoPlayHandled()
            if (s.songs.isNotEmpty()) player.playSongs(s.songs, 0)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .glass(shape = RoundedCornerShape(28.dp))
                .focusRequester(focusRequester),
            placeholder = { Text("Cari lagu, artis, atau tempel link YouTube") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = vm::clear) { Icon(Icons.Rounded.Close, "Hapus") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                vm.search()
                focusManager.clearFocus()
            }),
        )

        SourceChips(selected = source, onSelect = { vm.setSource(it) })

        val showSuggestions = editing && (state !is SearchState.Results || query.isNotBlank()) &&
            (suggestions.isNotEmpty() || (query.isBlank() && recent.isNotEmpty()))

        if (showSuggestions) {
            val items = if (query.isBlank()) recent else suggestions
            LazyColumn(contentPadding = contentPadding) {
                items(items) { text ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.search(text)
                                focusManager.clearFocus()
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (query.isBlank()) Icons.Rounded.History else Icons.AutoMirrored.Rounded.TrendingUp,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            return@Column
        }

        when (val s = state) {
            SearchState.Idle -> EmptyState(
                icon = Icons.Rounded.Search,
                title = "Cari musik",
                message = "Ketik judul lagu atau nama artis, atau tempel link video / playlist YouTube.",
                modifier = Modifier.padding(top = 48.dp),
            )

            SearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is SearchState.Error -> EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = "Pencarian gagal",
                message = s.message,
                modifier = Modifier.padding(top = 48.dp),
                action = { Button(onClick = { vm.search() }) { Text("Coba lagi") } },
            )

            is SearchState.Results -> {
                if (s.songs.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.MusicOff,
                        title = "Tidak ditemukan",
                        message = "Tidak ada hasil untuk \"${s.query}\".",
                        modifier = Modifier.padding(top = 48.dp),
                        action = if (source == SearchSource.MUSIC && !s.fromLink) {
                            { Button(onClick = { vm.setSource(SearchSource.YOUTUBE) }) { Text("Cari di YouTube") } }
                        } else {
                            null
                        },
                    )
                } else {
                    if (s.fellBackToYouTube && source == SearchSource.MUSIC) {
                        Text(
                            "Tidak ada di YouTube Music, ini hasil dari YouTube.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    ResultsList(
                        results = s,
                        currentSongId = currentSong?.id,
                        contentPadding = contentPadding,
                        onPlay = { index -> player.playSongs(s.songs, index) },
                        onLoadMore = vm::loadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsList(
    results: SearchState.Results,
    currentSongId: String?,
    contentPadding: PaddingValues,
    onPlay: (Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(shouldLoadMore, results.songs.size) {
        if (shouldLoadMore && results.nextPage != null) onLoadMore()
    }

    LazyColumn(state = listState, contentPadding = contentPadding) {
        itemsIndexed(results.songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song = song, isCurrent = song.id == currentSongId, onClick = { onPlay(index) })
        }
        if (results.loadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun SourceChips(selected: SearchSource, onSelect: (SearchSource) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == SearchSource.MUSIC,
            onClick = { onSelect(SearchSource.MUSIC) },
            label = { Text("YouTube Music") },
        )
        FilterChip(
            selected = selected == SearchSource.YOUTUBE,
            onClick = { onSelect(SearchSource.YOUTUBE) },
            label = { Text("YouTube") },
        )
    }
}
