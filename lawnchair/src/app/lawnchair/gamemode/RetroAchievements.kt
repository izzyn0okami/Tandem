package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import com.android.launcher3.R
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.RaCallbacks
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Conta do RetroAchievements: só o nome e o token ficam no aparelho; a senha nunca é guardada. */
object RaSettings {

    private fun prefs(context: Context) = context.getSharedPreferences("retroachievements", Context.MODE_PRIVATE)

    fun user(context: Context): String? = prefs(context).getString("user", null)

    fun token(context: Context): String? = prefs(context).getString("token", null)

    fun isLoggedIn(context: Context): Boolean = user(context) != null && token(context) != null

    fun save(context: Context, user: String, token: String) {
        prefs(context).edit {
            putString("user", user)
            putString("token", token)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    /** Modo hardcore: sem carregar estados salvos; as conquistas valem em dobro no site. */
    fun hardcore(context: Context): Boolean = prefs(context).getBoolean("hardcore", false)

    fun setHardcore(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean("hardcore", enabled) }
    }

    /** O hardcore bloqueia estados salvos neste jogo? (só em plataformas com conquistas e com conta) */
    fun blocksStates(context: Context, platform: GamePlatform): Boolean = hardcore(context) && isLoggedIn(context) && platform.raConsoleId != null

    /**
     * Guarda, por caminho do arquivo, o id do jogo no RetroAchievements assim que é descoberto (ao
     * jogar pela primeira vez). Achar o id exige o hash calculado pelo motor com o núcleo já rodando,
     * então a tela de detalhes (sem o jogo aberto) só consegue mostrar conquistas depois dessa primeira vez.
     */
    fun cachedGameId(context: Context, romPath: String): Long? = prefs(context).getLong("gameid:$romPath", -1L).takeIf { it > 0 }

    fun setCachedGameId(context: Context, romPath: String, gameId: Long) {
        prefs(context).edit { putLong("gameid:$romPath", gameId) }
    }
}

data class RaAchievement(val id: Long, val title: String, val description: String, val points: Int, val badge: String = "")

/** Uma conquista do jogo e se o usuário já a tem (no modo em que está jogando). */
data class RaEntry(val achievement: RaAchievement, val unlocked: Boolean)

// --- Indicadores e avisos extras, padrão de overlay da própria RetroAchievements -----------------
// (ver https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration)

/** Ícone de "desafio ativo": pequena versão do ícone desbloqueado, uma por conquista com desafio em andamento. */
data class RaChallengeIndicator(val id: Long, val badge: String)

/** Indicador de progresso (ícone trancado + texto, ex.: "7/10"); só um visível por vez. */
data class RaProgressIndicator(val title: String, val badge: String, val progress: String, val percent: Float)

/** Valor ao vivo de um placar sendo acompanhado (ex.: um cronômetro). */
data class RaLeaderboardTracker(val id: Long, val display: String)

/** Aviso de "Mastered" (hardcore) ou "Completed" (normal), do jogo todo ou de um subconjunto/DLC. */
data class RaMastery(val title: String, val badgeUrl: String, val hardcore: Boolean, val subset: Boolean)

/** Um evento de overlay do rc_client, já traduzido para algo que a tela de jogo sabe desenhar. */
sealed interface RaOverlayEvent {
    data class ChallengeShow(val indicator: RaChallengeIndicator) : RaOverlayEvent
    data class ChallengeHide(val id: Long) : RaOverlayEvent
    data class Progress(val indicator: RaProgressIndicator) : RaOverlayEvent
    data object ProgressHide : RaOverlayEvent
    data class LeaderboardMessage(val text: String) : RaOverlayEvent
    data class Tracker(val tracker: RaLeaderboardTracker) : RaOverlayEvent
    data class TrackerHide(val id: Long) : RaOverlayEvent
    data class Mastery(val info: RaMastery) : RaOverlayEvent
}

/** Conquistas que o usuário já tem: no modo normal e no hardcore. */
data class RaUnlocks(val softcore: Set<Long>, val hardcore: Set<Long>)

/**
 * Bloqueante: lista de conquistas e o que já foi ganho, para mostrar na tela de detalhes sem o jogo
 * estar aberto. Só funciona depois que o jogo foi aberto pelo menos uma vez (é quando o id do jogo no
 * RetroAchievements é descoberto e guardado; ver [RaSettings.cachedGameId]). Nulo = sem conta, ou jogo
 * ainda não reconhecido.
 *
 * Isso continua usando a API clássica (dorequest.php) direto, em vez do motor rc_client: o rc_client
 * foi desenhado em torno de uma sessão com o jogo carregado de verdade, e não dá pra "espiar" as
 * conquistas de um jogo que não está rodando. Essa é a única parte do app que ainda fala com a API
 * dessa forma; durante a partida (classe [RaSession] abaixo) é tudo feito pelo rc_client oficial.
 */
fun loadAchievementPreview(context: Context, romPath: String): List<RaEntry>? {
    val user = RaSettings.user(context) ?: return null
    val token = RaSettings.token(context) ?: return null
    val gameId = RaSettings.cachedGameId(context, romPath) ?: return null
    return try {
        val client = RaPreviewClient()
        val all = client.achievements(user, token, gameId)
        if (all.isEmpty()) return emptyList()
        // Só consulta (startsession também marca "jogando agora", mas é inofensivo e é a forma já usada pela API clássica).
        val unlocks = client.startSession(user, token, gameId, hardcore = false)
        val unlocked = unlocks.softcore + unlocks.hardcore
        all.map { RaEntry(it, unlocked = it.id in unlocked) }
    } catch (e: IOException) {
        GameLog.error("conquistas", "não consegui carregar a prévia de conquistas", e)
        null
    }
}

/** O que a sessão descobriu sobre o jogo no RetroAchievements, para mostrar um aviso ao entrar. */
enum class RaGameStatus {
    /** A ROM não bate com nenhum hash conhecido (pode ser uma versão diferente, tradução ou hack), ou não deu pra entrar na conta. */
    UNSUPPORTED,

    /** O jogo é reconhecido, mas ninguém cadastrou conquistas para ele ainda. */
    NO_ACHIEVEMENTS,

    /** Jogo reconhecido e com conquistas sendo acompanhadas nesta partida. */
    TRACKING,
}

/**
 * Cliente mínimo da API clássica do RetroAchievements (dorequest.php), usado só para: o login com
 * senha (tela de configurações) e a prévia de conquistas na tela de detalhes (ver [loadAchievementPreview]),
 * os dois únicos pontos do app que não passam pelo motor rc_client. Chamadas bloqueantes.
 */
class RaPreviewClient {

    private val http = OkHttpClient()

    private fun call(vararg params: Pair<String, String>): JSONObject {
        val form = FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder()
            .url("https://retroachievements.org/dorequest.php")
            .header("User-Agent", "Tandem/1.0")
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("RetroAchievements respondeu ${response.code}")
            return JSONObject(response.body.string())
        }
    }

    /** Entra com usuário e senha e devolve o token (a senha não é guardada). */
    fun login(user: String, password: String): Pair<String, String> {
        val json = call("r" to "login2", "u" to user.trim(), "p" to password)
        if (!json.optBoolean("Success", false)) {
            throw IOException(json.optString("Error").ifEmpty { "Usuário ou senha inválidos" })
        }
        return json.getString("User") to json.getString("Token")
    }

    fun achievements(user: String, token: String, gameId: Long): List<RaAchievement> {
        val json = call("r" to "patch", "u" to user, "t" to token, "g" to gameId.toString())
        val list = json.optJSONObject("PatchData")?.optJSONArray("Achievements") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
            // Flags 3 = conquistas oficiais (5 são não oficiais).
            .filter { it.optInt("Flags", 3) == 3 }
            .map {
                RaAchievement(
                    id = it.getLong("ID"),
                    title = it.optString("Title"),
                    description = it.optString("Description"),
                    points = it.optInt("Points", 0),
                    badge = it.optString("BadgeName"),
                )
            }
            .filter { !it.title.contains("Unknown Emulator", ignoreCase = true) }
    }

    /** Inicia a sessão de jogo e devolve as conquistas que o usuário já tem. */
    fun startSession(user: String, token: String, gameId: Long, hardcore: Boolean): RaUnlocks {
        val json = call("r" to "startsession", "u" to user, "t" to token, "g" to gameId.toString(), "h" to if (hardcore) "1" else "0")
        fun ids(key: String): Set<Long> {
            val array = json.optJSONArray(key) ?: return emptySet()
            return (0 until array.length()).map { array.getJSONObject(it).getLong("ID") }.toSet()
        }
        return RaUnlocks(softcore = ids("Unlocks"), hardcore = ids("HardcoreUnlocks"))
    }
}

/**
 * Conquistas durante uma partida (normal ou hardcore), usando o motor oficial da RetroAchievements
 * (rc_client, do lado nativo): login, identificar/carregar o jogo pelo hash, modo hardcore e o envio
 * dos desbloqueios ao site (com fila de novas tentativas automática se a internet cair) são todos
 * cuidados por ele. Este objeto só faz a ponte: executa as chamadas HTTP que o rc_client pede (com o
 * OkHttp do app) e repassa os eventos (conquista desbloqueada, etc.) para a UI.
 */
class RaSession(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val view: GLRetroView,
    private val platform: GamePlatform,
    private val romPath: String,
    private val scope: CoroutineScope,
    private val hardcore: Boolean,
    private val onUnlock: (RaAchievement) -> Unit,
    private val onState: (entries: List<RaEntry>, hardcore: Boolean) -> Unit,
    private val onStatus: (status: RaGameStatus, unlocked: Int, total: Int) -> Unit = { _, _, _ -> },
    private val onOverlayEvent: (RaOverlayEvent) -> Unit = {},
) : RaCallbacks {

    private val http = OkHttpClient()
    private var achievementsById = emptyMap<Long, RaAchievement>()

    @Volatile private var active = false

    /** Chamar só depois que o jogo já está rodando (o motor precisa do mapa de memória do núcleo). */
    fun start() {
        val user = RaSettings.user(context) ?: return
        val token = RaSettings.token(context) ?: return
        val consoleId = platform.raConsoleId ?: return

        active = true
        LibretroDroid.raCreate()
        LibretroDroid.raSetCallbacks(this)
        LibretroDroid.raSetHardcoreEnabled(hardcore)

        scope.launch(Dispatchers.IO) {
            val hash = runCatching { LibretroDroid.computeRomHash(romPath, consoleId) }.getOrNull()
            if (hash == null || !active) {
                withContext(Dispatchers.Main) { onStatus(RaGameStatus.UNSUPPORTED, 0, 0) }
                return@launch
            }
            pendingHash = hash
            pendingConsoleId = consoleId
            LibretroDroid.raBeginLoginWithToken(user, token)
        }
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { LibretroDroid.raUnloadGame() }
        runCatching { LibretroDroid.raSetCallbacks(null) }
        runCatching { LibretroDroid.raDestroy() }
    }

    @Volatile private var pendingHash: String? = null

    @Volatile private var pendingConsoleId: Int? = null

    // --- RaCallbacks: chamado pelo nativo, em threads variadas (ver RaCallbacks.java) -----------

    override fun onServerCall(requestId: Int, url: String, postData: String?, contentType: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url).header("User-Agent", "Tandem/1.0")
                if (postData != null) {
                    val mediaType = (contentType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
                    builder.post(postData.toRequestBody(mediaType))
                }
                http.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    LibretroDroid.raDeliverServerResponse(requestId, response.code, body, false)
                }
            } catch (e: IOException) {
                // Sem internet ou falha de rede: o rc_client sabe tentar de novo mais tarde sozinho.
                LibretroDroid.raDeliverServerResponse(requestId, 0, "", true)
            }
        }
    }

    override fun onLoginResult(success: Boolean, errorMessage: String?) {
        if (!active) return
        if (!success) {
            GameLog.log("conquistas", "não consegui entrar na conta RetroAchievements: $errorMessage")
            scope.launch(Dispatchers.Main) { onStatus(RaGameStatus.UNSUPPORTED, 0, 0) }
            return
        }
        val hash = pendingHash ?: return
        val consoleId = pendingConsoleId ?: return
        LibretroDroid.raBeginLoadGame(hash, consoleId)
    }

    override fun onLoadGameResult(success: Boolean, errorMessage: String?) {
        if (!active) return
        if (!success) {
            GameLog.log("conquistas", "jogo não reconhecido pelo RetroAchievements: $errorMessage")
            scope.launch(Dispatchers.Main) { onStatus(RaGameStatus.UNSUPPORTED, 0, 0) }
            return
        }
        publish(reportStatus = true)
    }

    override fun onAchievementTriggered(achievementId: Int) {
        val id = achievementId.toLong()
        publish(reportStatus = false)
        achievementsById[id]?.let { achievement ->
            scope.launch(Dispatchers.Main) { onUnlock(achievement) }
        }
    }

    override fun onServerError(api: String, message: String) {
        GameLog.log("conquistas", "RetroAchievements ($api): $message")
    }

    override fun onReset() {
        // O hardcore só é escolhido antes de começar a jogar, então isso não deve acontecer hoje;
        // deixado pronto (e registrado) para quando/se o app permitir trocar de modo com o jogo aberto.
        GameLog.log("conquistas", "RetroAchievements pediu reinício do jogo (mudança de modo hardcore)")
    }

    // --- Indicadores e avisos extras, padrão de overlay da RetroAchievements --------------------

    override fun onChallengeIndicatorShow(achievementId: Int, badge: String) {
        emitOverlay(RaOverlayEvent.ChallengeShow(RaChallengeIndicator(achievementId.toLong(), badge)))
    }

    override fun onChallengeIndicatorHide(achievementId: Int) {
        emitOverlay(RaOverlayEvent.ChallengeHide(achievementId.toLong()))
    }

    override fun onProgressIndicatorShow(_achievementId: Int, title: String, badge: String, measuredProgress: String, measuredPercent: Float) {
        emitOverlay(RaOverlayEvent.Progress(RaProgressIndicator(title, badge, measuredProgress, measuredPercent)))
    }

    override fun onProgressIndicatorHide() {
        emitOverlay(RaOverlayEvent.ProgressHide)
    }

    override fun onLeaderboardStarted(_leaderboardId: Int, title: String, _description: String) {
        emitOverlay(RaOverlayEvent.LeaderboardMessage(context.getString(R.string.game_ra_leaderboard_started, title)))
    }

    override fun onLeaderboardFailed(_leaderboardId: Int, title: String) {
        emitOverlay(RaOverlayEvent.LeaderboardMessage(context.getString(R.string.game_ra_leaderboard_failed, title)))
    }

    override fun onLeaderboardSubmitted(_leaderboardId: Int, title: String, score: String) {
        emitOverlay(RaOverlayEvent.LeaderboardMessage(context.getString(R.string.game_ra_leaderboard_submitted, score, title)))
    }

    override fun onLeaderboardTrackerUpdate(trackerId: Int, display: String) {
        emitOverlay(RaOverlayEvent.Tracker(RaLeaderboardTracker(trackerId.toLong(), display)))
    }

    override fun onLeaderboardTrackerHide(trackerId: Int) {
        emitOverlay(RaOverlayEvent.TrackerHide(trackerId.toLong()))
    }

    override fun onGameCompleted(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val mastery = RaMastery(
            title = obj.optString("title"),
            badgeUrl = obj.optString("badgeUrl"),
            hardcore = obj.optBoolean("hardcore"),
            subset = obj.optBoolean("subset"),
        )
        emitOverlay(RaOverlayEvent.Mastery(mastery))
    }

    private fun emitOverlay(event: RaOverlayEvent) {
        if (!active) return
        scope.launch(Dispatchers.Main) { onOverlayEvent(event) }
    }

    private fun publish(reportStatus: Boolean) {
        val json = runCatching { JSONArray(LibretroDroid.raGetAchievementListJson()) }.getOrNull() ?: return
        val entries = (0 until json.length()).map { json.getJSONObject(it) }.map { obj ->
            val achievement = RaAchievement(
                id = obj.getLong("id"),
                title = obj.optString("title"),
                description = obj.optString("description"),
                points = obj.optInt("points"),
                badge = obj.optString("badge"),
            )
            RaEntry(achievement, unlocked = obj.optBoolean("unlocked"))
        }
        achievementsById = entries.associate { it.achievement.id to it.achievement }

        scope.launch(Dispatchers.Main) {
            onState(entries, hardcore)
            if (reportStatus) {
                val unlockedCount = entries.count { it.unlocked }
                onStatus(
                    if (entries.isEmpty()) RaGameStatus.NO_ACHIEVEMENTS else RaGameStatus.TRACKING,
                    unlockedCount,
                    entries.size,
                )
            }
        }
    }
}
