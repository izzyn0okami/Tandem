package app.lawnchair.gamemode

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.android.launcher3.R
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CARD_WIDTH = 96.dp
private val CARD_HEIGHT = 128.dp
private val CARD_GAP = 10.dp

@Composable
internal fun LibraryScreen(
    games: List<LocalGame>,
    entries: List<LibraryEntry>,
    androidGames: List<AndroidGame>,
    progress: Map<Long, Long>,
    version: Int,
    syncVersion: Int,
    busy: Boolean,
    onPlay: (LocalGame) -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onLaunchAndroid: (AndroidGame) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val chipFocus = remember { FocusRequester() }
    var searchText by remember { mutableStateOf("") }
    val normalizedSearch = searchText.trim()
    var platformFilter by remember { mutableStateOf<String?>(null) }
    var remotePlatforms by remember { mutableStateOf<List<RemotePlatform>>(emptyList()) }
    var remoteCollections by remember { mutableStateOf<List<RemoteCollection>>(emptyList()) }
    var collectionFilter by remember { mutableStateOf<RemoteCollection?>(null) }
    var remoteFilters by remember { mutableStateOf(RemoteGameFilters()) }
    var remoteCollectionError by remember { mutableStateOf("") }
    var remoteSearchIds by remember { mutableStateOf<List<Long>?>(null) }
    var remoteSearching by remember { mutableStateOf(false) }
    var remoteSearchError by remember { mutableStateOf("") }
    var remoteSearchOffset by remember { mutableIntStateOf(0) }
    var remoteSearchHasMore by remember { mutableStateOf(false) }
    var remoteSearchLoadingMore by remember { mutableStateOf(false) }
    var activeRemoteSearchKey by remember { mutableStateOf("") }
    var displayLimit by remember { mutableIntStateOf(100) }
    var sortBy by remember { mutableStateOf("") }
    var sortDescending by remember { mutableStateOf(false) }
    val sortOptions = remember {
        listOf(
            "" to "Relevância",
            "name" to "Nome",
            "fs_size_bytes" to "Tamanho",
            "first_release_date" to "Lançamento",
            "created_at" to "Adicionado",
            "average_rating" to "Avaliação",
        )
    }
    val currentRemoteSearchKey = listOf(
        normalizedSearch,
        platformFilter.orEmpty(),
        collectionFilter?.kind.orEmpty(),
        collectionFilter?.id.orEmpty(),
        remoteFilters.hashCode(),
        remotePlatforms.hashCode(),
        sortBy,
        sortDescending,
        RomMSettings.url(context).orEmpty(),
        RomMSettings.token(context)?.hashCode() ?: 0,
    ).joinToString("|")
    LaunchedEffect(RomMSettings.url(context), RomMSettings.token(context), RomMSettings.isConnected(context)) {
        remotePlatforms = emptyList()
        remoteCollections = emptyList()
        collectionFilter = null
        remoteCollectionError = ""
        if (RomMSettings.isConnected(context)) {
            runCatching {
                withContext(Dispatchers.IO) {
                    RomMClient(
                        RomMSettings.url(context).orEmpty(),
                        RomMSettings.token(context),
                    ).listPlatforms()
                }
            }.onSuccess { remotePlatforms = it }
                .onFailure { GameLog.log("romm", "falha ao carregar plataformas do RomM: ${it.message}") }
            runCatching {
                withContext(Dispatchers.IO) {
                    RomMClient(
                        RomMSettings.url(context).orEmpty(),
                        RomMSettings.token(context),
                    ).listCollections()
                }
            }.onSuccess { remoteCollections = it }
                .onFailure {
                    remoteCollectionError = it.message ?: "Não foi possível consultar as coleções."
                    GameLog.log("romm", "falha ao carregar coleções do RomM: ${it.message}")
                }
        } else {
            remotePlatforms = emptyList()
            remoteCollections = emptyList()
            collectionFilter = null
        }
    }
    LaunchedEffect(
        normalizedSearch,
        platformFilter,
        collectionFilter,
        remoteFilters,
        remotePlatforms,
        sortBy,
        sortDescending,
        RomMSettings.url(context),
        RomMSettings.token(context),
    ) {
        activeRemoteSearchKey = currentRemoteSearchKey
        displayLimit = 100
        remoteSearchLoadingMore = false
        remoteSearchIds = null
        remoteSearchOffset = 0
        remoteSearchHasMore = false
        remoteSearchError = ""
        if (
            RomMSettings.isConnected(context) &&
            (
                normalizedSearch.length >= 2 || platformFilter != null || collectionFilter != null ||
                    remoteFilters.isActive || sortBy.isNotEmpty()
                )
        ) {
            remoteSearching = true
            delay(350)
            try {
                val selectedPlatformIds = platformFilter?.let { slug ->
                    remotePlatforms.filter { GamePlatforms.fromRomM(it.slug)?.slug == slug }.map { it.id }
                }.orEmpty()
                val page = withContext(Dispatchers.IO) {
                    RomMClient(
                        RomMSettings.url(context).orEmpty(),
                        RomMSettings.token(context),
                    ).listGameIdsPage(
                        searchTerm = normalizedSearch.takeIf { it.length >= 2 },
                        platformIds = selectedPlatformIds,
                        collection = collectionFilter,
                        filters = remoteFilters,
                        orderBy = sortBy,
                        orderDir = if (sortDescending) "desc" else "asc",
                        offset = 0,
                    )
                }
                remoteSearchIds = page.ids
                remoteSearchOffset = page.nextOffset
                remoteSearchHasMore = page.hasMore
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                remoteSearchError = error.message ?: error.toString()
            } finally {
                remoteSearching = false
            }
        } else {
            remoteSearching = false
        }
    }
    val loadMoreRemote: () -> Unit = {
        if (remoteSearchHasMore && !remoteSearchLoadingMore && RomMSettings.isConnected(context)) {
            val requestKey = activeRemoteSearchKey
            remoteSearchLoadingMore = true
            scope.launch {
                try {
                    val selectedPlatformIds = platformFilter?.let { slug ->
                        remotePlatforms.filter { GamePlatforms.fromRomM(it.slug)?.slug == slug }.map { it.id }
                    }.orEmpty()
                    val page = withContext(Dispatchers.IO) {
                        RomMClient(
                            RomMSettings.url(context).orEmpty(),
                            RomMSettings.token(context),
                        ).listGameIdsPage(
                            searchTerm = normalizedSearch.takeIf { it.length >= 2 },
                            platformIds = selectedPlatformIds,
                            collection = collectionFilter,
                            filters = remoteFilters,
                            orderBy = sortBy,
                            orderDir = if (sortDescending) "desc" else "asc",
                            offset = remoteSearchOffset,
                        )
                    }
                    if (activeRemoteSearchKey == requestKey) {
                        remoteSearchIds = (remoteSearchIds.orEmpty() + page.ids).distinct()
                        remoteSearchOffset = page.nextOffset
                        remoteSearchHasMore = page.hasMore
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (activeRemoteSearchKey == requestKey) {
                        remoteSearchError = error.message ?: error.toString()
                    }
                } finally {
                    if (activeRemoteSearchKey == requestKey) remoteSearchLoadingMore = false
                }
            }
        }
    }
    val recent = remember(version, games, normalizedSearch) {
        if (normalizedSearch.isEmpty()) {
            PlayHistory.recent(context, games).take(10)
        } else {
            PlayHistory.recent(context, games).filter { it.title.contains(normalizedSearch, ignoreCase = true) }.take(10)
        }
    }
    val entryByPath = remember(entries) { entries.flatMap { entry -> entry.locals.map { it.path to entry } }.toMap() }
    val platforms = remember(entries) { entries.map { it.platform }.distinctBy { it.slug } }
    val hasRemoteFilters = collectionFilter != null || remoteFilters.isActive || sortBy.isNotEmpty()
    val searchedEntries = remember(entries, normalizedSearch, remoteSearchIds, hasRemoteFilters) {
        val remoteIds = remoteSearchIds.orEmpty()
        val remoteIdSet = remoteIds.toHashSet()
        val remoteOrder = remoteIds.withIndex().associate { it.value to it.index }
        val remoteEntries = if (!hasRemoteFilters) {
            entries
        } else {
            entries.filter { entry -> entry.group?.versions?.any { it.id in remoteIdSet } == true }
        }
        val filtered = if (normalizedSearch.isEmpty()) {
            remoteEntries
        } else {
            remoteEntries.filter { entry ->
                entry.title.contains(normalizedSearch, ignoreCase = true) ||
                    entry.platform.name.contains(normalizedSearch, ignoreCase = true) ||
                    entry.group?.versions?.any { it.id in remoteIdSet } == true
            }
        }
        if (remoteOrder.isEmpty()) {
            filtered
        } else {
            filtered.sortedBy { entry ->
                entry.group?.versions?.mapNotNull { remoteOrder[it.id] }?.minOrNull() ?: Int.MAX_VALUE
            }
        }
    }
    val visible = remember(searchedEntries, platformFilter) {
        if (platformFilter == null) searchedEntries else searchedEntries.filter { it.platform.slug == platformFilter }
    }
    val displayedEntries = visible.take(displayLimit)
    val androidSorted = remember(androidGames, version, normalizedSearch) {
        AndroidGames.sortedByRecent(context, androidGames).filter {
            normalizedSearch.isEmpty() || it.title.contains(normalizedSearch, ignoreCase = true)
        }
    }
    val showAndroid = !hasRemoteFilters && (platformFilter == null || platformFilter == ANDROID_FILTER)
    val hero = if (normalizedSearch.isEmpty()) recent.firstOrNull() ?: games.firstOrNull() else null
    val heroFocus = remember { FocusRequester() }
    LaunchedEffect(hero?.path) {
        if (hero != null) runCatching { heroFocus.requestFocus() }
    }

    // Tema musical do jogo em destaque (experimental, desligado por padrão): trocar de destaque
    // para e sair da Biblioteca sempre encerra o que estiver tocando.
    val themeMusicEnabled = GameModeSettings.isSoundbitesEnabled(context)
    var themePlaying by remember { mutableStateOf(false) }
    var previousHeroPath by remember { mutableStateOf(hero?.path) }
    LaunchedEffect(hero?.path) {
        val previousPath = previousHeroPath
        if (previousPath != null && previousPath != hero?.path) {
            GameLog.log("iiDB", "jogo em destaque alterado; interrompendo soundbite anterior")
            GameThemeMusic.stop("jogo em destaque alterado")
            themePlaying = false
        }
        // A primeira carga da biblioteca (null -> primeiro jogo) não é uma troca de destaque.
        // Não interrompa uma busca iniciada enquanto a lista ainda estava sendo carregada.
        previousHeroPath = hero?.path
    }
    val onToggleTheme: () -> Unit = toggle@{
        val game = hero ?: return@toggle
        if (themePlaying) {
            themePlaying = false
            scope.launch(Dispatchers.IO) { GameThemeMusic.stop() }
        } else {
            themePlaying = true
            scope.launch(Dispatchers.IO) {
                GameThemeMusic.play(context, game.path, game.title, game.platform.name) { themePlaying = false }
                withContext(Dispatchers.Main) { themePlaying = GameThemeMusic.isPlaying(game.path) }
            }
        }
    }

    if (entries.isEmpty() && androidGames.isEmpty() && normalizedSearch.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text = stringResource(R.string.game_mode_empty_library),
                color = GameColors.textDim,
                fontSize = 16.sp,
            )
        }
        return
    }

    val filterOrder = remember(platforms, androidGames) {
        listOf<String?>(null) + platforms.map { it.slug } + listOfNotNull(ANDROID_FILTER.takeIf { androidGames.isNotEmpty() })
    }
    val chipsIndex = (if (hero != null) 1 else 0) + (if (recent.isNotEmpty()) 1 else 0)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onStepKeys(Key.ButtonL2, Key.ButtonR2) { platformFilter = cycle(filterOrder, platformFilter, it) }
            .onShortcutKey(Key.ButtonX, onRefresh)
            .onShortcutKey(Key.ButtonY) {
                scope.launch {
                    listState.animateScrollToItem(chipsIndex)
                    runCatching { chipFocus.requestFocus() }
                }
            },
    ) {
        val columns = (((maxWidth - 48.dp) + CARD_GAP) / (CARD_WIDTH + CARD_GAP)).toInt().coerceAtLeast(2)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
        ) {
            item(key = "romm-search") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = { Text("Pesquisar jogos no RomM e no aparelho") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (RomMSettings.isConnected(context)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sortOptions, key = { it.first }) { (key, label) ->
                                PlatformChip(label, sortBy == key) { sortBy = key }
                            }
                            if (sortBy.isNotEmpty()) {
                                item {
                                    PlatformChip(
                                        if (sortDescending) "↓ Decrescente" else "↑ Crescente",
                                        sortDescending,
                                    ) { sortDescending = !sortDescending }
                                }
                            }
                        }
                    }
                    if (remoteSearching) Text("Pesquisando no RomM…", color = GameColors.textDim, fontSize = 12.sp)
                    if (remoteSearchError.isNotEmpty()) Text("Pesquisa no RomM indisponível: $remoteSearchError", color = GameColors.danger, fontSize = 12.sp)
                }
            }
            if (hero != null) {
                item(key = "hero") {
                    HeroSection(
                        game = hero,
                        played = recent.isNotEmpty(),
                        busy = busy,
                        focus = heroFocus,
                        onPlay = { onPlay(hero) },
                        onDetails = { entryByPath[hero.path]?.let(onOpen) },
                        showThemeButton = themeMusicEnabled,
                        themePlaying = themePlaying,
                        onToggleTheme = onToggleTheme,
                    )
                }
            }
            if (normalizedSearch.isEmpty() && recent.isNotEmpty()) {
                item(key = "recent") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.game_recent))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                            items(recent, key = { it.path }) { game ->
                                GameCard(game, onClick = { entryByPath[game.path]?.let(onOpen) })
                            }
                        }
                    }
                }
            }
            if (remoteCollections.isNotEmpty()) {
                item(key = "romm-collections") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Coleções do RomM")
                        if (remoteCollectionError.isNotEmpty()) {
                            Text("Coleções do RomM indisponíveis: $remoteCollectionError", color = GameColors.danger, fontSize = 12.sp)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "romm-collection-all") {
                                PlatformChip(
                                    "Todas",
                                    collectionFilter == null,
                                    chipModifier(collectionFilter == null, chipFocus),
                                ) { collectionFilter = null }
                            }
                            items(remoteCollections, key = { "${it.kind}:${it.id}" }) { collection ->
                                val selected = collectionFilter?.let { it.kind == collection.kind && it.id == collection.id } == true
                                PlatformChip(
                                    collection.name,
                                    selected,
                                    chipModifier(selected, chipFocus),
                                ) { collectionFilter = if (selected) null else collection }
                            }
                        }
                    }
                }
            }
            if (RomMSettings.isConnected(context)) {
                item(key = "romm-filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Filtros do RomM")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "filter-favorites") {
                                PlatformChip("Favoritos", remoteFilters.favorites, chipModifier(remoteFilters.favorites, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(favorites = !remoteFilters.favorites)
                                }
                            }
                            item(key = "filter-duplicates") {
                                PlatformChip("Duplicados", remoteFilters.duplicates, chipModifier(remoteFilters.duplicates, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(duplicates = !remoteFilters.duplicates)
                                }
                            }
                            item(key = "filter-played") {
                                PlatformChip("Já jogados", remoteFilters.played, chipModifier(remoteFilters.played, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(played = !remoteFilters.played)
                                }
                            }
                            item(key = "filter-ra") {
                                PlatformChip("Com RetroAchievements", remoteFilters.retroAchievements, chipModifier(remoteFilters.retroAchievements, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(retroAchievements = !remoteFilters.retroAchievements)
                                }
                            }
                            item(key = "filter-saves") {
                                PlatformChip("Com saves", remoteFilters.saves, chipModifier(remoteFilters.saves, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(saves = !remoteFilters.saves)
                                }
                            }
                            item(key = "filter-states") {
                                PlatformChip("Com save states", remoteFilters.states, chipModifier(remoteFilters.states, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(states = !remoteFilters.states)
                                }
                            }
                            item(key = "filter-verified") {
                                PlatformChip("Verificados", remoteFilters.verified, chipModifier(remoteFilters.verified, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(verified = !remoteFilters.verified)
                                }
                            }
                            item(key = "filter-soundtracks") {
                                PlatformChip("Com trilha sonora", remoteFilters.soundtracks, chipModifier(remoteFilters.soundtracks, chipFocus)) {
                                    remoteFilters = remoteFilters.copy(soundtracks = !remoteFilters.soundtracks)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "platforms") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.game_by_platform))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            PlatformChip(stringResource(R.string.game_all), platformFilter == null, chipModifier(platformFilter == null, chipFocus)) { platformFilter = null }
                        }
                        items(platforms, key = { it.slug }) { platform ->
                            val selected = platformFilter == platform.slug
                            PlatformChip(platform.name, selected, chipModifier(selected, chipFocus)) { platformFilter = platform.slug }
                        }
                        if (androidGames.isNotEmpty()) {
                            item(key = ANDROID_FILTER) {
                                val selected = platformFilter == ANDROID_FILTER
                                PlatformChip(stringResource(R.string.game_android), selected, chipModifier(selected, chipFocus)) { platformFilter = ANDROID_FILTER }
                            }
                        }
                    }
                }
            }
            if (showAndroid && androidSorted.isNotEmpty()) {
                item(key = "android") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.game_android))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                            items(androidSorted, key = { it.packageName }) { game ->
                                AndroidCard(game, onClick = { onLaunchAndroid(game) })
                            }
                        }
                    }
                }
            }
            if (platformFilter != ANDROID_FILTER && displayedEntries.isNotEmpty()) {
                item(key = "library-title") { SectionTitle(stringResource(R.string.game_library_section)) }
                items(displayedEntries.chunked(columns), key = { row -> row.first().key }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                        row.forEach { entry -> EntryCard(entry, progress, syncVersion, onClick = { onOpen(entry) }) }
                    }
                }
            } else if ((normalizedSearch.isNotEmpty() || hasRemoteFilters) && !remoteSearching && visible.isEmpty() && androidSorted.isEmpty()) {
                item(key = "search-empty") {
                    Text("Nenhum jogo encontrado nesta página.", color = GameColors.textDim, fontSize = 14.sp)
                }
            }
            if (visible.size > displayedEntries.size) {
                item(key = "library-load-more") {
                    PlatformChip(
                        "Mostrar mais jogos (${visible.size - displayedEntries.size} restantes)",
                        selected = false,
                    ) { displayLimit += 100 }
                }
            }
            if (remoteSearchHasMore) {
                item(key = "romm-load-more") {
                    PlatformChip(
                        if (remoteSearchLoadingMore) "Carregando mais resultados…" else "Carregar mais resultados do RomM",
                        selected = false,
                    ) { loadMoreRemote() }
                }
            }
        }
    }
}

/** Só o chip selecionado recebe o foco quando o botão Y pede para ir aos consoles. */
private fun chipModifier(selected: Boolean, focus: FocusRequester): Modifier = if (selected) Modifier.focusRequester(focus) else Modifier

@Composable
private fun SaveSyncBadge(status: SaveSyncStatus?) {
    if (status == null) return
    val (label, color) = when (status) {
        SaveSyncStatus.SYNCING -> "Sincronizando…" to GameColors.accent
        SaveSyncStatus.SYNCED -> "Saves sincronizados" to GameColors.success
        SaveSyncStatus.PENDING -> "Sincronização pendente" to GameColors.danger
    }
    Text(
        text = label,
        color = GameColors.text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 6.dp)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** Cartão da grade: capa, selo "Baixar" se só está no servidor, "N versões" e o andamento do download. */
@Composable
private fun EntryCard(entry: LibraryEntry, progress: Map<Long, Long>, syncVersion: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val percent = entry.percent(progress)
    val syncStatus = entry.local?.let { SaveSync.status(context, it.path, CoreRegistry.chosen(context, it.platform)) }
    FocusSurface(onClick = onClick, modifier = Modifier.size(width = CARD_WIDTH, height = CARD_HEIGHT)) {
        CoverTile(entry.cover, modifier = Modifier.fillMaxSize())
        val versions = entry.group?.versions?.size ?: 0
        if (versions > 1) {
            CardBadge(stringResource(R.string.game_romm_versions, versions), GameColors.scrim, GameColors.text, Alignment.BottomStart)
        }
        if (syncStatus != null) {
            CardBadge(
                when (syncStatus) {
                    SaveSyncStatus.SYNCED -> "Sync OK"
                    SaveSyncStatus.SYNCING -> "Enviando…"
                    SaveSyncStatus.PENDING -> "Pendente"
                },
                GameColors.scrim,
                GameColors.text,
                Alignment.TopStart,
            )
        }
        if (entry.remoteOnly && percent == null) {
            CardBadge(stringResource(R.string.game_romm_download), GameColors.accent, GameColors.onAccent, Alignment.TopEnd)
        }
        if (percent != null) {
            Box(modifier = Modifier.fillMaxSize().background(GameColors.scrim), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.game_romm_downloading, percent),
                    color = GameColors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(percent / 100f)
                    .height(4.dp)
                    .background(GameColors.accent),
            )
        }
    }
}

@Composable
private fun BoxScope.CardBadge(text: String, background: Color, color: Color, alignment: Alignment) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(alignment)
            .padding(4.dp)
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private const val ANDROID_FILTER = "android"

@Composable
private fun AndroidCard(game: AndroidGame, onClick: () -> Unit) {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(null, game.packageName) {
        value = withContext(Dispatchers.IO) { GameArt.androidArt(context, game) }
        value = withContext(Dispatchers.IO) { GameArt.androidCover(context, game) } ?: value
    }
    FocusSurface(onClick = onClick, modifier = Modifier.size(width = CARD_WIDTH, height = CARD_HEIGHT)) {
        art?.let { Image(bitmap = it, contentDescription = game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun GameCard(game: LocalGame, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.size(width = CARD_WIDTH, height = CARD_HEIGHT)) {
        CoverTile(game, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun PlatformChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        background = if (selected) GameColors.surfaceHigh else GameColors.surface,
    ) {
        Text(
            text = label,
            color = if (selected) GameColors.text else GameColors.textDim,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun HeroSection(
    game: LocalGame,
    played: Boolean,
    busy: Boolean,
    focus: FocusRequester,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    showThemeButton: Boolean = false,
    themePlaying: Boolean = false,
    onToggleTheme: () -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
        CoverTile(game, modifier = Modifier.size(width = 112.dp, height = 150.dp).clip(RoundedCornerShape(12.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(if (played) R.string.game_continue_playing else R.string.game_featured).uppercase(),
                color = GameColors.textDim,
                fontSize = 12.sp,
            )
            Text(text = game.title, color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(text = game.platform.name, color = GameColors.textDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                GameButton(
                    label = stringResource(if (played) R.string.game_continue else R.string.game_play),
                    onClick = onPlay,
                    primary = true,
                    enabled = !busy,
                    modifier = Modifier.width(140.dp).focusRequester(focus),
                )
                GameIconButton(
                    label = stringResource(R.string.game_details),
                    symbol = "ⓘ",
                    onClick = onDetails,
                )
                if (showThemeButton) {
                    GameIconButton(
                        label = stringResource(if (themePlaying) R.string.game_theme_stop else R.string.game_theme_play),
                        symbol = if (themePlaying) "Ⅱ" else "♫",
                        onClick = onToggleTheme,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GameDetailScreen(
    entry: LibraryEntry,
    progress: Map<Long, Long>,
    syncVersion: Int,
    busy: Boolean,
    onPlay: (LocalGame) -> Unit,
    onLoad: (LocalGame) -> Unit,
    onDownload: (RemoteGame) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val local = entry.local
    val group = entry.group
    val chosenCoreId = remember(entry.platform.slug) { CoreRegistry.chosen(context, entry.platform) }
    val coreInfo = remember(entry.platform.slug, chosenCoreId) { CoreRegistry.info(entry.platform, chosenCoreId) }
    val history = remember(local?.path) { local?.let { PlayHistory.get(context, it.path) } ?: PlayHistory.Entry(0L, 0L) }
    val hasState = remember(local?.path, chosenCoreId, coreInfo.supportsStates) {
        local != null && coreInfo.supportsStates &&
            SaveStore(context, chosenCoreId, File(local.path)).hasState() &&
            !RaSettings.blocksStates(context, local.platform, chosenCoreId)
    }
    val percent = entry.percent(progress)
    val firstFocus = remember { FocusRequester() }
    val manyVersions = (group?.versions?.size ?: 0) > 1
    val detailGames = group?.versions.orEmpty()
    val metadataGames = detailGames.filter {
        it.metadata?.hasContent == true || it.metadata?.hasManualMetadata == true ||
            it.alternativeNames.isNotEmpty() || it.providerIds.isNotEmpty()
    }
    val associatedFiles = detailGames.flatMap { it.associatedFiles }.distinctBy { it.id }
    val isEasyRpgGame = detailGames.any { it.isEasyRpgGame }
    val metadataText = remember(entry.key, metadataGames) {
        buildString {
            val metadata = metadataGames.mapNotNull { it.metadata }
            fun appendValues(label: String, values: List<String>) {
                val distinct = values.filter { it.isNotBlank() }.distinct()
                if (distinct.isNotEmpty()) append("$label: ${distinct.joinToString(" · ")}\n")
            }
            appendValues("Gêneros", metadata.flatMap { it.genres })
            appendValues("Franquias", metadata.flatMap { it.franchises })
            appendValues("Coleções", metadata.flatMap { it.collections })
            appendValues("Empresas", metadata.flatMap { it.companies })
            appendValues("Editoras", metadata.flatMap { it.publishers })
            appendValues("Desenvolvedores", metadata.flatMap { it.developers })
            appendValues("Modos", metadata.flatMap { it.gameModes })
            appendValues("Classificação etária", metadata.flatMap { it.ageRatings })
            appendValues("Jogadores", metadata.mapNotNull { it.playerCount })
            appendValues(
                "Lançamento",
                metadata.mapNotNull { it.firstReleaseDate }
                    .distinct()
                    .map { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000L)) },
            )
            appendValues("Avaliação RomM", metadata.mapNotNull { it.averageRating?.toString() })
            if (metadata.any { it.hasManualMetadata }) append("Inclui metadados preenchidos manualmente no RomM.\n")
            appendValues("Títulos alternativos", metadataGames.flatMap { it.alternativeNames })
            metadataGames.flatMap { it.providerIds.entries }
                .groupBy { it.key }
                .toSortedMap()
                .forEach { (provider, values) ->
                    append("$provider: ${values.map { it.value }.distinct().joinToString(" · ")}\n")
                }
        }.trim()
    }
    val summary = remember(entry.key, detailGames) {
        detailGames.firstNotNullOfOrNull { game -> game.summary?.trim()?.takeIf { it.isNotBlank() } }
    }
    val rommIdentityText = remember(entry.key, detailGames, associatedFiles) {
        buildString {
            fun appendValues(label: String, values: List<String>) {
                val distinct = values.filter { it.isNotBlank() }.distinct()
                if (distinct.isNotEmpty()) append("$label: ${distinct.joinToString(" · ")}\n")
            }
            appendValues("IDs RomM", detailGames.map { it.id.toString() })
            appendValues("Arquivos principais", detailGames.map { it.fileName })
            appendValues("Nomes no servidor", detailGames.map { it.serverName })
            appendValues(
                "IDs de arquivo",
                detailGames.flatMap { game ->
                    listOfNotNull(game.fileId?.toString()) + game.fileIds.map { it.toString() }
                },
            )
            appendValues(
                "IDs de versões relacionadas",
                detailGames.flatMap { game ->
                    game.siblingIds.map { it.toString() } + listOfNotNull(game.mainSiblingId?.toString())
                },
            )
            appendValues("Arquivos associados (IDs)", associatedFiles.map { it.id.toString() })
            appendValues("Formato", detailGames.map { if (it.folder) "Pasta de jogo" else "Arquivo único" })
            if (detailGames.any { it.isEasyRpgGame }) append("Compatível com EasyRPG.\n")
        }.trim()
    }
    val personalDataText = remember(entry.key, detailGames) {
        buildString {
            val values = detailGames.mapNotNull { it.userData }.distinct()
            fun appendValues(label: String, entries: List<String>) {
                val distinct = entries.filter { it.isNotBlank() }.distinct()
                if (distinct.isNotEmpty()) append("$label: ${distinct.joinToString(" · ")}\n")
            }
            appendValues("Status", values.mapNotNull { it.status })
            appendValues("Avaliação pessoal", values.mapNotNull { it.rating.takeIf { rating -> rating > 0 }?.toString() })
            appendValues("Dificuldade", values.mapNotNull { it.difficulty.takeIf { difficulty -> difficulty > 0 }?.toString() })
            appendValues("Conclusão", values.mapNotNull { it.completion.takeIf { completion -> completion > 0 }?.toString() })
            appendValues(
                "Última vez jogado",
                values.mapNotNull { it.lastPlayedAtMs?.takeIf { timestamp -> timestamp > 0L } }
                    .map { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
            )
            appendValues(
                "Criado no RomM",
                values.mapNotNull { it.createdAtMs.takeIf { timestamp -> timestamp > 0L } }
                    .map { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
            )
            appendValues(
                "Atualizado no RomM",
                values.mapNotNull { it.updatedAtMs.takeIf { timestamp -> timestamp > 0L } }
                    .map { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
            )
            appendValues(
                "Marcadores",
                buildList {
                    if (values.any { it.isMainSibling }) add("Versão principal")
                    if (values.any { it.backlogged }) add("Backlog")
                    if (values.any { it.nowPlaying }) add("Jogando agora")
                    if (values.any { it.hidden }) add("Oculto")
                },
            )
            appendValues("Mídia fixada", values.flatMap { it.pinnedMedia })
        }.trim()
    }
    val noteGames = remember(entry.key) { group?.versions?.filter { it.hasNotes }.orEmpty() }
    val rommNotes by produceState<List<Pair<String, RemoteGameNote>>?>(null, entry.key, RomMSettings.url(context), RomMSettings.token(context)) {
        value = if (noteGames.isEmpty() || !RomMSettings.isConnected(context)) {
            emptyList()
        } else {
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                    noteGames.flatMap { game -> client.listNotes(game.id).map { game.fileName to it } }
                }
            }.onFailure { GameLog.error("romm", "falha ao carregar notas de ${entry.title}", it) }
                .getOrNull()
        }
    }
    var remoteSaveVersions by remember(entry.key) { mutableStateOf<List<RemoteFile>?>(null) }
    var remoteSaveVersionsLoading by remember(entry.key) { mutableStateOf(false) }
    var remoteSaveVersionsError by remember(entry.key) { mutableStateOf<String?>(null) }
    var pendingSaveVersionRestore by remember(entry.key) { mutableStateOf<RemoteFile?>(null) }
    var saveVersionRestoreLoading by remember(entry.key) { mutableStateOf(false) }
    var saveVersionRestoreMessage by remember(entry.key) { mutableStateOf<String?>(null) }
    var deviceSyncInspection by remember(entry.key) { mutableStateOf<DeviceSyncSaveInspection?>(null) }
    var deviceSyncInspectionLoading by remember(entry.key) { mutableStateOf(false) }
    var deviceSyncInspectionError by remember(entry.key) { mutableStateOf<String?>(null) }
    var deviceSyncExecution by remember(entry.key) { mutableStateOf<DeviceSyncExecutionResult?>(null) }
    var deviceSyncExecutionLoading by remember(entry.key) { mutableStateOf(false) }
    var deviceSyncExecutionError by remember(entry.key) { mutableStateOf<String?>(null) }
    val canInspectDeviceSyncSave = local != null &&
        entry.platform.launch == LaunchMode.LIBRETRO &&
        coreInfo.supportsRomMSaveSync &&
        chosenCoreId.isNotBlank() &&
        RomMSettings.isConnected(context) &&
        RomMSettings.deviceSyncDeviceId(context) != null &&
        runCatching { GameIdentityResolver.hasRomMLink(context, File(local.path)) }.getOrDefault(false)
    val syncStatus by produceState<SaveSyncStatus?>(null, local?.path, chosenCoreId, syncVersion, coreInfo.supportsRomMSaveSync) {
        value = if (coreInfo.supportsRomMSaveSync) local?.let { SaveSync.status(context, it.path, chosenCoreId) } else null
    }
    var rommLinked by remember(local?.path) {
        mutableStateOf(local?.let { runCatching { GameIdentityResolver.hasRomMLink(context, File(it.path)) }.getOrDefault(false) } ?: false)
    }
    var rommLinkAmbiguous by remember(local?.path) {
        mutableStateOf(local?.let { GameLibraryReconciler.isAmbiguous(context, it.path) } ?: false)
    }
    var showRomMLinkDialog by remember(entry.key) { mutableStateOf(false) }
    val coreUpdateAvailable by produceState<Boolean?>(null, local?.path, chosenCoreId) {
        value = if (local != null && entry.platform.launch == LaunchMode.LIBRETRO && chosenCoreId.isNotBlank()) {
            CoreInstaller.updateAvailable(context, chosenCoreId)
        } else {
            false
        }
    }
    var coreUpdating by remember(entry.key) { mutableStateOf(false) }
    var coreUpdateMessage by remember(entry.key) { mutableStateOf<String?>(null) }
    val detailScope = rememberCoroutineScope()
    var showAchievements by remember(entry.key) { mutableStateOf(false) }
    val achievements by produceState<List<RaEntry>?>(null, entry.key) {
        value = if (local != null) withContext(Dispatchers.IO) { loadAchievementPreview(context, local.path) } else null
    }
    val coverArt by produceState<ImageBitmap?>(null, entry.cover.path) {
        value = withContext(Dispatchers.IO) { GameArt.quick(context, entry.cover) }
    }
    val heroArt by produceState<ImageBitmap?>(null, entry.key) {
        value = withContext(Dispatchers.IO) { GameArt.heroArt(context, "${entry.platform.slug}|${entry.title}", entry.title) }
    }
    LaunchedEffect(entry.key) { runCatching { firstFocus.requestFocus() } }
    LaunchedEffect(entry.key, GameModeSettings.isSoundbitesEnabled(context), GameModeSettings.isSoundbitesAutoDetailsEnabled(context)) {
        if (GameModeSettings.isSoundbitesEnabled(context) && GameModeSettings.isSoundbitesAutoDetailsEnabled(context)) {
            GameLog.log("iiDB", "reprodução automática ao abrir detalhes: ${entry.title}")
            GameThemeMusic.play(context, entry.key, entry.title, entry.platform.name)
        } else {
            GameThemeMusic.stop("detalhes abertos sem reprodução automática")
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (showRomMLinkDialog && local != null) {
            RomMLinkDialog(
                local = local,
                onDismiss = { showRomMLinkDialog = false },
                onLink = {
                    rommLinked = true
                    rommLinkAmbiguous = false
                    showRomMLinkDialog = false
                    GameLog.log("romm", "ROM local associada explicitamente ao registro RomM #${it.id}: ${local.title}")
                },
            )
        }
        // Arte grande de fundo: do SteamGridDB quando tiver, senão a própria capa ampliada, com um
        // gradiente escuro por cima pra manter o texto legível (estilo tela de detalhes do SteamOS).
        (heroArt ?: coverArt)?.let { background ->
            Image(
                bitmap = background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to GameColors.background.copy(alpha = 0.55f),
                    0.4f to GameColors.background.copy(alpha = 0.88f),
                    1f to GameColors.background,
                ),
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            CoverTile(entry.cover, modifier = Modifier.size(width = 150.dp, height = 200.dp).clip(RoundedCornerShape(14.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = entry.platform.name.uppercase(), color = GameColors.textDim, fontSize = 12.sp)
                Text(text = entry.title, color = GameColors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    when {
                        local != null -> GameButton(
                            label = stringResource(R.string.game_play),
                            onClick = { onPlay(local) },
                            primary = true,
                            enabled = !busy,
                            modifier = Modifier.width(130.dp).focusRequester(firstFocus),
                        )

                        group != null && !manyVersions -> GameButton(
                            label = if (percent != null) stringResource(R.string.game_romm_downloading, percent) else stringResource(R.string.game_romm_download),
                            onClick = { onDownload(group.versions.first()) },
                            primary = true,
                            enabled = percent == null,
                            modifier = Modifier.width(150.dp).focusRequester(firstFocus),
                        )
                    }
                    if (local != null) {
                        GameButton(
                            label = stringResource(R.string.game_load_state_start),
                            onClick = { onLoad(local) },
                            enabled = hasState && !busy,
                            modifier = Modifier.width(170.dp),
                        )
                    }
                    GameIconButton(label = stringResource(R.string.game_hint_back), symbol = "←", onClick = onBack)
                }
                if (syncStatus != null) {
                    SaveSyncBadge(syncStatus)
                }
                if (
                    local != null &&
                    entry.platform.launch == LaunchMode.LIBRETRO &&
                    coreInfo.supportsRomMSaveSync &&
                    RomMSettings.isConnected(context)
                ) {
                    if (rommLinked) {
                        Text("Vínculo com RomM confirmado para esta conta.", color = GameColors.accent, fontSize = 13.sp)
                    } else if (rommLinkAmbiguous) {
                        GameButton(
                            label = "Resolver vínculo ambíguo",
                            onClick = { showRomMLinkDialog = true },
                            enabled = !busy,
                            modifier = Modifier.width(250.dp),
                        )
                    } else {
                        Text(
                            "Aguardando correspondência exata no catálogo RomM.",
                            color = GameColors.textDim,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (canInspectDeviceSyncSave) {
                    GameButton(
                        label = if (remoteSaveVersionsLoading) "Carregando histórico…" else "Histórico de SRAM",
                        onClick = {
                            if (!remoteSaveVersionsLoading && local != null) {
                                remoteSaveVersionsLoading = true
                                remoteSaveVersionsError = null
                                saveVersionRestoreMessage = null
                                detailScope.launch {
                                    try {
                                        val versions = withContext(Dispatchers.IO) {
                                            val connection = GameIdentityResolver.connectionSnapshot(context)
                                                ?: throw IllegalStateException("Conecte a conta RomM vinculada a este jogo.")
                                            val romId = GameIdentityResolver.linkedRomId(context, File(local.path), connection)
                                                ?: throw IllegalStateException("O vínculo RomM deste jogo não está disponível.")
                                            val slot = DeviceSyncArtifactPolicy.remoteSlotForCore(chosenCoreId)
                                            RomMClient(connection.url, connection.token)
                                                .listRemoteFiles(RemoteFileKind.Save, romId, slot)
                                                .filter { it.slot == slot }
                                                .sortedByDescending { it.updatedAtMs }
                                        }
                                        remoteSaveVersions = versions
                                        if (versions.isEmpty()) saveVersionRestoreMessage = "Nenhuma versão de SRAM foi encontrada neste slot."
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        remoteSaveVersionsError = error.message ?: "Não foi possível carregar o histórico do RomM."
                                        GameLog.error("Device Sync", "falha ao consultar histórico SRAM de ${entry.title}", error)
                                    } finally {
                                        remoteSaveVersionsLoading = false
                                    }
                                }
                            }
                        },
                        enabled = !remoteSaveVersionsLoading && !deviceSyncExecutionLoading && !busy,
                        modifier = Modifier.width(210.dp),
                    )
                    remoteSaveVersions?.let { versions ->
                        Text(
                            text = "Histórico de SRAM no RomM — ${versions.size} versão(ões). A mais recente aparece primeiro.",
                            color = GameColors.text,
                            fontSize = 13.sp,
                        )
                        versions.forEachIndexed { index, version ->
                            val timestamp = if (version.updatedAtMs > 0L) {
                                DateFormat.getDateTimeInstance().format(Date(version.updatedAtMs))
                            } else {
                                "Data não informada"
                            }
                            Text(
                                text = buildString {
                                    append(if (index == 0) "Mais recente • " else "Versão histórica • ")
                                    append(timestamp)
                                    version.emulator?.let { append(" • Núcleo: $it") }
                                    version.sizeBytes?.let { append(" • $it bytes") }
                                    version.contentHash?.let { append(" • MD5 ${it.take(8)}") }
                                },
                                color = GameColors.textDim,
                                fontSize = 12.sp,
                            )
                            GameButton(
                                label = "Restaurar esta versão (ID ${version.id})",
                                onClick = { pendingSaveVersionRestore = version },
                                enabled = !saveVersionRestoreLoading && !busy,
                                modifier = Modifier.width(260.dp),
                            )
                        }
                    }
                    remoteSaveVersionsError?.let { error ->
                        Text("Falha ao consultar histórico: $error", color = GameColors.danger, fontSize = 12.sp)
                    }
                    saveVersionRestoreMessage?.let { message ->
                        Text(message, color = GameColors.accent, fontSize = 12.sp)
                    }
                    GameButton(
                        label = if (deviceSyncInspectionLoading) "Inspecionando save…" else "Inspecionar save no RomM",
                        onClick = {
                            if (!deviceSyncInspectionLoading && local != null) {
                                deviceSyncInspectionLoading = true
                                deviceSyncInspection = null
                                deviceSyncInspectionError = null
                                detailScope.launch {
                                    try {
                                        val inspection = withContext(Dispatchers.IO) {
                                            RomMDeviceSyncDiagnostics.inspectLocalSave(
                                                context = context,
                                                romPath = local.path,
                                                coreId = chosenCoreId,
                                                platformSlug = entry.platform.slug,
                                            )
                                        }
                                        deviceSyncInspection = inspection
                                        val counts = DeviceSyncAction.entries.associateWith { action ->
                                            inspection.negotiation.operations.count { it.action == action }
                                        }
                                        GameLog.log(
                                            "Device Sync",
                                            "inspeção somente leitura concluída para ${entry.title}; save local incluído=${inspection.localSaveIncluded}; " +
                                                counts.entries.joinToString { "${it.key}=${it.value}" } +
                                                "; nenhuma operação aplicada",
                                        )
                                    } catch (error: CancellationException) {
                                        deviceSyncInspectionLoading = false
                                        throw error
                                    } catch (error: Exception) {
                                        deviceSyncInspectionError = error.message ?: "Não foi possível inspecionar o save no RomM."
                                        GameLog.error("Device Sync", "falha na inspeção somente leitura de ${entry.title}", error)
                                    } finally {
                                        deviceSyncInspectionLoading = false
                                    }
                                }
                            }
                        },
                        enabled = !deviceSyncInspectionLoading && !deviceSyncExecutionLoading && !busy,
                        modifier = Modifier.width(230.dp),
                    )
                    deviceSyncInspection?.let { inspection ->
                        val operations = inspection.negotiation.operations
                        Text(
                            text = "Inspeção concluída — ROM #${inspection.romId}. " +
                                if (inspection.localSaveIncluded) "Save local encontrado." else "Nenhum save local válido encontrado.",
                            color = GameColors.text,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "Sem alterações: nenhuma operação foi aplicada. " +
                                "Sem ação: ${operations.count { it.action == DeviceSyncAction.NoOp }} · " +
                                "Enviar: ${operations.count { it.action == DeviceSyncAction.Upload }} · " +
                                "Baixar: ${operations.count { it.action == DeviceSyncAction.Download }} · " +
                                "Conflitos: ${operations.count { it.action == DeviceSyncAction.Conflict }} · " +
                                "Excluir: ${operations.count { it.action == DeviceSyncAction.Delete }} · " +
                                "Desconhecidas: ${operations.count { it.action == DeviceSyncAction.Unknown }}",
                            color = GameColors.textDim,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "Save local: " +
                                (inspection.localSaveSizeBytes?.let { "$it bytes" } ?: "ausente") +
                                " · atualizado: ${inspection.localSaveUpdatedAt ?: "—"} · MD5: " +
                                (inspection.localSaveMd5?.take(8) ?: "—") +
                                ". Operações remotas: " +
                                operations.joinToString(" | ") { op ->
                                    "${op.action}/ROM ${op.romId}/slot ${op.slot ?: "—"}/ID ${op.saveId ?: "—"}/" +
                                        "${op.fileName ?: "—"}/emulador ${op.emulator ?: "—"}/" +
                                        "atualizado ${op.serverUpdatedAt ?: "—"}/MD5 ${op.serverContentHash?.take(8) ?: "—"}"
                                },
                            color = GameColors.textDim,
                            fontSize = 11.sp,
                        )
                    }
                    deviceSyncInspectionError?.let { error ->
                        Text(
                            text = "Falha na inspeção: $error",
                            color = GameColors.text,
                            fontSize = 12.sp,
                        )
                    }
                    GameButton(
                        label = if (deviceSyncExecutionLoading) "Sincronizando save…" else "Sincronizar save com RomM",
                        onClick = {
                            if (!deviceSyncExecutionLoading && local != null) {
                                deviceSyncExecutionLoading = true
                                deviceSyncExecution = null
                                deviceSyncExecutionError = null
                                detailScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            RomMDeviceSyncRunner.synchronizeLocalSave(
                                                context = context,
                                                romPath = local.path,
                                                coreId = chosenCoreId,
                                                platformSlug = entry.platform.slug,
                                            )
                                        }
                                        deviceSyncExecution = result
                                        GameLog.log(
                                            "Device Sync",
                                            "sincronização do save concluída para ${entry.title}; " +
                                                "upload=${result.uploaded}, download=${result.downloaded}, noOp=${result.noOp}, " +
                                                "conflict=${result.conflictsPreserved}, delete_ignored=${result.deletesIgnored}, " +
                                                "unknown_ignored=${result.unknownIgnored}, failed=${result.failed}",
                                        )
                                    } catch (error: CancellationException) {
                                        deviceSyncExecutionLoading = false
                                        throw error
                                    } catch (error: Exception) {
                                        deviceSyncExecutionError = error.message ?: "Não foi possível sincronizar o save com o RomM."
                                        GameLog.error("Device Sync", "falha na sincronização do save de ${entry.title}", error)
                                    } finally {
                                        deviceSyncExecutionLoading = false
                                    }
                                }
                            }
                        },
                        enabled = !deviceSyncExecutionLoading && !deviceSyncInspectionLoading && !busy,
                        primary = true,
                        modifier = Modifier.width(260.dp),
                    )
                    deviceSyncExecution?.let { result ->
                        Text(
                            text = "Sincronização do save — ROM #${result.romId}: " +
                                "${result.uploaded} envio(s), ${result.downloaded} download(s), ${result.noOp} sem alteração.",
                            color = GameColors.text,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "Conflitos preservados: ${result.conflictsPreserved} · " +
                                "exclusões recusadas: ${result.deletesIgnored} · " +
                                "operações desconhecidas: ${result.unknownIgnored} · falhas: ${result.failed}. " +
                                "Save states e artefatos não elegíveis não foram incluídos.",
                            color = GameColors.textDim,
                            fontSize = 12.sp,
                        )
                        if (result.conflictsPreserved > 0 && local != null) {
                            Text(
                                "As versões local e remota foram preservadas. Escolha manter a versão local abaixo ou use o Histórico de SRAM para restaurar uma versão do RomM.",
                                color = GameColors.accent,
                                fontSize = 12.sp,
                            )
                            GameButton(
                                label = if (deviceSyncExecutionLoading) "Resolvendo conflito…" else "Manter versão local e publicar no RomM",
                                onClick = {
                                    if (!deviceSyncExecutionLoading) {
                                        deviceSyncExecutionLoading = true
                                        detailScope.launch {
                                            try {
                                                val message = RomMDeviceSyncRunner.resolveConflictKeepLocal(
                                                    context = context,
                                                    romPath = local.path,
                                                    coreId = chosenCoreId,
                                                    platformSlug = entry.platform.slug,
                                                )
                                                saveVersionRestoreMessage = message
                                                deviceSyncExecution = result.copy(
                                                    conflictsPreserved = 0,
                                                    uploaded = result.uploaded + 1,
                                                )
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (error: Exception) {
                                                saveVersionRestoreMessage = error.message ?: "Não foi possível resolver o conflito."
                                                GameLog.error("Device Sync", "falha na resolução explícita do conflito de ${entry.title}", error)
                                            } finally {
                                                deviceSyncExecutionLoading = false
                                            }
                                        }
                                    }
                                },
                                enabled = !deviceSyncExecutionLoading && !busy,
                                primary = true,
                                modifier = Modifier.width(300.dp),
                            )
                        }
                    }
                    deviceSyncExecutionError?.let { error ->
                        Text(
                            text = "Falha na sincronização save: $error",
                            color = GameColors.danger,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (local != null && coreUpdateAvailable == true) {
                    GameButton(
                        label = if (coreUpdating) "Atualizando núcleo…" else "Atualizar núcleo",
                        onClick = {
                            if (!coreUpdating) {
                                coreUpdating = true
                                coreUpdateMessage = "Baixando e validando a atualização…"
                                detailScope.launch {
                                    val result = withContext(Dispatchers.IO) { CoreInstaller.update(context, chosenCoreId) }
                                    coreUpdating = false
                                    if (result.isSuccess) {
                                        coreUpdateMessage = "Núcleo atualizado com sucesso."
                                        GameLog.log("núcleos", "atualização manual concluída: $chosenCoreId")
                                    } else {
                                        coreUpdateMessage = "Falha na atualização. O núcleo anterior foi preservado quando possível."
                                        result.exceptionOrNull()?.let { GameLog.error("núcleos", "atualização manual falhou: $chosenCoreId", it) }
                                    }
                                }
                            }
                        },
                        enabled = !coreUpdating && !busy,
                        modifier = Modifier.width(190.dp),
                    )
                }
                if (manyVersions) {
                    VersionList(
                        entry = entry,
                        progress = progress,
                        firstFocus = firstFocus.takeIf { local == null },
                        onPlay = onPlay,
                        onDownload = onDownload,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Stat(stringResource(R.string.game_stat_playtime), formatPlaytime(history.playtimeMs))
                    Stat(
                        stringResource(R.string.game_stat_last_played),
                        if (history.played) {
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(history.lastPlayedMs))
                        } else {
                            stringResource(R.string.game_never_played)
                        },
                    )
                    Stat(stringResource(R.string.game_stat_origin), stringResource(if (group != null) R.string.game_origin_server else R.string.game_origin_local))
                }
                coreUpdateMessage?.let { message ->
                    Text(text = message, color = GameColors.textDim, fontSize = 12.sp)
                }
                if (!summary.isNullOrBlank()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(top = 8.dp).width(520.dp),
                    ) {
                        Text("Descrição", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = summary,
                            color = GameColors.textDim,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }
                detailGames.firstOrNull()?.let { remoteGame ->
                    RomMGameDataPanel(
                        game = remoteGame,
                        localPlaytimeMs = history.playtimeMs,
                        localLastPlayedMs = history.lastPlayedMs,
                    )
                }
                detailGames.firstOrNull()?.let { remoteGame ->
                    RomMMediaPanel(games = detailGames)
                }
                if (isEasyRpgGame) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            "EasyRPG · jogo de RPG Maker 2000/2003",
                            color = GameColors.accent,
                            fontSize = 13.sp,
                        )
                        Text(
                            "A sincronização dos saves nativos .lsd ainda não foi validada no Tandem.",
                            color = GameColors.textDim,
                            fontSize = 11.sp,
                        )
                    }
                }
                if (metadataText.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 8.dp).width(520.dp)) {
                        Text("Metadados do RomM", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(metadataText, color = GameColors.textDim, fontSize = 12.sp)
                    }
                }
                if (personalDataText.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 8.dp).width(520.dp)) {
                        Text("Dados pessoais no RomM", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(personalDataText, color = GameColors.textDim, fontSize = 12.sp)
                    }
                }
                if (rommIdentityText.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 8.dp).width(520.dp)) {
                        Text("Identificação e versões RomM", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(rommIdentityText, color = GameColors.textDim, fontSize = 12.sp)
                    }
                }
                if (associatedFiles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 8.dp).width(520.dp)) {
                        Text("Arquivos associados do RomM", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        associatedFiles.forEach { file ->
                            val category = file.category?.replaceFirstChar { it.uppercase() } ?: "Jogo"
                            val size = if (file.sizeBytes > 0L) " · ${file.sizeBytes / 1024} KB" else ""
                            val level = if (file.isTopLevel) " · Principal" else ""
                            val fileId = " · ID ${file.id}"
                            val hashes = listOfNotNull(
                                file.crcHash?.let { "CRC $it" },
                                file.md5Hash?.let { "MD5 $it" },
                                file.sha1Hash?.let { "SHA-1 $it" },
                            ).joinToString(" · ").let { if (it.isBlank()) "" else " · $it" }
                            Text("$category · ${file.fileName}$size$level$fileId$hashes", color = GameColors.textDim, fontSize = 11.sp)
                        }
                    }
                }
                if (noteGames.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp).width(520.dp)) {
                        Text("Notas do RomM", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        when {
                            rommNotes == null -> Text("Carregando notas…", color = GameColors.textDim, fontSize = 12.sp)

                            rommNotes!!.isEmpty() -> Text("Não foi possível carregar as notas ou não há notas visíveis.", color = GameColors.textDim, fontSize = 12.sp)

                            else -> rommNotes!!.forEach { (versionName, note) ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(note.title, color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    if (note.content.isNotBlank()) Text(note.content, color = GameColors.textDim, fontSize = 12.sp)
                                    if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "), color = GameColors.accent, fontSize = 11.sp)
                                    Text(
                                        text = buildString {
                                            if (note.username.isNotBlank()) append(note.username)
                                            if (versionName.isNotBlank()) {
                                                if (isNotEmpty()) append(" • ")
                                                append(versionName)
                                            }
                                        },
                                        color = GameColors.textDim,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                AchievementSummaryRow(
                    platform = entry.platform,
                    hasLocal = local != null,
                    entries = achievements,
                    onOpen = { showAchievements = true },
                )
                local?.let { Text(text = it.path, color = GameColors.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        achievements?.let { list ->
            if (showAchievements) {
                AchievementListPanel(title = entry.title, entries = list, hardcore = false)
            }
        }
    }
    // Fecha com o mesmo botão de voltar do controle (B) ou do Android, sem fechar a tela de detalhes junto.
    BackHandler(enabled = showAchievements) { showAchievements = false }
    pendingSaveVersionRestore?.let { selectedVersion ->
        Dialog(onDismissRequest = { if (!saveVersionRestoreLoading) pendingSaveVersionRestore = null }) {
            Column(
                modifier = Modifier
                    .width(440.dp)
                    .background(GameColors.backgroundTop, RoundedCornerShape(14.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Restaurar versão de SRAM?", color = GameColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "A versão escolhida será baixada e verificada. A SRAM atual será preservada em backup. Se for uma versão antiga, ela será publicada como uma nova versão ativa no RomM, sem apagar o histórico.",
                    color = GameColors.textDim,
                    fontSize = 14.sp,
                )
                Text(
                    "ID ${selectedVersion.id} • " +
                        (if (selectedVersion.updatedAtMs > 0L) DateFormat.getDateTimeInstance().format(Date(selectedVersion.updatedAtMs)) else "data não informada"),
                    color = GameColors.text,
                    fontSize = 13.sp,
                )
                GameButton(
                    label = if (saveVersionRestoreLoading) "Restaurando…" else "Confirmar restauração",
                    onClick = {
                        if (!saveVersionRestoreLoading && local != null) {
                            saveVersionRestoreLoading = true
                            detailScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        RomMDeviceSyncRunner.restoreRemoteSaveVersion(
                                            context = context,
                                            romPath = local.path,
                                            coreId = chosenCoreId,
                                            platformSlug = entry.platform.slug,
                                            saveId = selectedVersion.id,
                                        )
                                    }
                                    saveVersionRestoreMessage = result
                                    remoteSaveVersions = null
                                    pendingSaveVersionRestore = null
                                    GameLog.log("Device Sync", "usuário restaurou versão SRAM ID ${selectedVersion.id} de ${entry.title}")
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    saveVersionRestoreMessage = error.message ?: "Não foi possível restaurar a versão escolhida."
                                    GameLog.error("Device Sync", "falha ao restaurar SRAM ID ${selectedVersion.id} de ${entry.title}", error)
                                } finally {
                                    saveVersionRestoreLoading = false
                                }
                            }
                        }
                    },
                    enabled = !saveVersionRestoreLoading && !busy,
                    primary = true,
                )
                GameButton(
                    label = "Cancelar",
                    onClick = { pendingSaveVersionRestore = null },
                    enabled = !saveVersionRestoreLoading,
                )
            }
        }
    }
}

/** Resuminho de conquistas na tela de detalhes: "X de Y" + alguns ícones, com botão pra ver a lista toda. */
@Composable
private fun AchievementSummaryRow(platform: GamePlatform, hasLocal: Boolean, entries: List<RaEntry>?, onOpen: () -> Unit) {
    val context = LocalContext.current
    if (platform.raConsoleId == null || !RaSettings.isLoggedIn(context)) return
    Column(modifier = Modifier.padding(top = 6.dp)) {
        when {
            !hasLocal -> Text(
                text = stringResource(R.string.game_ra_preview_need_local),
                color = GameColors.textDim,
                fontSize = 13.sp,
            )

            entries == null -> Text(
                text = stringResource(
                    if (RaSettings.webApiKey(context) == null) {
                        R.string.game_ra_preview_need_web_key
                    } else {
                        R.string.game_ra_preview_unknown
                    },
                ),
                color = GameColors.textDim,
                fontSize = 13.sp,
            )

            entries.isEmpty() -> Text(
                text = stringResource(R.string.game_ra_status_none),
                color = GameColors.textDim,
                fontSize = 13.sp,
            )

            else -> FocusSurface(onClick = onOpen, shape = RoundedCornerShape(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                        entries.take(6).forEach { AchievementBadge(badge = it.achievement.badge, unlocked = it.unlocked) }
                    }
                    Text(
                        text = stringResource(R.string.game_ra_summary_short, entries.count { it.unlocked }, entries.size),
                        color = GameColors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label.uppercase(), color = GameColors.textDim, fontSize = 11.sp)
        Text(text = value, color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun formatPlaytime(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return when {
        minutes <= 0 -> stringResource(R.string.game_playtime_none)
        minutes < 60 -> stringResource(R.string.game_playtime_minutes, minutes)
        else -> stringResource(R.string.game_playtime_format, minutes / 60, minutes % 60)
    }
}

private enum class SettingsCategory(val title: String, val subtitle: String, val icon: String) {
    LIBRARY("Biblioteca e armazenamento", "Pastas, importação e organização dos jogos", "▦"),
    EMULATION("Emulação", "Núcleos, BIOS, emuladores e gráficos", "◉"),
    ONLINE("RomM e serviços online", "Servidor, dispositivos e sincronização", "☁"),
    MEDIA("Mídia e metadados", "Capas, scraping e trilhas sonoras", "▧"),
    CONTROLS("Controles e interface", "Gamepad virtual, navegação e comportamento", "⌖"),
    SYSTEM("Sistema e diagnóstico", "Logs, informações e encerramento", "⚙"),
}

@Composable
internal fun SettingsScreen(
    rootPath: String,
    needsAccess: Boolean,
    busy: Boolean,
    onChooseFolder: () -> Unit,
    onRefresh: () -> Unit,
    onGrantAccess: () -> Unit,
    onPickRom: () -> Unit,
    onOpenBios: () -> Unit,
    onOpenEmulators: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenCores: () -> Unit,
    onOpenScraper: () -> Unit,
    onOpenServer: () -> Unit,
    preferVulkan: Boolean,
    externalVulkanDriverEnabled: Boolean,
    vulkanDriverLabel: String?,
    vulkanDriverStatus: String,
    onTogglePreferVulkan: () -> Unit,
    onToggleExternalVulkanDriver: () -> Unit,
    onImportVulkanDriver: () -> Unit,
    onRemoveVulkanDriver: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val categoryFocus = remember { FocusRequester() }
    LaunchedEffect(selectedCategory) {
        if (selectedCategory == null) runCatching { categoryFocus.requestFocus() }
    }
    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }
    var virtualGamepadEnabled by remember { mutableStateOf(GameModeSettings.isVirtualGamepadEnabled(context)) }
    var virtualGamepadAutoHide by remember { mutableStateOf(GameModeSettings.isVirtualGamepadAutoHideEnabled(context)) }
    var virtualGamepadOpacity by remember { mutableStateOf(GameModeSettings.virtualGamepadOpacity(context)) }
    var virtualGamepadScale by remember { mutableStateOf(GameModeSettings.virtualGamepadScale(context)) }
    var virtualGamepadOffsetX by remember { mutableStateOf(GameModeSettings.virtualGamepadOffsetX(context)) }
    var virtualGamepadOffsetY by remember { mutableStateOf(GameModeSettings.virtualGamepadOffsetY(context)) }
    var autoEnter by remember { mutableStateOf(GameModeSettings.isAutoEnterEnabled(context)) }
    val toggleAuto = {
        autoEnter = !autoEnter
        GameModeSettings.setAutoEnterEnabled(context, autoEnter)
    }
    var sound by remember { mutableStateOf(GameModeSettings.isSoundEnabled(context)) }
    val toggleSound = {
        sound = !sound
        GameModeSettings.setSoundEnabled(context, sound)
    }
    var themeMusic by remember { mutableStateOf(GameModeSettings.isSoundbitesEnabled(context)) }
    val toggleThemeMusic = {
        themeMusic = !themeMusic
        GameModeSettings.setSoundbitesEnabled(context, themeMusic)
        if (!themeMusic) {
            GameThemeMusic.stop("recurso desativado nas configurações")
        }
    }
    var autoThemeMusicOnDetails by remember { mutableStateOf(GameModeSettings.isSoundbitesAutoDetailsEnabled(context)) }
    val toggleAutoThemeMusicOnDetails = {
        autoThemeMusicOnDetails = !autoThemeMusicOnDetails
        GameModeSettings.setSoundbitesAutoDetailsEnabled(context, autoThemeMusicOnDetails)
    }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyConfigured by remember { mutableStateOf(IidbKeyStore.get(context) != null) }
    var iidbStatus by remember { mutableStateOf("") }
    var checkingIidb by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var remoteDevices by remember { mutableStateOf<List<RemoteDevice>?>(null) }
    var remoteDevicesLoading by remember { mutableStateOf(false) }
    var remoteDevicesError by remember { mutableStateOf("") }
    val refreshRemoteDevices = {
        if (!RomMSettings.isConnected(context)) {
            remoteDevices = null
            remoteDevicesError = "Conecte-se ao RomM para consultar os dispositivos."
        } else if (!remoteDevicesLoading) {
            scope.launch {
                remoteDevicesLoading = true
                remoteDevicesError = ""
                runCatching {
                    withContext(Dispatchers.IO) {
                        RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context)).listDevices()
                    }
                }.onSuccess { remoteDevices = it }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        remoteDevicesError = error.message ?: "Não foi possível consultar os dispositivos."
                    }
                remoteDevicesLoading = false
            }
        }
    }
    LaunchedEffect(RomMSettings.url(context), RomMSettings.token(context), RomMSettings.isConnected(context)) {
        if (RomMSettings.isConnected(context)) refreshRemoteDevices() else remoteDevices = null
    }
    var scraper by remember { mutableStateOf(GameModeSettings.isScraperEnabled(context)) }
    var raRichPresenceVisible by remember { mutableStateOf(GameModeSettings.isRaRichPresenceVisible(context)) }
    val toggleRaRichPresence = {
        raRichPresenceVisible = !raRichPresenceVisible
        GameModeSettings.setRaRichPresenceVisible(context, raRichPresenceVisible)
    }
    val toggleScraper = {
        scraper = !scraper
        GameModeSettings.setScraperEnabled(context, scraper)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .onShortcutKey(Key.ButtonB) { if (selectedCategory != null) selectedCategory = null }
            .width(520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectedCategory == null) {
            SettingsSection("Configurações")
            SettingsCategory.entries.forEachIndexed { index, category ->
                FocusSurface(
                    onClick = { selectedCategory = category },
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(categoryFocus) else Modifier),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(category.icon, color = GameColors.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(category.title, color = GameColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(category.subtitle, color = GameColors.textDim, fontSize = 11.sp)
                        }
                        Text("›", color = GameColors.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GameIconButton(
                    label = "Voltar às configurações",
                    symbol = "←",
                    onClick = { selectedCategory = null },
                )
                Text(selectedCategory?.title.orEmpty(), color = GameColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            when (selectedCategory) {
                SettingsCategory.LIBRARY -> {
                    SettingsSection(stringResource(R.string.game_mode_section_library))
                    Text(
                        text = stringResource(R.string.game_mode_folder_label, rootPath),
                        color = GameColors.textDim,
                        fontSize = 14.sp,
                    )
                    GameButton(stringResource(R.string.game_mode_choose_folder), onChooseFolder)
                    GameButton(stringResource(R.string.game_mode_refresh), onRefresh)
                    if (needsAccess) {
                        Text(text = stringResource(R.string.game_mode_needs_access), color = GameColors.danger, fontSize = 14.sp)
                        GameButton(stringResource(R.string.game_mode_grant_access), onGrantAccess, primary = true)
                    }
                    GameButton(stringResource(R.string.game_mode_play_rom), onPickRom, enabled = !busy)
                    GameButton(stringResource(R.string.game_folders_open), onOpenFolders)
                }

                SettingsCategory.EMULATION -> {
                    SettingsSection(stringResource(R.string.game_mode_section_platforms))
                    GameButton(stringResource(R.string.game_bios_open), onOpenBios)
                    GameButton(stringResource(R.string.game_cores_open), onOpenCores)
                    GameButton(stringResource(R.string.game_emulators_open), onOpenEmulators)

                    SettingsSection("Gráficos e Vulkan")
                    SettingsSwitchRow(
                        label = "Preferir Vulkan quando disponível",
                        checked = preferVulkan,
                        onToggle = onTogglePreferVulkan,
                    )
                    Text(
                        "O Tandem usa Vulkan quando o núcleo solicita esse modo e o dispositivo oferece suporte. A preferência também orienta núcleos que permitem escolher o renderizador.",
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Driver Adreno: ${vulkanDriverLabel ?: "driver padrão do Android"}",
                        color = GameColors.text,
                        fontSize = 13.sp,
                    )
                    if (vulkanDriverLabel != null) {
                        SettingsSwitchRow(
                            label = "Usar driver externo Adreno (Turnip)",
                            checked = externalVulkanDriverEnabled,
                            onToggle = onToggleExternalVulkanDriver,
                        )
                    }
                    GameButton(
                        label = if (vulkanDriverLabel == null) "Importar driver Turnip (.zip)" else "Substituir driver Turnip (.zip)",
                        onClick = onImportVulkanDriver,
                    )
                    if (vulkanDriverLabel != null) {
                        GameButton("Remover driver externo", onRemoveVulkanDriver, danger = true)
                    }
                    if (vulkanDriverStatus.isNotBlank()) {
                        Text(vulkanDriverStatus, color = GameColors.textDim, fontSize = 12.sp)
                    }
                    Text(
                        "Importe um pacote ZIP compatível com Android ARM64 que contenha libvulkan_freedreno.so. Recurso destinado a GPUs Adreno (Snapdragon); os ajustes são aplicados na próxima sessão.",
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )
                }

                SettingsCategory.ONLINE -> {
                    SettingsSection(stringResource(R.string.game_mode_section_online))
                    GameButton(stringResource(R.string.game_server_open), onOpenServer)
                    if (RomMSettings.isConnected(context)) {
                        Text("Dispositivos registrados no RomM", color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        GameButton(
                            if (remoteDevicesLoading) "Atualizando dispositivos…" else "Atualizar dispositivos",
                            refreshRemoteDevices,
                            enabled = !remoteDevicesLoading,
                        )
                        if (remoteDevicesError.isNotEmpty()) {
                            Text(remoteDevicesError, color = GameColors.danger, fontSize = 12.sp)
                        }
                        remoteDevices?.let { devices ->
                            if (devices.isEmpty()) {
                                Text("Nenhum dispositivo registrado foi retornado pelo servidor.", color = GameColors.textDim, fontSize = 12.sp)
                            } else {
                                devices.forEach { device ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(GameColors.surface, RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            device.name.ifBlank { "Dispositivo ${device.id}" },
                                            color = GameColors.text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            listOfNotNull(device.platform, device.client, device.clientVersion?.let { "v$it" }, device.syncMode?.let { "sync $it" }).joinToString(" • ")
                                                .ifBlank { "Tipo não informado pelo servidor" },
                                            color = GameColors.textDim,
                                            fontSize = 12.sp,
                                        )
                                        Text(
                                            when (device.online) {
                                                true -> "Online"
                                                false -> "Offline"
                                                null -> "Presença indisponível (endpoint de dispositivos online não disponível)"
                                            },
                                            color = if (device.online == true) GameColors.accent else GameColors.textDim,
                                            fontSize = 12.sp,
                                        )
                                        device.syncEnabled?.let { enabled ->
                                            Text(
                                                if (enabled) "Sincronização habilitada" else "Sincronização desabilitada",
                                                color = GameColors.textDim,
                                                fontSize = 11.sp,
                                            )
                                        }
                                        if (device.capabilities.isEmpty()) {
                                            Text("Capacidades não declaradas pelo servidor.", color = GameColors.textDim, fontSize = 12.sp)
                                        } else {
                                            device.capabilities.toSortedMap().forEach { (capability, supported) ->
                                                Text(
                                                    "$capability: ${if (supported) "suportado" else "não suportado"}",
                                                    color = GameColors.textDim,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text("Conecte-se ao RomM para consultar os dispositivos registrados.", color = GameColors.textDim, fontSize = 12.sp)
                    }
                    GameButton(stringResource(R.string.game_sgdb_open), onOpenScraper)
                    SettingsSwitchRow(label = stringResource(R.string.game_mode_scraper), checked = scraper, onToggle = toggleScraper)
                    SettingsSwitchRow(label = "Exibir Rich Presence durante o jogo", checked = raRichPresenceVisible, onToggle = toggleRaRichPresence)
                    Text(
                        text = "Quando desativado, somente a sobreposição visual é ocultada. O RetroAchievements continua atualizando o estado da partida em segundo plano.",
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )
                }

                SettingsCategory.MEDIA -> {
                    SettingsSection("iiDB — soundbites")
                    Text(
                        text = if (apiKeyConfigured) "Chave da iiDB configurada e protegida pelo Android Keystore." else "Informe sua chave pessoal da iiDB. Ela não será incluída no APK nem enviada aos servidores de assets.",
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Chave de API iiDB") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    GameButton(
                        label = if (checkingIidb) "Validando chave…" else "Validar e salvar chave",
                        onClick = {
                            if (apiKeyInput.isBlank() || checkingIidb) {
                                iidbStatus = "Informe uma chave válida."
                            } else {
                                checkingIidb = true
                                iidbStatus = "Verificando acesso à iiDB…"
                                val candidate = apiKeyInput
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { IidbSoundbites.checkAccess(candidate) }
                                    if (result == IidbSoundbites.AccessResult.VALID) {
                                        val saved = runCatching { withContext(Dispatchers.IO) { IidbKeyStore.save(context, candidate) } }.isSuccess
                                        if (saved) {
                                            apiKeyConfigured = true
                                            apiKeyInput = ""
                                            iidbStatus = "Chave validada e salva com segurança."
                                        } else {
                                            iidbStatus = "A chave foi validada, mas não pôde ser protegida neste aparelho."
                                        }
                                    } else {
                                        iidbStatus = when (result) {
                                            IidbSoundbites.AccessResult.INVALID_KEY -> "A iiDB rejeitou a chave. Confira e tente novamente."
                                            IidbSoundbites.AccessResult.DENIED -> "A chave não tem permissão para acessar a API."
                                            else -> "Não foi possível validar. Verifique a conexão e tente novamente."
                                        }
                                    }
                                    checkingIidb = false
                                }
                            }
                        },
                        primary = true,
                        enabled = !checkingIidb,
                    )
                    if (apiKeyConfigured) {
                        GameButton(
                            label = "Remover chave da iiDB",
                            onClick = {
                                IidbKeyStore.clear(context)
                                apiKeyConfigured = false
                                apiKeyInput = ""
                                iidbStatus = "Chave removida."
                                GameThemeMusic.stop("chave removida")
                            },
                            danger = true,
                        )
                    }
                    if (iidbStatus.isNotBlank()) {
                        Text(text = iidbStatus, color = GameColors.textDim, fontSize = 12.sp)
                    }
                    Text(
                        text = "Os soundbites são clips curtos fornecidos pela iiDB. São baixados sob demanda para o cache local, não são distribuídos pelo Tandem e não repetem em loop.",
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )
                    GameButton(
                        label = "Sobre a iiDB / iiSU",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iisu.network/wiki/iidb-developers"))) },
                    )
                }

                SettingsCategory.CONTROLS -> {
                    SettingsSection("Controles virtuais")
                    SettingsSwitchRow(
                        label = "Exibir controles na tela durante os jogos",
                        checked = virtualGamepadEnabled,
                        onToggle = {
                            virtualGamepadEnabled = !virtualGamepadEnabled
                            GameModeSettings.setVirtualGamepadEnabled(context, virtualGamepadEnabled)
                        },
                    )
                    SettingsSwitchRow(
                        label = "Ocultar quando conectar controle físico",
                        checked = virtualGamepadAutoHide,
                        onToggle = {
                            virtualGamepadAutoHide = !virtualGamepadAutoHide
                            GameModeSettings.setVirtualGamepadAutoHideEnabled(context, virtualGamepadAutoHide)
                        },
                    )
                    Text("Opacidade: ${(virtualGamepadOpacity * 100).toInt()}%", color = GameColors.textDim, fontSize = 13.sp)
                    Slider(
                        value = virtualGamepadOpacity,
                        onValueChange = {
                            virtualGamepadOpacity = it
                            GameModeSettings.setVirtualGamepadOpacity(context, it)
                        },
                        valueRange = 0.25f..1f,
                    )
                    Text("Tamanho: ${(virtualGamepadScale * 100).toInt()}%", color = GameColors.textDim, fontSize = 13.sp)
                    Slider(
                        value = virtualGamepadScale,
                        onValueChange = {
                            virtualGamepadScale = it
                            GameModeSettings.setVirtualGamepadScale(context, it)
                        },
                        valueRange = 0.7f..1.3f,
                    )
                    Text("Posição horizontal: ${(virtualGamepadOffsetX * 100).toInt()}%", color = GameColors.textDim, fontSize = 13.sp)
                    Slider(
                        value = virtualGamepadOffsetX,
                        onValueChange = {
                            virtualGamepadOffsetX = it
                            GameModeSettings.setVirtualGamepadOffsetX(context, it)
                        },
                        valueRange = -0.08f..0.08f,
                    )
                    Text("Posição vertical: ${(virtualGamepadOffsetY * 100).toInt()}%", color = GameColors.textDim, fontSize = 13.sp)
                    Slider(
                        value = virtualGamepadOffsetY,
                        onValueChange = {
                            virtualGamepadOffsetY = it
                            GameModeSettings.setVirtualGamepadOffsetY(context, it)
                        },
                        valueRange = -0.08f..0.08f,
                    )
                    Text(
                        "As alterações são salvas automaticamente e aplicadas na próxima sessão de jogo. O layout mantém dois analógicos independentes.",
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )

                    SettingsSection(stringResource(R.string.game_mode_section_interface))
                    SettingsSwitchRow(label = stringResource(R.string.game_mode_auto_enter), checked = autoEnter, onToggle = toggleAuto)
                    SettingsSwitchRow(label = stringResource(R.string.game_mode_sound), checked = sound, onToggle = toggleSound)
                    SettingsSwitchRow(label = stringResource(R.string.game_theme_music_toggle), checked = themeMusic, onToggle = toggleThemeMusic)
                    SettingsSwitchRow(label = stringResource(R.string.game_theme_music_auto_details), checked = autoThemeMusicOnDetails, onToggle = toggleAutoThemeMusicOnDetails)
                    Text(
                        text = stringResource(R.string.game_theme_music_note),
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )
                }

                SettingsCategory.SYSTEM -> {
                    SettingsSection(stringResource(R.string.game_mode_section_other))
                    GameButton(stringResource(R.string.game_log_export), rememberLogExporter())
                    GameButton(stringResource(R.string.game_mode_exit), onExit, danger = true)
                    Box(modifier = Modifier.height(12.dp))
                }

                null -> Unit
            }
        }
    }
}

/** Cabeçalho de uma seção da tela de Configurações (ou das Opções do núcleo), só pra agrupar visualmente. */
@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title.uppercase(),
        color = GameColors.textDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    FocusSurface(onClick = onToggle, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = GameColors.text, fontSize = 16.sp)
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
