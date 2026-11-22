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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.android.launcher3.R
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
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
    val recent = remember(version, games) { PlayHistory.recent(context, games).take(10) }
    val entryByPath = remember(entries) { entries.flatMap { entry -> entry.locals.map { it.path to entry } }.toMap() }
    var platformFilter by remember { mutableStateOf<String?>(null) }
    val platforms = remember(entries) { entries.map { it.platform }.distinctBy { it.slug } }
    val visible = remember(entries, platformFilter) {
        if (platformFilter == null) entries else entries.filter { it.platform.slug == platformFilter }
    }
    val androidSorted = remember(androidGames, version) { AndroidGames.sortedByRecent(context, androidGames) }
    val showAndroid = platformFilter == null || platformFilter == ANDROID_FILTER
    val hero = recent.firstOrNull() ?: games.firstOrNull()
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

    if (entries.isEmpty() && androidGames.isEmpty()) {
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
            if (recent.isNotEmpty()) {
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
            if (platformFilter != ANDROID_FILTER && visible.isNotEmpty()) {
                item(key = "library-title") { SectionTitle(stringResource(R.string.game_library_section)) }
                items(visible.chunked(columns), key = { row -> row.first().key }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                        row.forEach { entry -> EntryCard(entry, progress, syncVersion, onClick = { onOpen(entry) }) }
                    }
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
                    SaveSyncStatus.SYNCED -> "Saves sincronizados"
                    SaveSyncStatus.SYNCING -> "Sincronizando…"
                    SaveSyncStatus.PENDING -> "Sync pendente"
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
                GameButton(label = stringResource(R.string.game_details), onClick = onDetails, modifier = Modifier.width(120.dp))
                if (showThemeButton) {
                    GameButton(
                        label = stringResource(if (themePlaying) R.string.game_theme_stop else R.string.game_theme_play),
                        onClick = onToggleTheme,
                        modifier = Modifier.width(170.dp),
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
    val history = remember(local?.path) { local?.let { PlayHistory.get(context, it.path) } ?: PlayHistory.Entry(0L, 0L) }
    val hasState = remember(local?.path) {
        local != null && SaveStore(context, CoreRegistry.chosen(context, local.platform), File(local.path)).hasState() && !RaSettings.blocksStates(context, local.platform)
    }
    val percent = entry.percent(progress)
    val firstFocus = remember { FocusRequester() }
    val manyVersions = (group?.versions?.size ?: 0) > 1
    val summary = remember(entry.key) { group?.versions?.firstOrNull()?.summary }
    val chosenCoreId = remember(entry.platform.slug) { CoreRegistry.chosen(context, entry.platform) }
    val syncStatus by produceState<SaveSyncStatus?>(null, local?.path, chosenCoreId, syncVersion) {
        value = local?.let { SaveSync.status(context, it.path, chosenCoreId) }
    }
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
                    GameButton(label = stringResource(R.string.game_hint_back), onClick = onBack, modifier = Modifier.width(100.dp))
                }
                if (syncStatus != null) {
                    SaveSyncBadge(syncStatus)
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
                    Text(
                        text = summary,
                        color = GameColors.textDim,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp).width(520.dp),
                    )
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
    onExit: () -> Unit,
) {
    val context = LocalContext.current
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
            .width(520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

        SettingsSection(stringResource(R.string.game_mode_section_platforms))
        GameButton(stringResource(R.string.game_bios_open), onOpenBios)
        GameButton(stringResource(R.string.game_cores_open), onOpenCores)
        GameButton(stringResource(R.string.game_emulators_open), onOpenEmulators)

        SettingsSection(stringResource(R.string.game_mode_section_online))
        GameButton(stringResource(R.string.game_server_open), onOpenServer)
        GameButton(stringResource(R.string.game_sgdb_open), onOpenScraper)
        SettingsSwitchRow(label = stringResource(R.string.game_mode_scraper), checked = scraper, onToggle = toggleScraper)
        SettingsSwitchRow(label = "Exibir Rich Presence durante o jogo", checked = raRichPresenceVisible, onToggle = toggleRaRichPresence)
        Text(
            text = "Quando desativado, somente a sobreposição visual é ocultada. O RetroAchievements continua atualizando o estado da partida em segundo plano.",
            color = GameColors.textDim,
            fontSize = 12.sp,
        )

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

        SettingsSection(stringResource(R.string.game_mode_section_other))
        GameButton(stringResource(R.string.game_log_export), rememberLogExporter())
        GameButton(stringResource(R.string.game_mode_exit), onExit, danger = true)
        Box(modifier = Modifier.height(12.dp))
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
