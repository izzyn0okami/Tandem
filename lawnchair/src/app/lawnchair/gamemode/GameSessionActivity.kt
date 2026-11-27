package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sessão de jogo: roda um núcleo Libretro em tela cheia dentro do próprio APK.
 * M3: save de bateria automático, estado rápido e menu de pausa (Voltar, botão Mode ou Select + Start).
 * Roda no mesmo processo do launcher; separar em processo próprio fica para depois.
 */
class GameSessionActivity : ComponentActivity() {

    private var retroView: GLRetroView? = null
    private var virtualGamepad: VirtualGamepadView? = null
    private var saveStore: SaveStore? = null
    private var menuOpen by mutableStateOf(false)

    /** Loop que processa eventos pendentes das conquistas enquanto o jogo está pausado. */
    private var raIdleJob: Job? = null

    /** Camada de avisos e menu por cima do jogo: com o menu fechado, o toque passa direto para o jogo (DS, 3DS). */
    private fun passThrough(content: View): View = object : FrameLayout(this) {
        init {
            addView(content)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = menuOpen && super.dispatchTouchEvent(ev)
    }
    private var selectDown = false
    private var startDown = false
    private var resumedAtMs = 0L
    private lateinit var lostWatcher: ControllerLostWatcher
    private var romPath: String? = null
    private var gameIdentity: String? = null
    private var raSession: RaSession? = null
    private var nativeDestroyed = false
    private val raUnlockQueue = mutableStateListOf<RaAchievement>()
    private val raNoticeQueue = mutableStateListOf<String>()
    private var raEntries by mutableStateOf<List<RaEntry>>(emptyList())
    private var raHardcore by mutableStateOf(false)
    private var raSyncPending by mutableStateOf(false)
    private var raRichPresence by mutableStateOf("")
    private var raRichPresenceVisible by mutableStateOf(true)
    private var raListOpen by mutableStateOf(false)

    // Indicadores e avisos extras do overlay de conquistas (padrão RetroAchievements; ver RaBadge.kt).
    private val raChallengeIndicators = mutableStateMapOf<Long, RaChallengeIndicator>()
    private var raProgressIndicator by mutableStateOf<RaProgressIndicator?>(null)
    private val raLeaderboardTrackers = mutableStateMapOf<Long, RaLeaderboardTracker>()
    private val raMasteryQueue = mutableStateListOf<RaMastery>()
    private var optionsOpen by mutableStateOf(false)
    private var coreOptions by mutableStateOf<List<CoreOption>>(emptyList())
    private var coreLog: Closeable? = null
    private var coreIdForOptions = ""
    private var coreSupportsStates = false
    private var coreSupportsRomMSaveSync = false
    private var sramLoadFailureLogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        raRichPresenceVisible = GameModeSettings.isRaRichPresenceVisible(this)

        // O Context da Activity só é válido após super.onCreate().
        // Inicializar aqui evita a NPE ao construir a Activity antes do attach.
        lostWatcher = ControllerLostWatcher(applicationContext) {
            if (!menuOpen) {
                setMenu(true)
                Toast.makeText(this, R.string.game_session_pad_disconnected, Toast.LENGTH_SHORT).show()
            }
        }

        val corePath = intent.getStringExtra(EXTRA_CORE_PATH)
        val romPath = intent.getStringExtra(EXTRA_ROM_PATH)
        val stableIdentity = intent.getStringExtra(EXTRA_GAME_IDENTITY)
        if (corePath == null || romPath == null || stableIdentity.isNullOrBlank()) {
            finish()
            return
        }
        this.romPath = romPath
        gameIdentity = stableIdentity
        GameSessionCoordinator.markSessionActive(this, romPath, intent.getStringExtra(EXTRA_PLATFORM), stableIdentity)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val coreId = CoreInstaller.coreIdFromFileName(File(corePath).name)
        val platformSlug = intent.getStringExtra(EXTRA_PLATFORM)
        val siblings = platformSlug?.let(GamePlatforms::bySlug)?.let { CoreRegistry.cores(it) }.orEmpty().map { it.id }
        val legacyCores = intent.getStringArrayListExtra(EXTRA_MIGRATION_CORES).orEmpty()
        val legacyPaths = intent.getStringArrayListExtra(EXTRA_MIGRATION_PATHS).orEmpty()
        val migrationSources = legacyCores.zip(legacyPaths).associate { (core, path) -> core to File(path) }
        val store = SaveStore(
            this,
            coreId,
            File(romPath),
            siblings,
            platformSlug,
            stableIdentity,
            migrationSources,
        )
        saveStore = store
        coreIdForOptions = coreId
        logArtifactInventory("início da sessão")
        SessionDiagnostics.sessionStarted(this, coreId, romPath, platformSlug)
        coreLog = SessionDiagnostics.startCoreLog()

        val platformObject = platformSlug?.let(GamePlatforms::bySlug)
        val coreInfo = platformObject?.let { CoreRegistry.info(it, coreId) }
        coreSupportsStates = coreInfo?.supportsStates ?: false
        coreSupportsRomMSaveSync = coreInfo?.supportsRomMSaveSync ?: false
        // Calculado uma vez, sem depender de rede: o menu de pausa usa isso para travar "Carregar estado"
        // desde o primeiro frame, sem esperar a sessão do RetroAchievements (que é assíncrona) confirmar.
        val statesBlocked = platformObject != null && RaSettings.blocksStates(this, platformObject)
        // Reflete o modo escolhido desde o início, antes do login/identificação assíncronos do rc_client.
        raHardcore = statesBlocked
        if (platformObject?.raConsoleId != null && RaSettings.isLoggedIn(this)) {
            raNoticeQueue.add(getString(if (statesBlocked) R.string.game_ra_hardcore_label else R.string.game_ra_softcore_label))
        }
        // Núcleos como N64 e DS não abrem .zip: extrai antes (os saves continuam com o nome do .zip).
        val loadPath = try {
            RomUnzipper.prepare(this, romPath, platformObject).absolutePath
        } catch (error: IllegalArgumentException) {
            Toast.makeText(
                this,
                error.message ?: "Não foi possível preparar este arquivo de jogo.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        val effectiveSavesDirectory = GameFolders.dir(this, FolderKind.Saves)
        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            gameFilePath = loadPath
            systemDirectory = GameFolders.dir(this@GameSessionActivity, FolderKind.Bios).absolutePath
            savesDirectory = if (coreId in setOf("desmume", "desmume2015")) {
                store.coreSaveDirectory.absolutePath
            } else {
                effectiveSavesDirectory.absolutePath
            }
            preferVulkan = coreInfo?.preferVulkan ?: GameModeSettings.isVulkanPreferred(this@GameSessionActivity)
            vulkanDriver = if (GameModeSettings.isExternalVulkanDriverEnabled(this@GameSessionActivity)) {
                GameModeSettings.vulkanDriver(this@GameSessionActivity)
            } else {
                null
            }
            saveRAMState = store.readSram()
            // Opções que o usuário salvou para este núcleo.
            variables = CoreRegistry.variables(this@GameSessionActivity, coreId)
        }
        SavePathDiagnostics.log(
            context = this,
            coreId = coreId,
            platformSlug = platformSlug,
            romPath = romPath,
            gameIdentity = stableIdentity,
            effectiveDirectory = if (coreId in setOf("desmume", "desmume2015")) store.coreSaveDirectory else effectiveSavesDirectory,
            saveStore = store,
        )

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)
        val gamepad = VirtualGamepadView(this, retroView = view)
        virtualGamepad = gamepad
        val sessionHost = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Always let the overlay classify each pointer, even when an earlier finger
                // started on the DS touchscreen. It forwards only non-control pointers to GLRetroView.
                if (gamepad.dispatchTouchEvent(event)) return true
                return view.dispatchTouchEvent(event)
            }
        }.apply {
            addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(gamepad, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(sessionHost)

        lifecycleScope.launch {
            view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            if (view.sramLoadFailed) {
                Toast.makeText(
                    this@GameSessionActivity,
                    R.string.game_session_sram_load_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        if (coreId == "melonds") {
            lifecycleScope.launch {
                view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
                applyDsSideBySide(view)
            }
        }

        val platform = platformObject
        if (platform?.raConsoleId != null && RaSettings.isLoggedIn(this)) {
            lifecycleScope.launch {
                // O nome do núcleo e suas opções só ficam disponíveis depois que o primeiro frame
                // inicializa o core. Verifique opções salvas antes de ativar Hardcore.
                view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
                val hasDisallowedSetting = RaSettings.hardcore(this@GameSessionActivity) &&
                    view.getVariables().any { variable ->
                        val key = variable.key
                        val value = variable.value
                        key != null && value != null && !view.isAchievementsCoreSettingAllowed(key, value)
                    }
                val sessionHardcore = RaSettings.hardcore(this@GameSessionActivity) && !hasDisallowedSetting
                val progressUser = RaSettings.user(this@GameSessionActivity)
                val savedRaProgress = progressUser?.let { user ->
                    withContext(Dispatchers.IO) { store.readRaProgress(user) }
                }
                if (hasDisallowedSetting) {
                    raHardcore = false
                    raNoticeQueue.add("Hardcore desativado: o núcleo está usando uma opção incompatível.")
                    GameLog.log("conquistas", "Hardcore desativado: há opções do núcleo não permitidas")
                }
                val session = RaSession(
                    context = applicationContext,
                    view = view,
                    platform = platform,
                    romPath = loadPath,
                    cacheKeyPath = romPath,
                    scope = lifecycleScope,
                    hardcore = sessionHardcore,
                    onUnlock = {
                        raUnlockQueue.add(it)
                        if (GameModeSettings.isSoundEnabled(this@GameSessionActivity)) NavSound.unlock()
                    },
                    onState = { entries, hardcore ->
                        raEntries = entries
                        // Hardcore é uma regra da sessão, não depende de haver conquistas listadas.
                        raHardcore = hardcore
                    },
                    onStatus = { status, unlocked, total ->
                        when (status) {
                            RaGameStatus.UNSUPPORTED -> raNoticeQueue.add(getString(R.string.game_ra_status_unsupported))
                            RaGameStatus.NO_ACHIEVEMENTS -> raNoticeQueue.add(getString(R.string.game_ra_status_none))
                            RaGameStatus.TRACKING -> raNoticeQueue.add(getString(R.string.game_ra_status_progress, unlocked, total))
                            RaGameStatus.FEATURES_ONLY -> raNoticeQueue.add(getString(R.string.game_ra_status_features_only))
                        }
                    },
                    onOverlayEvent = { event -> handleRaOverlayEvent(event) },
                    onConnectionState = { connected -> raSyncPending = !connected },
                    onRichPresence = { message -> raRichPresence = message },
                    onGameLoaded = { onRestored ->
                        // Só tenta restaurar quando existe progresso salvo. Sem arquivo, libera
                        // imediatamente a publicação do estado inicial da sessão.
                        if (savedRaProgress == null) {
                            onRestored()
                        } else {
                            // Sempre adia a publicação para depois que o callback nativo de load retornar.
                            view.restoreAchievementsProgressAsync(savedRaProgress) { restored ->
                                if (restored == false) {
                                    progressUser?.let(store::deleteRaProgress)
                                    GameLog.log("conquistas", "progresso local incompatível; arquivo descartado")
                                }
                                onRestored()
                            }
                        }
                    },
                )
                raSession = session
                session.start()
            }
        }

        addContentView(
            passThrough(
                ComposeView(this).apply {
                    setContent {
                        LawnchairTheme {
                            // Avisos temporários são serializados para nunca se sobreporem na tela.
                            val unlocked = raUnlockQueue.firstOrNull()
                            val mastery = raMasteryQueue.firstOrNull()
                            val notice = raNoticeQueue.firstOrNull()
                            when {
                                unlocked != null -> AchievementOverlay(unlocked, hardcore = raHardcore) {
                                    if (raUnlockQueue.firstOrNull()?.id == unlocked.id) raUnlockQueue.removeAt(0)
                                }

                                mastery != null -> MasteryOverlay(
                                    mastery = mastery,
                                    playtimeLabel = formatPlaytime(PlayHistory.get(this@GameSessionActivity, romPath).playtimeMs),
                                    userName = RaSettings.user(this@GameSessionActivity).orEmpty(),
                                ) {
                                    if (raMasteryQueue.firstOrNull() == mastery) raMasteryQueue.removeAt(0)
                                }

                                notice != null -> GameMessageOverlay(notice) {
                                    if (raNoticeQueue.firstOrNull() == notice) raNoticeQueue.removeAt(0)
                                }
                            }
                            // Indicadores persistentes seguem as posições e regras recomendadas pelo RA.
                            ChallengeIndicatorsRow(raChallengeIndicators.values.toList())
                            raProgressIndicator?.let { ProgressIndicatorBadge(it) }
                            LeaderboardTrackersColumn(raLeaderboardTrackers.values.toList())
                            if (raRichPresenceVisible) RichPresenceOverlay(raRichPresence)
                            RaSyncIndicator(raSyncPending)
                            if (menuOpen) {
                                val progress = if (raEntries.isEmpty()) {
                                    null
                                } else {
                                    getString(R.string.game_ra_progress, raEntries.count { it.unlocked }, raEntries.size) +
                                        if (raHardcore) " · " + getString(R.string.game_ra_hardcore_label) else ""
                                }
                                PauseMenu(
                                    title = File(romPath).nameWithoutExtension,
                                    subtitle = progress,
                                    canSave = true,
                                    canLoad = store.hasState() && !raHardcore,
                                    supportsStates = coreSupportsStates,
                                    hasAchievements = raEntries.isNotEmpty(),
                                    raRichPresenceVisible = raRichPresenceVisible,
                                    onToggleRichPresence = {
                                        raRichPresenceVisible = !raRichPresenceVisible
                                        GameModeSettings.setRaRichPresenceVisible(this@GameSessionActivity, raRichPresenceVisible)
                                    },
                                    onAchievements = { raListOpen = true },
                                    onCoreOptions = { openCoreOptions() },
                                    onResume = { setMenu(false) },
                                    onSave = { saveQuickState() },
                                    onLoad = { loadQuickState() },
                                    onReset = {
                                        view.reset()
                                        setMenu(false)
                                    },
                                    onExit = { exitGame() },
                                )
                            }
                            if (menuOpen && optionsOpen) {
                                CoreOptionsPanel(
                                    coreLabel = coreLabel(),
                                    options = coreOptions,
                                    onChange = { option, value -> changeCoreOption(option, value) },
                                    onReset = {
                                        CoreRegistry.resetOptions(this@GameSessionActivity, coreIdForOptions)
                                        Toast.makeText(this@GameSessionActivity, R.string.game_core_options_reset_done, Toast.LENGTH_LONG).show()
                                    },
                                )
                            }
                            if (menuOpen && raListOpen) {
                                AchievementListPanel(
                                    title = File(romPath).nameWithoutExtension,
                                    entries = raEntries,
                                    hardcore = raHardcore,
                                )
                            }
                        }
                    }
                },
            ),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeTopPanelOr { setMenu(!menuOpen) }
                }
            },
        )

        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                GameLog.log("motor", "erro do motor (código $code) no núcleo $coreIdForOptions")
                Toast.makeText(this@GameSessionActivity, getString(R.string.game_session_engine_error, code), Toast.LENGTH_LONG).show()
                finish()
            }
        }

        if (intent.getBooleanExtra(EXTRA_LOAD_STATE, false) && store.hasState() && !statesBlocked) {
            lifecycleScope.launch {
                view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
                loadQuickState()
            }
        }

        // Autosave do save de bateria a cada minuto enquanto o jogo está na tela.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(SRAM_AUTOSAVE_MS)
                    romPath?.let { GameSessionCoordinator.markSessionActive(this@GameSessionActivity, it, intent.getStringExtra(EXTRA_PLATFORM), gameIdentity) }
                    withContext(Dispatchers.Default) { persistSram() }
                }
            }
        }
    }

    /** Traduz um evento de overlay do rc_client (ver RetroAchievements.kt) no estado que a tela desenha. */
    private fun handleRaOverlayEvent(event: RaOverlayEvent) {
        when (event) {
            is RaOverlayEvent.ChallengeShow -> raChallengeIndicators[event.indicator.id] = event.indicator

            is RaOverlayEvent.ChallengeHide -> raChallengeIndicators.remove(event.id)

            is RaOverlayEvent.Progress -> raProgressIndicator = event.indicator

            RaOverlayEvent.ProgressHide -> raProgressIndicator = null

            is RaOverlayEvent.LeaderboardMessage -> raNoticeQueue.add(event.text)

            is RaOverlayEvent.Tracker -> raLeaderboardTrackers[event.tracker.id] = event.tracker

            is RaOverlayEvent.TrackerHide -> raLeaderboardTrackers.remove(event.id)

            is RaOverlayEvent.Mastery -> {
                raMasteryQueue.add(event.info)
                if (GameModeSettings.isSoundEnabled(this)) NavSound.mastery()
            }
        }
    }

    /** "1h30min" ou "12 min" — usado só na legenda do aviso de Mastered/Completed. */
    private fun formatPlaytime(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) getString(R.string.game_ra_playtime_hm, hours, minutes) else getString(R.string.game_ra_playtime_m, minutes)
    }

    private fun setMenu(open: Boolean) {
        if (open == menuOpen) return
        // Hardcore: evita pausas seguidas que poderiam burlar condições de conquista.
        if (open && raHardcore && !(retroView?.canPauseAchievements() ?: false)) {
            raNoticeQueue.add("Aguarde alguns instantes antes de pausar novamente")
            return
        }
        menuOpen = open
        virtualGamepad?.setMenuOpen(open)
        if (!open) {
            optionsOpen = false
            raListOpen = false
        }
        if (open) {
            retroView?.pauseEmulation()
            startRaIdleLoop()
        } else {
            stopRaIdleLoop()
            retroView?.resumeEmulation()
        }
    }

    /** Enquanto pausado, o rc_client precisa de idle (no lugar de do_frame) para manter a fila viva. */
    private fun startRaIdleLoop() {
        if (raSession == null) return
        raIdleJob?.cancel()
        raIdleJob = lifecycleScope.launch {
            while (isActive) {
                // runOnGLThread aguarda o GL thread; não bloquear a thread principal a cada segundo.
                withContext(Dispatchers.Default) { retroView?.idleAchievements() }
                delay(1_000)
            }
        }
    }

    private fun stopRaIdleLoop() {
        raIdleJob?.cancel()
        raIdleJob = null
    }

    // Serializa o autosave periódico com a persistência disparada por pausa/encerramento.
    @Synchronized
    private fun persistSram() {
        val view = retroView ?: return
        val store = saveStore ?: return
        if (!GameSessionSavePolicy.shouldPersistSram(view.sramLoadFailed)) {
            if (!sramLoadFailureLogged) {
                GameLog.log("saves", "o núcleo rejeitou o SRAM existente; persistência bloqueada para preservar o arquivo original")
                sramLoadFailureLogged = true
            }
            return
        }
        runCatching {
            val bytes = view.serializeSRAM()
            if (bytes.isNotEmpty()) {
                val changed = store.writeSramIfChanged(bytes)
                if (changed) {
                    if (coreSupportsRomMSaveSync) {
                        romPath?.let { path -> coreIdOf()?.let { GameSessionCoordinator.markPending(this, path, it, intent.getStringExtra(EXTRA_PLATFORM), gameIdentity) } }
                        GameLog.log("saves", "SRAM alterado pelo núcleo e persistido; sincronização agendada")
                    } else {
                        GameLog.log("saves", "SRAM persistido localmente; este núcleo usa armazenamento próprio e não sincroniza saves pelo RomM")
                    }
                } else {
                    GameLog.log("saves", "SRAM inalterado após serialização do núcleo; gravação e upload desnecessários ignorados")
                }
            } else {
                GameLog.log("saves", "núcleo retornou SRAM vazia; arquivo local existente não foi alterado")
            }
        }.onFailure { GameLog.error("saves", "falha ao serializar ou persistir SRAM", it) }
        val user = RaSettings.user(this) ?: return
        if (raSession != null) {
            runCatching {
                val progress = view.serializeAchievementsProgress()
                if (progress != null && progress.isNotEmpty()) store.writeRaProgress(user, progress)
            }.onFailure { GameLog.error("conquistas", "falha ao salvar progresso local", it) }
        }
    }

    /** Registra somente metadados do inventário local; nunca registra conteúdo ou credenciais. */
    private fun logArtifactInventory(trigger: String) {
        val store = saveStore ?: return
        runCatching {
            val artifacts = store.inventory(RaSettings.user(this))
            val summary = artifacts.joinToString(separator = "; ") { artifact ->
                "${artifact.type}/${artifact.slot} ${artifact.format} ${artifact.sizeBytes}B sha256=${artifact.sha256.take(12)}"
            }.ifEmpty { "vazio" }
            GameLog.log(
                "inventário",
                "$trigger: jogo=${gameIdentity ?: "—"}, núcleo=${coreIdOf() ?: "—"}, artefatos=${artifacts.size} [$summary]",
            )
        }.onFailure { GameLog.error("inventário", "falha ao inspecionar artefatos locais", it) }
    }

    private fun saveQuickState() {
        if (!coreSupportsStates) {
            toast(R.string.game_session_state_save_failed)
            setMenu(false)
            return
        }
        val view = retroView ?: return
        val store = saveStore ?: return
        val saved = runCatching {
            val (emuState, raProgress) = view.serializeStateWithAchievements()
            if (emuState == null) {
                GameLog.log("saves", "núcleo não forneceu estado rápido serializável")
                return@runCatching false
            }
            val payload = QuickStateEnvelope.encode(emuState, raProgress)
            store.writeState(payload)
            logArtifactInventory("estado rápido salvo")
            if (coreSupportsRomMSaveSync) {
                romPath?.let { path -> coreIdOf()?.let { GameSessionCoordinator.markPending(this, path, it, intent.getStringExtra(EXTRA_PLATFORM), gameIdentity) } }
            }
            true
        }.onFailure { GameLog.error("saves", "falha ao criar estado rápido", it) }.getOrDefault(false)
        toast(if (saved) R.string.game_session_state_saved else R.string.game_session_state_save_failed)
        setMenu(false)
    }

    private fun loadQuickState() {
        // Defesa adicional: nenhum caminho de chamada pode carregar estados em núcleos incompatíveis ou durante o Hardcore.
        if (!coreSupportsStates || raHardcore) {
            toast(R.string.game_session_state_load_failed)
            return
        }
        val view = retroView ?: return
        val payload = runCatching { saveStore?.readState() }
            .onFailure { GameLog.error("saves", "falha ao ler arquivo de estado rápido", it) }
            .getOrNull()
        if (payload == null) {
            GameLog.log("saves", "carregamento solicitado, mas não há estado rápido legível")
            toast(R.string.game_session_state_load_failed)
            setMenu(false)
            return
        }
        val decoded = QuickStateEnvelope.decode(payload)
        if (decoded == null) {
            GameLog.log("saves", "estado rápido rejeitado: envelope TNDM truncado ou com comprimentos inválidos (total=${payload.size})")
            toast(R.string.game_session_state_load_failed)
            setMenu(false)
            return
        }
        val loaded = runCatching {
            view.unserializePersistedStateWithAchievements(decoded.emulatorState, decoded.achievementsProgress)
        }
            .onFailure { GameLog.error("saves", "falha ao carregar estado rápido", it) }
            .getOrDefault(false)
        if (!loaded) GameLog.log("saves", "núcleo recusou o estado rápido; sessão continuou sem restaurá-lo")
        toast(if (loaded) R.string.game_session_state_loaded else R.string.game_session_state_load_failed)
        setMenu(false)
    }

    private fun exitGame() {
        persistSram()
        finish()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        // A Activity restaura o estado do ciclo de vida automaticamente; preserve o menu pausado
        // antes que o observer do GLRetroView tente retomar a emulação.
        romPath?.let { GameSessionCoordinator.markSessionActive(this, it, intent.getStringExtra(EXTRA_PLATFORM), gameIdentity) }
        retroView?.suppressAutoResume = menuOpen
        super.onResume()
        retroView?.suppressAutoResume = false
        if (menuOpen) startRaIdleLoop() else stopRaIdleLoop()
        resumedAtMs = SystemClock.elapsedRealtime()
        lostWatcher.start()
    }

    override fun onDestroy() {
        virtualGamepad?.releaseAll()
        stopRaIdleLoop()
        if (!nativeDestroyed) {
            raSession?.stop()
            retroView?.destroyNative()
            nativeDestroyed = true
        }
        coreLog?.close()
        if (GameSessionLifecyclePolicy.shouldLogNormalEnd(isFinishing)) SessionDiagnostics.sessionEnded(this, "encerrado normalmente")
        super.onDestroy()
    }

    /** Fecha a lista de opções ou a de conquistas, se alguma estiver aberta; senão faz [otherwise]. */
    private fun closeTopPanelOr(otherwise: () -> Unit) {
        when {
            optionsOpen -> optionsOpen = false
            raListOpen -> raListOpen = false
            else -> otherwise()
        }
    }

    private fun coreLabel(): String {
        val platform = intent.getStringExtra(EXTRA_PLATFORM)?.let(GamePlatforms::bySlug)
        return platform?.let { CoreRegistry.cores(it).firstOrNull { core -> core.id == coreIdForOptions }?.label } ?: coreIdForOptions
    }

    /** DS: as duas telas lado a lado cabem melhor no celular em paisagem. Só usa valores que o núcleo declara. */
    private fun applyDsSideBySide(view: GLRetroView) {
        if (CoreRegistry.savedOptions(this, "melonds").containsKey("melonds_screen_layout")) return
        val option = CoreRegistry.parse(view.getVariables()).firstOrNull { it.key == "melonds_screen_layout" } ?: return
        val sideBySide = option.values.firstOrNull { it.contains("Left", ignoreCase = true) && it.contains("Right", ignoreCase = true) } ?: return
        view.updateVariables(Variable(key = option.key, value = sideBySide))
        GameLog.log("núcleos", "DS: telas lado a lado ($sideBySide)")
    }

    private fun openCoreOptions() {
        coreOptions = retroView?.let { CoreRegistry.parse(it.getVariables()) }.orEmpty()
        GameLog.log("núcleos", "opções abertas: ${coreOptions.size} no núcleo $coreIdForOptions")
        optionsOpen = true
    }

    private fun changeCoreOption(option: CoreOption, value: String) {
        val view = retroView ?: return
        if (raHardcore && !view.isAchievementsCoreSettingAllowed(option.key, value)) {
            raNoticeQueue.add("Esta opção não é permitida no modo Hardcore.")
            return
        }
        view.updateVariables(Variable(key = option.key, value = value))
        CoreRegistry.saveOption(this, coreIdForOptions, option.key, value)
        coreOptions = coreOptions.map { if (it.key == option.key) it.copy(current = value) else it }
        GameLog.log("núcleos", "$coreIdForOptions: ${option.key} = $value")
    }

    private fun coreIdOf(): String? = intent.getStringExtra(EXTRA_CORE_PATH)?.let { File(it).name.removeSuffix("_libretro_android.so") }

    override fun onPause() {
        virtualGamepad?.releaseAll()
        // Antes do super: a superfície de vídeo ainda está ativa e consegue entregar o save.
        persistSram()
        logArtifactInventory("pausa/encerramento da sessão")
        romPath?.let {
            val elapsed = SystemClock.elapsedRealtime() - resumedAtMs
            if (resumedAtMs > 0) PlayHistory.addSession(this, it, elapsed, System.currentTimeMillis())
        }
        resumedAtMs = 0L
        lostWatcher.stop()
        // Ao encerrar ou recriar a Activity, descarte o cliente RA e o núcleo antes de o
        // GLSurfaceView suspender o GL thread. Em uma pausa comum, preserve a sessão.
        if (GameSessionLifecyclePolicy.shouldDestroyNativeOnPause(isFinishing, isChangingConfigurations)) {
            stopRaIdleLoop()
            raSession?.stop()
            retroView?.destroyNative()
            nativeDestroyed = true
        }
        super.onPause()
        // Só libere e agende o upload depois que o GL foi pausado e o save local persistido.
        romPath?.let { path ->
            GameSessionCoordinator.markSessionInactive(this, path, intent.getStringExtra(EXTRA_PLATFORM), gameIdentity)
            if (GameSessionLifecyclePolicy.shouldEnqueuePostSessionSync(coreSupportsRomMSaveSync, path.isNotBlank(), retroView?.sramLoadFailed == true)) {
                coreIdOf()?.let { GameSessionCoordinator.enqueuePostSessionSync(this, path, it, intent.getStringExtra(EXTRA_PLATFORM), gameIdentity) }
            }
        }
        // Em uma pausa comum, o idle é retomado em onResume; respostas de rede aguardam o GL.
        stopRaIdleLoop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Controles sem botão Mode (a maioria dos genéricos): Select + Start juntos abrem o menu.
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectDown = isDown || (event.action != KeyEvent.ACTION_UP && selectDown)
            KeyEvent.KEYCODE_BUTTON_START -> startDown = isDown || (event.action != KeyEvent.ACTION_UP && startDown)
        }
        if (isDown && event.repeatCount == 0 && selectDown && startDown &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || event.keyCode == KeyEvent.KEYCODE_BUTTON_START)
        ) {
            setMenu(!menuOpen)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) setMenu(!menuOpen)
            return true
        }
        if (menuOpen) {
            return when (event.keyCode) {
                // No menu, A confirma e B fecha, como em qualquer console.
                KeyEvent.KEYCODE_BUTTON_A -> super.dispatchKeyEvent(KeyEvent(event.action, KeyEvent.KEYCODE_DPAD_CENTER))

                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP) closeTopPanelOr { setMenu(false) }
                    true
                }

                else -> super.dispatchKeyEvent(event)
            }
        }
        val view = retroView
        val isGameInput = event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_JOYSTICK) != 0
        if (view != null && isGameInput && event.keyCode != KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> view.onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> view.onKeyUp(event.keyCode, event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!menuOpen && retroView?.onGenericMotionEvent(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
        private const val EXTRA_CORE_PATH = "core_path"
        private const val EXTRA_ROM_PATH = "rom_path"
        private const val EXTRA_LOAD_STATE = "load_state"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_GAME_IDENTITY = "game_identity"
        private const val EXTRA_MIGRATION_CORES = "migration_cores"
        private const val EXTRA_MIGRATION_PATHS = "migration_paths"
        private const val RA_TOAST_MS = 4_000L
        private const val SRAM_AUTOSAVE_MS = 60_000L

        fun intent(
            context: Context,
            corePath: String,
            romPath: String,
            loadState: Boolean = false,
            platformSlug: String? = null,
            gameIdentity: String,
            migrationSources: Map<String, File>,
        ): Intent = Intent(context, GameSessionActivity::class.java)
            .putExtra(EXTRA_CORE_PATH, corePath)
            .putExtra(EXTRA_ROM_PATH, romPath)
            .putExtra(EXTRA_LOAD_STATE, loadState)
            .putExtra(EXTRA_PLATFORM, platformSlug)
            .putExtra(EXTRA_GAME_IDENTITY, gameIdentity)
            .putStringArrayListExtra(EXTRA_MIGRATION_CORES, ArrayList(migrationSources.keys))
            .putStringArrayListExtra(EXTRA_MIGRATION_PATHS, ArrayList(migrationSources.values.map(File::getAbsolutePath)))
    }
}

@Composable
private fun PauseMenu(
    title: String,
    subtitle: String?,
    canSave: Boolean,
    canLoad: Boolean,
    supportsStates: Boolean,
    hasAchievements: Boolean,
    raRichPresenceVisible: Boolean,
    onToggleRichPresence: () -> Unit,
    onAchievements: () -> Unit,
    onCoreOptions: () -> Unit,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocus() }
    Box(modifier = Modifier.fillMaxSize().background(GameColors.scrim)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(340.dp)
                .background(GameColors.panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.game_session_paused).uppercase(),
                color = GameColors.textDim,
                fontSize = 13.sp,
            )
            Text(
                text = title,
                color = GameColors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier.padding(bottom = if (subtitle == null) 8.dp else 2.dp),
            )
            if (subtitle != null) {
                Text(text = subtitle, color = GameColors.success, fontSize = 14.sp, modifier = Modifier.padding(bottom = 14.dp))
            }
            GameButton(
                if (raRichPresenceVisible) "Ocultar descrição da partida" else "Exibir descrição da partida",
                onToggleRichPresence,
            )
            GameButton(stringResource(R.string.game_session_resume), onResume, primary = true, modifier = Modifier.focusRequester(firstItem))
            if (supportsStates) {
                GameButton(stringResource(R.string.game_session_save_state), onSave, enabled = canSave)
                GameButton(stringResource(R.string.game_session_load_state), onLoad, enabled = canLoad)
            } else {
                Text("Este núcleo não oferece suporte a save states.", color = GameColors.textDim, fontSize = 12.sp)
            }
            GameButton(stringResource(R.string.game_session_reset), onReset)
            if (hasAchievements) GameButton(stringResource(R.string.game_session_achievements), onAchievements)
            GameButton(stringResource(R.string.game_session_core_options), onCoreOptions)
            GameButton(stringResource(R.string.game_session_exit), onExit, danger = true, modifier = Modifier.padding(top = 8.dp))
            Text(
                text = stringResource(R.string.game_session_autosave_note),
                color = GameColors.textDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
