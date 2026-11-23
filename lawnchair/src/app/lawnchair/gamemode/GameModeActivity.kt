package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modo jogos: biblioteca (pasta configurável, jogos baixados e colocados à mão), destaque de
 * "continuar jogando", recentes, filtro por plataforma, detalhe do jogo e ajustes.
 * Visual segue os mockups aprovados (estilo SteamOS), navegação só por controle físico.
 */
class GameModeActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)
    private var padLost by mutableStateOf(false)
    private lateinit var lostWatcher: ControllerLostWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameLog.init(applicationContext)
        Thread { SessionDiagnostics.reportPreviousCrashes(applicationContext) }.start()
        // Activity só possui Context válido depois que o Android conclui a anexação.
        // Inicializar o watcher aqui evita acessar applicationContext durante a construção.
        lostWatcher = ControllerLostWatcher(applicationContext) {
            if (GameModeSettings.isAutoEnterEnabled(applicationContext)) padLost = true
        }
        enableEdgeToEdge()
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                Box {
                    GameModeRoot(refreshKey = resumeTick, onExit = { finish() })
                    if (padLost) {
                        PadLostDialog(onStay = { padLost = false }, onLeave = { finish() })
                    }
                }
            }
        }
    }

    /** Tela cheia: esconde a barra de status e a de navegação (aparecem ao deslizar da borda). */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        lostWatcher.start()
    }

    override fun onPause() {
        lostWatcher.stop()
        super.onPause()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, GameModeActivity::class.java)
    }
}

private data class PendingSaveSync(
    val coreId: String,
    val romPath: String,
    val title: String,
    val loadState: Boolean,
    val platform: GamePlatform?,
    val error: String,
    val localSaveAvailable: Boolean,
)

private data class PendingLegacySaveClaim(
    val coreId: String,
    val romPath: String,
    val title: String,
    val loadState: Boolean,
    val platform: GamePlatform?,
    val gameIdentity: String,
    val sources: Map<String, File>,
)

private enum class GameTab(val label: Int) {
    Library(R.string.game_tab_library),
    Achievements(R.string.game_tab_achievements),
    Settings(R.string.game_tab_settings),
}

/** Identidade da tela mostrada no meio (fora das abas) e sua "profundidade", para animar a troca: abrir um detalhe ou uma sub-tela desliza para dentro; voltar desliza para fora; trocar de aba só esmaece. */
private data class ScreenState(val key: String, val depth: Int)

@Composable
private fun GameModeRoot(refreshKey: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(GameTab.Library) }
    var detailKey by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var pendingSaveSync by remember { mutableStateOf<PendingSaveSync?>(null) }
    var pendingLegacySaveClaim by remember { mutableStateOf<PendingLegacySaveClaim?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rootPath by remember { mutableStateOf(GameLibrarySettings.rootPath(context)) }
    var games by remember { mutableStateOf(emptyList<LocalGame>()) }
    var androidGames by remember { mutableStateOf(emptyList<AndroidGame>()) }
    var hasAccess by remember { mutableStateOf(StorageAccess.isGranted()) }
    var scanTick by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var showBios by remember { mutableStateOf(false) }
    var showEmulators by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showCores by remember { mutableStateOf(false) }
    var showScraper by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    val remote = remember { RemoteLibrary(context, scope) }
    val entries = remember(games, remote.games, rootPath, version) {
        buildEntries(context, games, remote.games.orEmpty(), File(rootPath))
    }
    val detail = entries.firstOrNull { it.key == detailKey }
    val networkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    DisposableEffect(Unit) {
        onDispose { GameThemeMusic.stop("modo jogos encerrado") }
    }
    LaunchedEffect(tab, detailKey) {
        if (detailKey == null) {
            GameThemeMusic.stop(if (tab == GameTab.Library) "saída dos detalhes do jogo" else "saída da Biblioteca")
        }
    }

    LaunchedEffect(remote.connected, remote.tick, refreshKey) { remote.refresh() }
    LaunchedEffect(remote.connected) {
        if (remote.connected && !localNetworkGranted(context)) runCatching { networkRequest.launch(LOCAL_NETWORK_PERMISSION) }
    }

    LaunchedEffect(refreshKey, scanTick, rootPath) {
        hasAccess = StorageAccess.isGranted()
        withContext(Dispatchers.IO) { BiosManager.importFromFolder(context, File(rootPath)) }
        games = withContext(Dispatchers.IO) { GameLibraryScanner.scan(File(rootPath)) }
        androidGames = withContext(Dispatchers.IO) { AndroidGames.list(context) }
        version++
    }

    BackHandler(enabled = detail != null || showBios || showEmulators || showFolders || showCores || showScraper || showServer) {
        when {
            showBios -> showBios = false
            showEmulators -> showEmulators = false
            showFolders -> showFolders = false
            showCores -> showCores = false
            showScraper -> showScraper = false
            showServer -> showServer = false
            else -> detailKey = null
        }
    }

    suspend fun startGame(
        coreId: String,
        romPath: String,
        title: String,
        loadState: Boolean,
        platform: GamePlatform?,
        useLocalSave: Boolean = false,
    ) {
        GameThemeMusic.stop("início de jogo")
        GameLog.log("jogo", "abrir: $title (plataforma=${platform?.slug ?: "?"}, núcleo=$coreId)")
        if (platform != null && platform.launch != LaunchMode.LIBRETRO) {
            status = context.getString(R.string.game_external_opening, title)
            ExternalEmulators.launch(context, LocalGame(title, platform, romPath))
            PlayHistory.markPlayed(context, romPath, System.currentTimeMillis())
            status = ""
            return
        }
        if (platform != null && !BiosManager.isReady(context, platform)) {
            error(context.getString(R.string.game_bios_missing, platform.name))
        }
        // Fingerprint e descoberta de saves legados podem ler arquivos grandes/varrer a biblioteca.
        // Faça tudo fora da interface e entregue o resultado pronto à sessão.
        val (gameIdentity, migrationPlan, siblings) = withContext(Dispatchers.IO) {
            val identity = GameIdentityResolver.resolve(context, File(romPath), platform?.slug)
            val siblings = platform?.let { selectedPlatform ->
                CoreRegistry.cores(selectedPlatform)
            }.orEmpty().map { it.id }
            val plan = GameIdentityResolver.planLegacyMigration(
                context,
                GameFolders.dir(context, FolderKind.Saves),
                File(romPath),
                platform?.slug,
                listOf(coreId) + siblings,
                identity,
            )
            Triple(identity, plan, siblings)
        }
        if (migrationPlan.ambiguous) {
            error(
                "Não foi possível confirmar a qual jogo pertence o save antigo ou se dois conjuntos de progresso são diferentes. " +
                    "Os arquivos foram preservados e o jogo não será iniciado. Conflitos ou jogos com identificação ambígua exigem revisão separada.",
            )
        }
        if (migrationPlan.confirmationSources.isNotEmpty()) {
            pendingLegacySaveClaim = PendingLegacySaveClaim(
                coreId = coreId,
                romPath = romPath,
                title = title,
                loadState = loadState,
                platform = platform,
                gameIdentity = gameIdentity,
                sources = migrationPlan.confirmationSources,
            )
            status = ""
            return
        }
        val migrationSources = migrationPlan.sources
        val core = CoreInstaller.ensureInstalled(context, coreId) { bytes ->
            status = context.getString(R.string.game_mode_status_downloading, coreId, bytes / 1024)
        }.getOrThrow()
        if (RomMSettings.isConnected(context) && !useLocalSave) {
            status = context.getString(R.string.game_mode_status_syncing)
            try {
                // A partida só começa depois que o pull terminou de verdade.
                withContext(Dispatchers.IO) { SaveSync.pull(context, romPath, coreId, platform?.slug, gameIdentity, migrationSources) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                GameLog.error("saves", "falha ao sincronizar antes de abrir ${File(romPath).name}", error)
                SaveSync.markSyncPending(context, romPath, coreId, platform?.slug, gameIdentity)
                pendingSaveSync = PendingSaveSync(
                    coreId = coreId,
                    romPath = romPath,
                    title = title,
                    loadState = loadState,
                    platform = platform,
                    error = error.message ?: error.toString(),
                    localSaveAvailable = error !is SaveRollbackException && withContext(Dispatchers.IO) {
                        val store = SaveStore(
                            context,
                            coreId,
                            File(romPath),
                            siblings,
                            platform?.slug,
                            gameIdentity,
                            migrationSources,
                        )
                        store.readSram() != null || store.hasState()
                    },
                )
                status = ""
                return
            }
        }
        status = context.getString(R.string.game_mode_status_starting, title)
        context.startActivity(GameSessionActivity.intent(context, core.absolutePath, romPath, loadState, platform?.slug, gameIdentity, migrationSources))
        status = ""
    }

    fun confirmLegacySaveClaim(pending: PendingLegacySaveClaim) {
        if (busy) return
        scope.launch {
            busy = true
            runCatching {
                val copied = withContext(Dispatchers.IO) {
                    GameIdentityResolver.claimLegacySaves(
                        context,
                        File(pending.romPath),
                        pending.gameIdentity,
                        pending.sources,
                    )
                }
                check(copied) {
                    "Não foi possível copiar e validar todos os arquivos. As pastas originais foram preservadas e o jogo continua bloqueado."
                }
                GameLog.log(
                    "saves",
                    "usuário confirmou associação de save legado à conta RomM atual; cópia verificada e origem preservada",
                )
                pendingLegacySaveClaim = null
                startGame(
                    pending.coreId,
                    pending.romPath,
                    pending.title,
                    pending.loadState,
                    pending.platform,
                )
            }.onFailure {
                GameLog.error("saves", "falha ao associar save legado à conta atual", it)
                status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString())
            }
            busy = false
        }
    }

    fun continueAfterSyncFailure(pending: PendingSaveSync, useLocalSave: Boolean) {
        pendingSaveSync = null
        scope.launch {
            busy = true
            runCatching {
                startGame(
                    pending.coreId,
                    pending.romPath,
                    pending.title,
                    pending.loadState,
                    pending.platform,
                    useLocalSave = useLocalSave,
                )
            }.onFailure {
                GameLog.error("jogo", "falha ao abrir ${pending.title}", it)
                status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString())
            }
            busy = false
        }
    }

    fun play(game: LocalGame, loadState: Boolean = false) {
        scope.launch {
            busy = true
            runCatching { startGame(CoreRegistry.chosen(context, game.platform), game.path, game.title, loadState, game.platform) }
                .onFailure {
                    GameLog.error("jogo", "falha ao abrir ${game.title}", it)
                    status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString())
                }
            busy = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = StorageAccess.treeUriToPath(uri)
        if (path == null) {
            status = context.getString(R.string.game_mode_status_folder_invalid)
        } else {
            GameLibrarySettings.setRootPath(context, path)
            rootPath = path
            status = ""
        }
    }

    val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                status = context.getString(R.string.game_mode_status_importing)
                val rom = RomLauncher.importRom(context, uri)
                val coreId = checkNotNull(RomLauncher.coreFor(context, rom.name)) {
                    context.getString(R.string.game_mode_status_unsupported, rom.name)
                }
                startGame(coreId, rom.absolutePath, rom.name, false, GamePlatforms.resolve(emptyList(), rom.extension))
            }.onFailure { status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString()) }
            busy = false
        }
    }

    val needsAccess = !hasAccess && !GameLibrarySettings.isInsideApp(context, rootPath)

    val selectTab: (GameTab) -> Unit = {
        tab = it
        showBios = false
        showEmulators = false
        showFolders = false
        showCores = false
        showScraper = false
        showServer = false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onStepKeys(Key.ButtonL1, Key.ButtonR1) { step ->
                if (detail == null) selectTab(cycle(GameTab.entries, tab, step))
            }
            .background(Brush.verticalGradient(listOf(GameColors.backgroundTop, GameColors.background)))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val current = detail
        if (current == null) {
            TopBar(
                selected = tab,
                onSelect = selectTab,
            )
        }
        val subScreen = showBios || showEmulators || showFolders || showCores || showScraper || showServer
        val depth = if (current != null || subScreen) 1 else 0
        val screenKey = when {
            current != null -> "detail:${current.key}"
            tab == GameTab.Settings && showBios -> "bios"
            tab == GameTab.Settings && showEmulators -> "emulators"
            tab == GameTab.Settings && showFolders -> "folders"
            tab == GameTab.Settings && showCores -> "cores"
            tab == GameTab.Settings && showScraper -> "scraper"
            tab == GameTab.Settings && showServer -> "server"
            else -> "tab:${tab.name}"
        }
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = ScreenState(screenKey, depth),
                transitionSpec = {
                    when {
                        targetState.depth > initialState.depth ->
                            (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220)))
                                .togetherWith(slideOutHorizontally(tween(220)) { -it / 8 } + fadeOut(tween(150)))

                        targetState.depth < initialState.depth ->
                            (slideInHorizontally(tween(220)) { -it / 4 } + fadeIn(tween(220)))
                                .togetherWith(slideOutHorizontally(tween(220)) { it / 8 } + fadeOut(tween(150)))

                        else -> fadeIn(tween(180)).togetherWith(fadeOut(tween(140)))
                    }
                },
                label = "gameScreen",
            ) { targetScreen ->
                val targetDetail = targetScreen.key
                    .takeIf { it.startsWith("detail:") }
                    ?.removePrefix("detail:")
                    ?.let { key -> entries.firstOrNull { it.key == key } }
                when {
                    targetDetail != null -> GameDetailScreen(
                        entry = targetDetail,
                        progress = remote.progress,
                        syncVersion = refreshKey,
                        busy = busy,
                        onPlay = { play(it) },
                        onLoad = { play(it, loadState = true) },
                        onDownload = { remote.download(it, File(rootPath)) { scanTick++ } },
                        onBack = { detailKey = null },
                    )

                    targetScreen.key == "tab:Library" -> LibraryScreen(
                        games = games,
                        entries = entries,
                        androidGames = androidGames,
                        progress = remote.progress,
                        version = version,
                        syncVersion = refreshKey,
                        busy = busy,
                        onPlay = { play(it) },
                        onOpen = { detailKey = it.key },
                        onLaunchAndroid = { game ->
                            if (!AndroidGames.launch(context, game)) {
                                status = context.getString(R.string.game_mode_status_error, game.title)
                            }
                        },
                        onRefresh = {
                            scanTick++
                            remote.requestRefresh()
                        },
                    )

                    targetScreen.key == "bios" -> BiosScreen(rootPath = rootPath)

                    targetScreen.key == "emulators" -> EmulatorsScreen()

                    targetScreen.key == "folders" -> FoldersScreen()

                    targetScreen.key == "cores" -> CoresScreen()

                    targetScreen.key == "scraper" -> ScraperScreen()

                    targetScreen.key == "server" -> ServerScreen(remote)

                    targetScreen.key == "tab:Settings" -> SettingsScreen(
                        rootPath = rootPath,
                        needsAccess = needsAccess,
                        busy = busy,
                        onChooseFolder = { folderPicker.launch(null) },
                        onRefresh = { scanTick++ },
                        onGrantAccess = { context.startActivity(StorageAccess.settingsIntent(context)) },
                        onPickRom = { romPicker.launch(arrayOf("*/*")) },
                        onOpenBios = { showBios = true },
                        onOpenEmulators = { showEmulators = true },
                        onOpenFolders = { showFolders = true },
                        onOpenCores = { showCores = true },
                        onOpenScraper = { showScraper = true },
                        onOpenServer = { showServer = true },
                        onExit = onExit,
                    )

                    else -> AchievementsScreen()
                }
            }
        }
        if (remote.refreshing) {
            Text(
                text = stringResource(R.string.game_romm_updating),
                color = GameColors.textDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
        if (remote.error.isNotEmpty()) {
            Text(
                text = remote.error,
                color = GameColors.danger,
                fontSize = 12.sp,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
        if (status.isNotEmpty()) {
            Text(
                text = status,
                color = GameColors.textDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        HintBar(inDetail = current != null, tab = tab)
    }
    pendingSaveSync?.let { pending ->
        SaveSyncFailureDialog(
            error = pending.error,
            localSaveAvailable = pending.localSaveAvailable,
            busy = busy,
            onRetry = { continueAfterSyncFailure(pending, useLocalSave = false) },
            onUseLocal = { continueAfterSyncFailure(pending, useLocalSave = true) },
        )
    }
    pendingLegacySaveClaim?.let { pending ->
        LegacySaveClaimDialog(
            title = pending.title,
            busy = busy,
            onConfirm = { confirmLegacySaveClaim(pending) },
            onCancel = {
                pendingLegacySaveClaim = null
                status = "Os saves antigos foram preservados. O jogo não foi iniciado."
            },
        )
    }
}

@Composable
private fun SaveSyncFailureDialog(
    error: String,
    localSaveAvailable: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onUseLocal: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize().background(GameColors.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(GameColors.backgroundTop, RoundedCornerShape(14.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.game_save_sync_failed_title),
                color = GameColors.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (localSaveAvailable) {
                    stringResource(R.string.game_save_sync_failed_body, error)
                } else {
                    stringResource(R.string.game_save_sync_unsafe_body)
                },
                color = GameColors.textDim,
                fontSize = 15.sp,
            )
            GameButton(
                stringResource(R.string.game_save_sync_retry),
                onRetry,
                primary = true,
                enabled = !busy,
                modifier = Modifier.focusRequester(retryFocus),
            )
            if (localSaveAvailable) {
                GameButton(
                    stringResource(R.string.game_save_sync_use_local),
                    onUseLocal,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun LegacySaveClaimDialog(
    title: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val safeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { safeFocus.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize().background(GameColors.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(GameColors.backgroundTop, RoundedCornerShape(14.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.game_save_claim_title),
                color = GameColors.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.game_save_claim_body, title),
                color = GameColors.textDim,
                fontSize = 15.sp,
            )
            GameButton(
                stringResource(R.string.game_save_claim_cancel),
                onCancel,
                primary = true,
                enabled = !busy,
                modifier = Modifier.focusRequester(safeFocus),
            )
            GameButton(
                stringResource(R.string.game_save_claim_confirm),
                onConfirm,
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun PadLostDialog(onStay: () -> Unit, onLeave: () -> Unit) {
    val stayFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { stayFocus.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize().background(GameColors.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(GameColors.backgroundTop, RoundedCornerShape(14.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.game_pad_disconnected_title), color = GameColors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.game_pad_disconnected_body), color = GameColors.textDim, fontSize = 15.sp)
            GameButton(stringResource(R.string.game_pad_stay), onStay, primary = true, modifier = Modifier.focusRequester(stayFocus))
            GameButton(stringResource(R.string.game_mode_exit), onLeave)
        }
    }
}

@Composable
private fun TopBar(selected: GameTab, onSelect: (GameTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tandem",
            color = GameColors.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp),
        )
        GameTab.entries.forEach { entry ->
            FocusSurface(
                onClick = { onSelect(entry) },
                background = if (entry == selected) GameColors.surfaceHigh else GameColors.background.copy(alpha = 0f),
            ) {
                Text(
                    text = stringResource(entry.label),
                    color = if (entry == selected) GameColors.text else GameColors.textDim,
                    fontSize = 14.sp,
                    fontWeight = if (entry == selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HintBar(inDetail: Boolean, tab: GameTab) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.bar)
            .padding(horizontal = 24.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Hint("A", stringResource(R.string.game_hint_select))
        Hint("B", stringResource(R.string.game_hint_back))
        if (!inDetail) {
            Hint("L1 R1", stringResource(R.string.game_hint_tabs))
            if (tab == GameTab.Library) {
                Hint("L2 R2", stringResource(R.string.game_hint_consoles))
                Hint("Y", stringResource(R.string.game_hint_filter_list))
                Hint("X", stringResource(R.string.game_hint_refresh))
            }
        }
    }
}

@Composable
private fun Hint(button: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = button, color = GameColors.text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(text = label, color = GameColors.textDim, fontSize = 12.sp)
    }
}
