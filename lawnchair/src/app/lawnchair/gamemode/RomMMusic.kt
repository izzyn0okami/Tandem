package app.lawnchair.gamemode

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RomMMusicSection(val label: String) {
    Tracks("Faixas"),
    Artists("Artistas"),
    Albums("Álbuns"),
    Favorites("Favoritos"),
    Playlists("Playlists"),
}

private const val ROMM_MUSIC_PAGE_SIZE = 60
private val ROMM_ANDROID_AUDIO_EXTENSIONS = setOf("mp3", "ogg", "oga", "opus", "m4a", "aac", "wav", "flac")

@Composable
internal fun RomMMusicScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverUrl = RomMSettings.url(context).orEmpty()
    val token = RomMSettings.token(context)
    val connected = RomMSettings.isConnected(context)
    val client = remember(serverUrl, token) { if (serverUrl.isNotBlank()) RomMClient(serverUrl, token) else null }
    val playback = remember(context) { RomMMusicPlayback(context) }

    var section by remember { mutableStateOf(RomMMusicSection.Tracks) }
    var query by remember { mutableStateOf("") }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<RemoteMusicPlaylist?>(null) }
    var tracks by remember { mutableStateOf<List<RemoteMusicTrack>>(emptyList()) }
    var facets by remember { mutableStateOf<List<RemoteMusicFacet>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<RemoteMusicPlaylist>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }
    var confirmDeletePlaylist by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<RemoteMusicPlaylist?>(null) }

    var queue by remember { mutableStateOf<List<RemoteMusicTrack>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var preparing by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    val currentTrack = queue.getOrNull(currentIndex)

    fun refreshCurrent() {
        refreshTick++
    }

    fun loadMore() {
        val activeClient = client ?: return
        if (loadingMore || offset >= total) return
        scope.launch {
            loadingMore = true
            try {
                val page = withContext(Dispatchers.IO) {
                    when (section) {
                        RomMMusicSection.Tracks -> activeClient.listMusicTracks(query, selectedArtist, selectedAlbum, ROMM_MUSIC_PAGE_SIZE, offset)
                        RomMMusicSection.Favorites -> activeClient.listMusicFavorites(query, ROMM_MUSIC_PAGE_SIZE, offset)
                        RomMMusicSection.Playlists -> selectedPlaylist?.let {
                            activeClient.listMusicPlaylistTracks(it.id, ROMM_MUSIC_PAGE_SIZE, offset)
                        }
                        else -> null
                    }
                }
                if (page != null) {
                    tracks = tracks + page.items
                    offset = page.offset + page.items.size
                    total = page.total
                }
            } catch (exception: Exception) {
                error = romMUserFacingError(exception, "carregar mais faixas")
            } finally {
                loadingMore = false
            }
        }
    }

    LaunchedEffect(connected, serverUrl, token, section, query, selectedArtist, selectedAlbum, selectedPlaylist?.id, refreshTick) {
        if (!connected || client == null) {
            tracks = emptyList()
            facets = emptyList()
            playlists = emptyList()
            error = "Conecte o Tandem a um servidor RomM para acessar a biblioteca musical."
            return@LaunchedEffect
        }
        delay(250)
        loading = true
        error = ""
        try {
            when (section) {
                RomMMusicSection.Tracks -> {
                    val page = withContext(Dispatchers.IO) { client.listMusicTracks(query, selectedArtist, selectedAlbum, ROMM_MUSIC_PAGE_SIZE, 0) }
                    tracks = page.items
                    total = page.total
                    offset = page.offset + page.items.size
                    facets = emptyList()
                }
                RomMMusicSection.Favorites -> {
                    val page = withContext(Dispatchers.IO) { client.listMusicFavorites(query, ROMM_MUSIC_PAGE_SIZE, 0) }
                    tracks = page.items
                    total = page.total
                    offset = page.offset + page.items.size
                    facets = emptyList()
                }
                RomMMusicSection.Artists -> {
                    val page = withContext(Dispatchers.IO) { client.listMusicArtists(query, 250, 0) }
                    facets = page.items
                    tracks = emptyList()
                }
                RomMMusicSection.Albums -> {
                    val page = withContext(Dispatchers.IO) { client.listMusicAlbums(query, 250, 0) }
                    facets = page.items
                    tracks = emptyList()
                }
                RomMMusicSection.Playlists -> {
                    playlists = withContext(Dispatchers.IO) { client.listMusicPlaylists() }
                    val selected = selectedPlaylist?.let { current -> playlists.firstOrNull { it.id == current.id } }
                    selectedPlaylist = selected
                    if (selected != null) {
                        val page = withContext(Dispatchers.IO) { client.listMusicPlaylistTracks(selected.id, ROMM_MUSIC_PAGE_SIZE, 0) }
                        tracks = page.items
                        total = page.total
                        offset = page.offset + page.items.size
                    } else {
                        tracks = emptyList()
                        total = 0
                        offset = 0
                    }
                    facets = emptyList()
                }
            }
        } catch (exception: Exception) {
            error = romMUserFacingError(exception, "carregar a biblioteca musical")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(playing, currentTrack?.romFileId) {
        while (playing) {
            positionMs = playback.currentPosition()
            delay(500)
        }
    }

    DisposableEffect(playback) {
        onDispose { playback.release() }
    }

    fun playAt(items: List<RemoteMusicTrack>, index: Int) {
        val track = items.getOrNull(index) ?: return
        val extension = track.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in ROMM_ANDROID_AUDIO_EXTENSIONS) {
            message = "Formato .$extension não é reproduzido pelo player Android do Tandem."
            return
        }
        val activeClient = client ?: return
        queue = items
        currentIndex = index
        preparing = true
        playing = false
        positionMs = 0
        durationMs = 0
        message = ""
        try {
            val source = activeClient.musicStreamSource(track)
            playback.play(
                source.first,
                source.second,
                onPrepared = { duration ->
                    durationMs = duration
                    positionMs = 0
                    preparing = false
                    playing = true
                },
                onCompleted = {
                    if (index + 1 < items.size) playAt(items, index + 1)
                    else {
                        playing = false
                        preparing = false
                        positionMs = durationMs
                    }
                },
                onError = { reason ->
                    preparing = false
                    playing = false
                    error = reason
                },
                onFocusLost = {
                    playing = false
                    message = "Reprodução pausada porque outro aplicativo solicitou o áudio."
                },
            )
        } catch (exception: Exception) {
            preparing = false
            playing = false
            error = romMUserFacingError(exception, "reproduzir a faixa")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Jukebox do RomM", color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Música da biblioteca do servidor, independente do jogo que estiver em destaque.", color = GameColors.textDim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RomMMusicSection.entries.forEach { item ->
                PlatformChip(item.label, section == item) {
                    section = item
                    if (item != RomMMusicSection.Tracks) {
                        selectedArtist = null
                        selectedAlbum = null
                    }
                    if (item != RomMMusicSection.Playlists) selectedPlaylist = null
                }
            }
        }
        if (section != RomMMusicSection.Playlists) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Pesquisar música") },
                singleLine = true,
                modifier = Modifier.width(520.dp),
            )
        }
        if (selectedArtist != null || selectedAlbum != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selectedArtist?.let { PlatformChip("Artista: $it", true) { selectedArtist = null } }
                selectedAlbum?.let { PlatformChip("Álbum: $it", true) { selectedAlbum = null } }
            }
        }

        if (section == RomMMusicSection.Playlists) {
            if (selectedPlaylist == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it.take(400) },
                        label = { Text("Nova playlist privada") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    GameButton(
                        "Criar",
                        onClick = {
                            val activeClient = client ?: return@GameButton
                            scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) { activeClient.createMusicPlaylist(playlistName) }
                                    playlistName = ""
                                    message = "Playlist criada."
                                    refreshCurrent()
                                } catch (exception: Exception) {
                                    error = romMUserFacingError(exception, "criar playlist")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && playlistName.isNotBlank(),
                        primary = true,
                        compact = true,
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FocusSurface(
                                onClick = { selectedPlaylist = playlist },
                                modifier = Modifier.weight(1f),
                                background = GameColors.surface,
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(playlist.name, color = GameColors.text, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${playlist.trackCount} faixa(s) · ${if (playlist.isPublic) "pública" else "privada"}",
                                        color = GameColors.textDim,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            GameButton(
                                "Excluir",
                                onClick = {
                                    playlistToDelete = playlist
                                    confirmDeletePlaylist = true
                                },
                                danger = true,
                                compact = true,
                            )
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameButton("‹ Playlists", onClick = { selectedPlaylist = null }, compact = true)
                    Text(selectedPlaylist!!.name, color = GameColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameButton("Adicionar faixas", onClick = { section = RomMMusicSection.Tracks }, primary = true, compact = true)
                    GameButton("Excluir playlist", onClick = { playlistToDelete = selectedPlaylist; confirmDeletePlaylist = true }, danger = true, compact = true)
                }
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tracks, key = { it.romFileId }) { track ->
                        RomMMusicTrackRow(
                            track = track,
                            selected = currentTrack?.romFileId == track.romFileId,
                            playing = playing,
                            onPlay = { playAt(tracks, tracks.indexOf(track)) },
                            onFavorite = { toggleMusicFavorite(track, client, scope, ::refreshCurrent) { error = it } },
                            onRemove = {
                                val activeClient = client ?: return@RomMMusicTrackRow
                                scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            activeClient.removeMusicPlaylistTracks(selectedPlaylist!!.id, listOf(track.romFileId))
                                        }
                                        refreshCurrent()
                                    } catch (exception: Exception) {
                                        error = romMUserFacingError(exception, "remover faixa da playlist")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            removeLabel = "Remover",
                        )
                    }
                    if (tracks.size < total) item { GameButton("Carregar mais", onClick = { loadMore() }, enabled = !loadingMore, primary = true) }
                }
            }
        } else if (section == RomMMusicSection.Artists || section == RomMMusicSection.Albums) {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(facets, key = { it.value }) { facet ->
                    FocusSurface(
                        onClick = {
                            if (section == RomMMusicSection.Artists) selectedArtist = facet.value else selectedAlbum = facet.value
                            section = RomMMusicSection.Tracks
                        },
                        background = GameColors.surface,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(facet.value, color = GameColors.text)
                            Text("${facet.count} faixa(s)", color = GameColors.textDim, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            if (section == RomMMusicSection.Tracks && selectedPlaylist != null) {
                Text("Destino: ${selectedPlaylist!!.name}", color = GameColors.success, fontSize = 12.sp)
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tracks, key = { it.romFileId }) { track ->
                    RomMMusicTrackRow(
                        track = track,
                        selected = currentTrack?.romFileId == track.romFileId,
                        playing = playing,
                        onPlay = { playAt(tracks, tracks.indexOf(track)) },
                        onFavorite = { toggleMusicFavorite(track, client, scope, ::refreshCurrent) { error = it } },
                        onAdd = if (section == RomMMusicSection.Tracks && selectedPlaylist != null) {
                            {
                                val activeClient = client
                                val target = selectedPlaylist
                                if (activeClient != null && target != null) scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) { activeClient.addMusicPlaylistTracks(target.id, listOf(track.romFileId)) }
                                        message = "Faixa adicionada a ${target.name}."
                                    } catch (exception: Exception) {
                                        error = romMUserFacingError(exception, "adicionar faixa à playlist")
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        } else null,
                    )
                }
                if (tracks.size < total) item { GameButton("Carregar mais", onClick = { loadMore() }, enabled = !loadingMore, primary = true) }
            }
        }

        if (loading || loadingMore) Text(if (loadingMore) "Carregando mais faixas…" else "Carregando música…", color = GameColors.textDim, fontSize = 12.sp)
        if (error.isNotBlank()) Text(error, color = GameColors.danger, fontSize = 12.sp, maxLines = 3)
        if (message.isNotBlank()) Text(message, color = GameColors.textDim, fontSize = 12.sp)

        currentTrack?.let { track ->
            Column(
                modifier = Modifier.fillMaxWidth().background(GameColors.backgroundTop, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(track.title ?: track.fileName, color = GameColors.text, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(track.artist, track.album, track.gameName).joinToString(" · "), color = GameColors.textDim, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameButton("Anterior", onClick = { if (currentIndex > 0) playAt(queue, currentIndex - 1) }, enabled = currentIndex > 0, compact = true)
                    GameButton(
                        if (playing) "Pausar" else "Reproduzir",
                        onClick = {
                            if (playing) {
                                playback.pause()
                                playing = false
                            } else if (!preparing && playback.hasPlayer()) {
                                playback.resume()
                                playing = playback.isPlaying()
                            } else if (!preparing) {
                                playAt(queue, currentIndex)
                            }
                        },
                        primary = true,
                        enabled = !preparing,
                        compact = true,
                    )
                    GameButton("Próxima", onClick = { if (currentIndex + 1 < queue.size) playAt(queue, currentIndex + 1) }, enabled = currentIndex + 1 < queue.size, compact = true)
                    if (preparing) Text("Conectando ao áudio…", color = GameColors.textDim, fontSize = 12.sp)
                }
                if (durationMs > 0) {
                    Slider(
                        value = positionMs.coerceIn(0, durationMs).toFloat(),
                        onValueChange = { positionMs = it.toInt() },
                        onValueChangeFinished = { playback.seekTo(positionMs) },
                        valueRange = 0f..durationMs.toFloat(),
                    )
                    Text("${formatMusicTime(positionMs)} / ${formatMusicTime(durationMs)}", color = GameColors.textDim, fontSize = 11.sp)
                }
            }
        }
    }

    if (confirmDeletePlaylist) {
        val target = playlistToDelete ?: selectedPlaylist
        AlertDialog(
            onDismissRequest = { confirmDeletePlaylist = false; playlistToDelete = null },
            title = { Text("Excluir playlist?") },
            text = { Text("A playlist \"${target?.name.orEmpty()}\" será removida do RomM. As faixas originais não serão apagadas.") },
            confirmButton = {
                GameButton("Excluir", onClick = {
                    val activeClient = client
                    if (activeClient != null && target != null) scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { activeClient.deleteMusicPlaylist(target.id) }
                            if (selectedPlaylist?.id == target.id) selectedPlaylist = null
                            playlistToDelete = null
                            confirmDeletePlaylist = false
                            message = "Playlist excluída."
                            refreshCurrent()
                        } catch (exception: Exception) {
                            error = romMUserFacingError(exception, "excluir playlist")
                        } finally {
                            busy = false
                        }
                    }
                }, danger = true, enabled = !busy, compact = true)
            },
            dismissButton = { GameButton("Cancelar", onClick = { confirmDeletePlaylist = false; playlistToDelete = null }, compact = true) },
        )
    }
}

@Composable
private fun RomMMusicTrackRow(
    track: RemoteMusicTrack,
    selected: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAdd: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remover",
) {
    val extension = track.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val supported = extension in ROMM_ANDROID_AUDIO_EXTENSIONS
    Column(
        modifier = Modifier.fillMaxWidth().background(if (selected) GameColors.surfaceHigh else GameColors.surface, RoundedCornerShape(10.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusSurface(
                onClick = onPlay,
                enabled = supported,
                modifier = Modifier.weight(1f),
                background = GameColors.background.copy(alpha = 0.25f),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        (if (selected && playing) "♫ " else "") + (track.title ?: track.fileName),
                        color = if (supported) GameColors.text else GameColors.textDim,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        listOfNotNull(track.artist, track.album, track.gameName ?: track.platformName).joinToString(" · "),
                        color = GameColors.textDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            GameButton(if (track.isFavorite) "★" else "☆", onFavorite, compact = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!supported) Text("Formato .$extension não compatível com o player Android.", color = GameColors.textDim, fontSize = 11.sp)
            onAdd?.let { GameButton("Adicionar à playlist", it, compact = true) }
            onRemove?.let { GameButton(removeLabel, it, danger = true, compact = true) }
        }
    }
}

private fun toggleMusicFavorite(
    track: RemoteMusicTrack,
    client: RomMClient?,
    scope: kotlinx.coroutines.CoroutineScope,
    refresh: () -> Unit,
    onError: (String) -> Unit,
) {
    val activeClient = client ?: return
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                if (track.isFavorite) activeClient.removeMusicFavorites(listOf(track.romFileId))
                else activeClient.addMusicFavorites(listOf(track.romFileId))
            }
            refresh()
        } catch (exception: Exception) {
            onError(romMUserFacingError(exception, "alterar favorito musical"))
        }
    }
}

private fun formatMusicTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Player HTTP nativo: o stream é reproduzido diretamente, sem baixar a faixa inteira. */
private class RomMMusicPlayback(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun play(
        url: String,
        headers: Map<String, String>,
        onPrepared: (Int) -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit,
        onFocusLost: () -> Unit,
    ) {
        release()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    runCatching { player?.pause() }
                    onFocusLost()
                }
            }
            .setWillPauseWhenDucked(true)
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw IOException("O Android não concedeu foco de áudio para o player.")
        }
        focusRequest = request
        val created = MediaPlayer()
        player = created
        try {
            created.setAudioAttributes(attributes)
            created.setOnPreparedListener { prepared ->
                runCatching { prepared.start() }
                    .onSuccess { onPrepared(prepared.duration.coerceAtLeast(0)) }
                    .onFailure { release(); onError(it.message ?: "Não foi possível iniciar o áudio.") }
            }
            created.setOnCompletionListener {
                release()
                onCompleted()
            }
            created.setOnErrorListener { _, what, extra ->
                release()
                onError("Falha na reprodução de áudio (código $what/$extra).")
                true
            }
            created.setDataSource(url, headers)
            created.prepareAsync()
        } catch (exception: Exception) {
            release()
            throw exception
        }
    }

    fun pause() { runCatching { player?.pause() } }
    fun resume() { runCatching { player?.start() } }
    fun seekTo(positionMs: Int) { runCatching { player?.seekTo(positionMs.coerceAtLeast(0)) } }
    fun currentPosition(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)
    fun hasPlayer(): Boolean = player != null

    fun release() {
        val old = player
        player = null
        runCatching { old?.release() }
        focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }
}
