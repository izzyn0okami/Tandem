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
    private var raSession: RaSession? = null
    private val raUnlockQueue = mutableStateListOf<RaAchievement>()
    private var raEntries by mutableStateOf<List<RaEntry>>(emptyList())
    private var raHardcore by mutableStateOf(false)
    private var raListOpen by mutableStateOf(false)

    // Indicadores e avisos extras do overlay de conquistas (padrão RetroAchievements; ver RaBadge.kt).
    private val raChallengeIndicators = mutableStateMapOf<Long, RaChallengeIndicator>()
    private var raProgressIndicator by mutableStateOf<RaProgressIndicator?>(null)
    private val raLeaderboardTrackers = mutableStateMapOf<Long, RaLeaderboardTracker>()
    private val raLeaderboardMessageQueue = mutableStateListOf<String>()
    private val raMasteryQueue = mutableStateListOf<RaMastery>()
    private var optionsOpen by mutableStateOf(false)
    private var coreOptions by mutableStateOf<List<CoreOption>>(emptyList())
    private var coreLog: Closeable? = null
    private var coreIdForOptions = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        this.romPath = romPath
        if (corePath == null || romPath == null) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val coreId = File(corePath).name.removeSuffix("_libretro_android.so")
        val platformSlug = intent.getStringExtra(EXTRA_PLATFORM)
        val siblings = platformSlug?.let(GamePlatforms::bySlug)?.let { CoreRegistry.cores(it) }.orEmpty().map { it.id }
        val store = SaveStore(this, coreId, File(romPath), siblings)
        saveStore = store
        coreIdForOptions = coreId
        SessionDiagnostics.sessionStarted(this, coreId, romPath, platformSlug)
        coreLog = SessionDiagnostics.startCoreLog()

        val platformObject = platformSlug?.let(GamePlatforms::bySlug)
        // Calculado uma vez, sem depender de rede: o menu de pausa usa isso para travar "Carregar estado"
        // desde o primeiro frame, sem esperar a sessão do RetroAchievements (que é assíncrona) confirmar.
        val statesBlocked = platformObject != null && RaSettings.blocksStates(this, platformObject)
        // Reflete o modo escolhido desde o início, antes do login/identificação assíncronos do rc_client.
        raHardcore = statesBlocked
        if (platformObject?.raConsoleId != null && RaSettings.isLoggedIn(this)) {
            toast(if (statesBlocked) R.string.game_ra_hardcore_label else R.string.game_ra_softcore_label)
        }
        // Núcleos como N64 e DS não abrem .zip: extrai antes (os saves continuam com o nome do .zip).
        val loadPath = RomUnzipper.prepare(this, romPath, platformObject).absolutePath
        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            gameFilePath = loadPath
            systemDirectory = GameFolders.dir(this@GameSessionActivity, FolderKind.Bios).absolutePath
            savesDirectory = GameFolders.dir(this@GameSessionActivity, FolderKind.Saves).absolutePath
            saveRAMState = store.readSram()
            // Opções que o usuário salvou para este núcleo.
            variables = CoreRegistry.variables(this@GameSessionActivity, coreId)
        }

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)
        setContentView(view)

        if (coreId == "melonds") {
            lifecycleScope.launch {
                view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
                applyDsSideBySide(view)
            }
        }

        val platform = platformObject
        if (platform?.raConsoleId != null && RaSettings.isLoggedIn(this)) {
            val session = RaSession(
                context = applicationContext,
                view = view,
                platform = platform,
                romPath = loadPath,
                cacheKeyPath = romPath,
                scope = lifecycleScope,
                hardcore = RaSettings.hardcore(this),
                onUnlock = {
                    raUnlockQueue.add(it)
                    if (GameModeSettings.isSoundEnabled(this)) NavSound.unlock()
                },
                onState = { entries, hardcore ->
                    raEntries = entries
                    // Hardcore is a session rule, not a property of whether this game has achievements.
                    raHardcore = hardcore
                },
                onStatus = { status, unlocked, total ->
                    when (status) {
                        RaGameStatus.UNSUPPORTED -> toast(R.string.game_ra_status_unsupported)
                        RaGameStatus.NO_ACHIEVEMENTS -> toast(R.string.game_ra_status_none)
                        RaGameStatus.TRACKING -> toast(getString(R.string.game_ra_status_progress, unlocked, total))
                    }
                },
                onOverlayEvent = { event -> handleRaOverlayEvent(event) },
            )
            raSession = session
            lifecycleScope.launch {
                view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
                session.start()
            }
        }

        addContentView(
            passThrough(
                ComposeView(this).apply {
                    setContent {
                        LawnchairTheme {
                            raUnlockQueue.firstOrNull()?.let { unlocked ->
                                AchievementOverlay(unlocked, hardcore = raHardcore) {
                                    if (raUnlockQueue.firstOrNull()?.id == unlocked.id) {
                                        raUnlockQueue.removeAt(0)
                                    }
                                }
                            }
                            // Indicadores e avisos extras do padrão RetroAchievements (ver RaBadge.kt).
                            ChallengeIndicatorsRow(raChallengeIndicators.values.toList())
                            raProgressIndicator?.let { ProgressIndicatorBadge(it) }
                            LeaderboardTrackersColumn(raLeaderboardTrackers.values.toList())
                            raLeaderboardMessageQueue.firstOrNull()?.let { message ->
                                LeaderboardMessageToast(message) {
                                    if (raLeaderboardMessageQueue.firstOrNull() == message) raLeaderboardMessageQueue.removeAt(0)
                                }
                            }
                            raMasteryQueue.firstOrNull()?.let { mastery ->
                                MasteryOverlay(
                                    mastery = mastery,
                                    playtimeLabel = formatPlaytime(PlayHistory.get(this@GameSessionActivity, romPath).playtimeMs),
                                    userName = RaSettings.user(this@GameSessionActivity).orEmpty(),
                                ) {
                                    if (raMasteryQueue.firstOrNull() == mastery) raMasteryQueue.removeAt(0)
                                }
                            }
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
                                    canLoad = store.hasState() && !statesBlocked,
                                    hasAchievements = raEntries.isNotEmpty(),
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

            is RaOverlayEvent.LeaderboardMessage -> raLeaderboardMessageQueue.add(event.text)

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
            Toast.makeText(this, "Aguarde alguns instantes antes de pausar novamente", Toast.LENGTH_SHORT).show()
            return
        }
        menuOpen = open
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
                retroView?.idleAchievements()
                delay(1_000)
            }
        }
    }

    private fun stopRaIdleLoop() {
        raIdleJob?.cancel()
        raIdleJob = null
    }

    private fun persistSram() {
        val view = retroView ?: return
        val store = saveStore ?: return
        runCatching {
            val bytes = view.serializeSRAM()
            if (bytes.isNotEmpty()) store.writeSram(bytes)
        }
    }

    private fun saveQuickState() {
        val view = retroView ?: return
        val store = saveStore ?: return
        val saved = runCatching {
            val (emuState, raProgress) = view.serializeStateWithAchievements()
            if (emuState == null) return@runCatching false
            val payload = if (raProgress != null) {
                val header = "TNDM".toByteArray(Charsets.US_ASCII)
                val len = raProgress.size
                header + byteArrayOf(
                    (len shr 24).toByte(),
                    (len shr 16).toByte(),
                    (len shr 8).toByte(),
                    len.toByte(),
                ) + raProgress + emuState
            } else {
                emuState
            }
            store.writeState(payload)
            true
        }.getOrDefault(false)
        toast(if (saved) R.string.game_session_state_saved else R.string.game_session_state_save_failed)
        setMenu(false)
    }

    private fun loadQuickState() {
        val view = retroView ?: return
        val payload = saveStore?.readState() ?: return
        var emuState = payload
        var raProgress: ByteArray? = null
        val marker = "TNDM".toByteArray(Charsets.US_ASCII)
        if (payload.size >= 8 && payload.copyOfRange(0, 4).contentEquals(marker)) {
            val len = ((payload[4].toInt() and 0xFF) shl 24) or
                ((payload[5].toInt() and 0xFF) shl 16) or
                ((payload[6].toInt() and 0xFF) shl 8) or
                (payload[7].toInt() and 0xFF)
            val emuSize = payload.size - 8 - len
            if (len < 0 || emuSize <= 0) {
                // Cabeçalho corrompido ou pacote truncado: não entrega o conteúdo ao núcleo.
                toast(R.string.game_session_state_load_failed)
                setMenu(false)
                return
            }
            raProgress = payload.copyOfRange(8, 8 + len)
            emuState = payload.copyOfRange(8 + len, payload.size)
        }
        val loaded = runCatching { view.unserializeStateWithAchievements(emuState, raProgress) }.getOrDefault(false)
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
        super.onResume()
        if (!menuOpen) stopRaIdleLoop()
        resumedAtMs = SystemClock.elapsedRealtime()
        lostWatcher.start()
    }

    override fun onDestroy() {
        stopRaIdleLoop()
        raSession?.stop()
        // Esta Activity não herda de LibretroActivity; portanto precisa liberar explicitamente
        // os recursos nativos enquanto o GL thread ainda está ativo.
        retroView?.destroyNative()
        coreLog?.close()
        if (isFinishing) SessionDiagnostics.sessionEnded(this, "encerrado normalmente")
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
        retroView?.updateVariables(Variable(key = option.key, value = value))
        CoreRegistry.saveOption(this, coreIdForOptions, option.key, value)
        coreOptions = coreOptions.map { if (it.key == option.key) it.copy(current = value) else it }
        GameLog.log("núcleos", "$coreIdForOptions: ${option.key} = $value")
    }

    override fun onStop() {
        super.onStop()
        val rom = romPath ?: return
        val core = saveStore?.let { coreIdOf() } ?: return
        SaveSync.pushAsync(this, rom, core)
    }

    private fun coreIdOf(): String? = intent.getStringExtra(EXTRA_CORE_PATH)?.let { File(it).name.removeSuffix("_libretro_android.so") }

    override fun onPause() {
        // Antes do super: a superfície de vídeo ainda está ativa e consegue entregar o save.
        persistSram()
        romPath?.let {
            val elapsed = SystemClock.elapsedRealtime() - resumedAtMs
            if (resumedAtMs > 0) PlayHistory.addSession(this, it, elapsed, System.currentTimeMillis())
        }
        resumedAtMs = 0L
        lostWatcher.stop()
        super.onPause()
        // O rc_client precisa continuar recebendo idle enquanto o app está em segundo plano.
        startRaIdleLoop()
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
        private const val RA_TOAST_MS = 4_000L
        private const val SRAM_AUTOSAVE_MS = 60_000L

        fun intent(context: Context, corePath: String, romPath: String, loadState: Boolean = false, platformSlug: String? = null): Intent = Intent(context, GameSessionActivity::class.java)
            .putExtra(EXTRA_CORE_PATH, corePath)
            .putExtra(EXTRA_ROM_PATH, romPath)
            .putExtra(EXTRA_LOAD_STATE, loadState)
            .putExtra(EXTRA_PLATFORM, platformSlug)
    }
}

@Composable
private fun PauseMenu(
    title: String,
    subtitle: String?,
    canLoad: Boolean,
    hasAchievements: Boolean,
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
            GameButton(stringResource(R.string.game_session_resume), onResume, primary = true, modifier = Modifier.focusRequester(firstItem))
            GameButton(stringResource(R.string.game_session_save_state), onSave)
            GameButton(stringResource(R.string.game_session_load_state), onLoad, enabled = canLoad)
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
