package app.lawnchair.gamemode

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.android.launcher3.R
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.RaCallbacks
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    fun token(context: Context): String? {
        RaTokenKeyStore.get(context)?.let { return it }
        // Migra tokens de versões anteriores, que eram guardados em SharedPreferences sem criptografia.
        val legacy = prefs(context).getString("token", null) ?: return null
        return runCatching {
            RaTokenKeyStore.save(context, legacy)
            prefs(context).edit { remove("token") }
            legacy
        }.getOrNull()
    }

    fun isLoggedIn(context: Context): Boolean = user(context) != null && token(context) != null

    fun webApiKey(context: Context): String? = RaWebApiKeyStore.get(context)

    fun saveWebApiKey(context: Context, key: String) {
        RaWebApiKeyStore.save(context, key)
    }

    fun clearWebApiKey(context: Context) {
        RaWebApiKeyStore.clear(context)
    }

    fun save(context: Context, user: String, token: String) {
        RaTokenKeyStore.save(context, token)
        prefs(context).edit {
            putString("user", user)
            remove("token")
        }
    }

    fun clear(context: Context) {
        RaTokenKeyStore.clear(context)
        RaWebApiKeyStore.clear(context)
        prefs(context).edit { clear() }
    }

    /** Modo hardcore: sem carregar estados salvos; as conquistas valem em dobro no site. */
    fun hardcore(context: Context): Boolean = prefs(context).getBoolean("hardcore", true)

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
    val webApiKey = RaSettings.webApiKey(context) ?: return null
    val gameId = RaSettings.cachedGameId(context, romPath) ?: return null
    return try {
        RaPreviewClient().achievementProgress(user, webApiKey, gameId)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
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
    private val userAgent = "Tandem/1.0.0 (Android ${Build.VERSION.RELEASE})"

    private fun call(vararg params: Pair<String, String>): JSONObject {
        val form = FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder()
            .url("https://retroachievements.org/dorequest.php")
            .header("User-Agent", userAgent)
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("RetroAchievements respondeu ${response.code}")
            return JSONObject(response.body.string())
        }
    }

    private fun getApi(url: okhttp3.HttpUrl): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("RetroAchievements respondeu ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
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

    /** Confirma a Web API Key consultando o perfil da conta já conectada. */
    fun validateWebApiKey(user: String, webApiKey: String) {
        val url = requireNotNull("https://retroachievements.org/API/API_GetUserProfile.php".toHttpUrlOrNull())
            .newBuilder()
            .addQueryParameter("u", user)
            .addQueryParameter("y", webApiKey.trim())
            .build()
        val profile = getApi(url)
        if (profile.optString("User").isBlank() || !profile.optString("User").equals(user, ignoreCase = true)) {
            throw IOException("A API não confirmou o usuário. Verifique a chave e tente novamente.")
        }
    }

    /** Lista conquistas e desbloqueios sem chamar startsession nem alterar o estado de jogo atual. */
    fun achievementProgress(user: String, webApiKey: String, gameId: Long): List<RaEntry> {
        val url = requireNotNull("https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php".toHttpUrlOrNull())
            .newBuilder()
            .addQueryParameter("u", user)
            .addQueryParameter("g", gameId.toString())
            .addQueryParameter("y", webApiKey.trim())
            .build()
        val json = getApi(url)
        val achievements = json.optJSONObject("Achievements") ?: return emptyList()
        val entries = mutableListOf<Pair<Int, RaEntry>>()
        val keys = achievements.keys()
        while (keys.hasNext()) {
            val item = achievements.optJSONObject(keys.next()) ?: continue
            val title = item.optString("Title")
            if (title.isBlank() || title.contains("Unknown Emulator", ignoreCase = true)) continue
            val achievement = RaAchievement(
                id = item.optLong("ID"),
                title = title,
                description = item.optString("Description"),
                points = item.optInt("Points", 0),
                badge = item.optString("BadgeName"),
            )
            val unlocked = item.optString("DateEarned").isNotBlank() ||
                item.optString("DateEarnedHardcore").isNotBlank()
            entries += item.optInt("DisplayOrder") to RaEntry(achievement, unlocked)
        }
        return entries.sortedBy { it.first }.map { it.second }
    }
}

class RaSession(
    private val context: Context,
    private val view: GLRetroView,
    private val platform: GamePlatform,
    private val romPath: String,
    private val cacheKeyPath: String = romPath,
    private val scope: CoroutineScope,
    hardcore: Boolean,
    private val onUnlock: (RaAchievement) -> Unit,
    private val onState: (entries: List<RaEntry>, hardcore: Boolean) -> Unit,
    private val onStatus: (status: RaGameStatus, unlocked: Int, total: Int) -> Unit = { _, _, _ -> },
    private val onOverlayEvent: (RaOverlayEvent) -> Unit = {},
    private val onConnectionState: (connected: Boolean) -> Unit = {},
    private val onRichPresence: (message: String) -> Unit = {},
    private val onGameLoaded: (onRestored: () -> Unit) -> Unit = { it() },
) : RaCallbacks {

    private val http = OkHttpClient()
    private var hardcoreEnabled = hardcore

    @Volatile private var userAgent = "Tandem/1.0.0 (Android ${Build.VERSION.RELEASE})"
    private var achievementsById = emptyMap<Long, RaAchievement>()

    @Volatile private var active = false
    private var richPresenceJob: Job? = null
    private var lastRichPresence: String? = null

    /** Chamar só depois que o jogo já está rodando (o motor precisa do mapa de memória do núcleo). */
    fun start() {
        val user = RaSettings.user(context) ?: return
        val token = RaSettings.token(context) ?: return
        val consoleId = platform.raConsoleId ?: return

        active = true
        onConnectionState(true)
        view.startAchievements(this, hardcoreEnabled)
        val rcheevosClause = view.getAchievementsUserAgentClause()
        if (rcheevosClause.isNotBlank()) userAgent = "Tandem/1.0.0 (Android ${Build.VERSION.RELEASE}) $rcheevosClause"

        scope.launch(Dispatchers.IO) {
            val hash = runCatching { LibretroDroid.computeRomHash(romPath, consoleId) }.getOrNull()
            if (hash == null || !active) {
                if (active) withContext(Dispatchers.Main) { disableHardcoreForUnsupportedSession() }
                return@launch
            }
            pendingHash = hash
            pendingConsoleId = consoleId
            view.beginAchievementsLogin(user, token) { active }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        richPresenceJob?.cancel()
        richPresenceJob = null
        onRichPresence("")
        onConnectionState(true)
        runCatching { view.stopAchievements() }
            .onFailure { GameLog.error("conquistas", "falha ao encerrar a sessão RetroAchievements", it) }
    }

    @Volatile private var pendingHash: String? = null

    @Volatile private var pendingConsoleId: Int? = null

    // --- RaCallbacks: chamado pelo nativo, em threads variadas (ver RaCallbacks.java) -----------

    override fun onServerCall(requestId: Int, url: String, postData: String?, contentType: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url).header("User-Agent", userAgent)
                if (postData != null) {
                    val mediaType = (contentType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
                    builder.post(postData.toRequestBody(mediaType))
                }
                http.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (active) view.deliverAchievementsResponse(requestId, response.code, body, false) { active }
                }
            } catch (e: IOException) {
                // Sem internet ou falha de rede: o rc_client sabe tentar de novo mais tarde sozinho.
                if (active) view.deliverAchievementsResponse(requestId, 0, "", true) { active }
            }
        }
    }

    override fun onLoginResult(success: Boolean, errorMessage: String?) {
        if (!active) return
        if (!success) {
            GameLog.log("conquistas", "não consegui entrar na conta RetroAchievements: $errorMessage")
            disableHardcoreForUnsupportedSession()
            return
        }
        val hash = pendingHash ?: return
        val consoleId = pendingConsoleId ?: return
        view.beginAchievementsGame(hash, consoleId)
    }

    override fun onLoadGameResult(success: Boolean, errorMessage: String?) {
        if (!active) return
        if (success) {
            val gameId = view.getAchievementsGameId()
            if (gameId > 0) RaSettings.setCachedGameId(context, cacheKeyPath, gameId)
            if (!view.isAchievementsProcessingRequired()) {
                // Jogos sem conquistas, placares ou Rich Presence não devem manter Hardcore ativo.
                if (hardcoreEnabled) view.disableAchievementsHardcore()
                hardcoreEnabled = false
                scope.launch(Dispatchers.Main) {
                    if (active) {
                        onState(emptyList(), false)
                        onStatus(RaGameStatus.NO_ACHIEVEMENTS, 0, 0)
                    }
                }
                return
            }
            // A restauração é enfileirada depois deste callback nativo para evitar reentrância no rc_client.
            onGameLoaded {
                if (active) {
                    publish(reportStatus = true)
                    startRichPresenceUpdates()
                }
            }
            return
        }
        if (!success) {
            GameLog.log("conquistas", "jogo não reconhecido pelo RetroAchievements: $errorMessage")
            disableHardcoreForUnsupportedSession()
            return
        }
    }

    override fun onAchievementTriggered(achievementId: Int) {
        if (!active) return
        val id = achievementId.toLong()
        publish(reportStatus = false)
        achievementsById[id]?.let { achievement ->
            scope.launch(Dispatchers.Main) {
                if (active) onUnlock(achievement)
            }
        }
    }

    override fun onServerError(api: String, message: String) {
        if (!active) return
        GameLog.log("conquistas", "RetroAchievements ($api): $message")
        emitOverlay(RaOverlayEvent.LeaderboardMessage("Falha no RetroAchievements ($api): $message"))
    }

    override fun onDisconnected() {
        if (!active) return
        scope.launch(Dispatchers.Main) { if (active) onConnectionState(false) }
        emitOverlay(RaOverlayEvent.LeaderboardMessage("Sem conexão com o RetroAchievements. As atualizações ficarão pendentes."))
    }

    override fun onReconnected() {
        if (!active) return
        scope.launch(Dispatchers.Main) { if (active) onConnectionState(true) }
        emitOverlay(RaOverlayEvent.LeaderboardMessage("Conexão restabelecida. Atualizações sincronizadas."))
    }

    /**
     * Hardcore must not remain active when authentication or game identification fails.
     * The setting itself is preserved for the next supported session.
     */
    private fun disableHardcoreForUnsupportedSession() {
        hardcoreEnabled = false
        view.disableAchievementsHardcore()
        scope.launch(Dispatchers.Main) {
            if (active) {
                onState(emptyList(), false)
                onStatus(RaGameStatus.UNSUPPORTED, 0, 0)
            }
        }
    }

    override fun onReset() {
        if (!active) return
        // O rc_client fica suspenso até receber rc_client_reset. Enfileirar o reset evita
        // reentrância enquanto ainda estamos dentro do callback nativo do rcheevos.
        view.resetAfterAchievementsModeChange()
        GameLog.log("conquistas", "RetroAchievements solicitou reinício do núcleo após mudança de modo")
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

    // A confirmação visual é exibida no evento SCOREBOARD, quando a classificação do servidor chega.
    // Assim evitamos duas notificações para o mesmo envio.
    override fun onLeaderboardSubmitted(_leaderboardId: Int, _title: String, _score: String) = Unit

    override fun onLeaderboardScoreboard(_leaderboardId: Int, title: String, json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val submitted = obj.optString("submittedScore")
        val best = obj.optString("bestScore")
        val rank = obj.optInt("newRank")
        val total = obj.optInt("numEntries")
        val lines = buildList {
            add(title)
            if (submitted.isNotBlank()) add("Enviado: $submitted")
            if (rank > 0) add("Posição: #$rank de $total")
            if (best.isNotBlank()) add("Melhor: $best")
            val topEntries = obj.optJSONArray("topEntries")
            if (topEntries != null) {
                for (index in 0 until minOf(topEntries.length(), 2)) {
                    val entry = topEntries.optJSONObject(index) ?: continue
                    val username = entry.optString("username")
                    val entryScore = entry.optString("score")
                    val entryRank = entry.optInt("rank")
                    if (username.isNotBlank() && entryScore.isNotBlank()) {
                        add("#$entryRank $username — $entryScore")
                    }
                }
            }
        }
        emitOverlay(RaOverlayEvent.LeaderboardMessage(lines.joinToString("\n")))
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

    /** Consulta o texto calculado pelo rc_client sem implementar o parser de Rich Presence em paralelo. */
    private fun startRichPresenceUpdates() {
        richPresenceJob?.cancel()
        richPresenceJob = scope.launch {
            while (active) {
                val message = runCatching {
                    withContext(Dispatchers.Default) { view.getAchievementsRichPresence() }
                }.getOrDefault("")
                if (active && message != lastRichPresence) {
                    lastRichPresence = message
                    onRichPresence(message)
                }
                delay(2_000)
            }
        }
    }

    private fun emitOverlay(event: RaOverlayEvent) {
        if (!active) return
        scope.launch(Dispatchers.Main) { onOverlayEvent(event) }
    }

    private fun publish(reportStatus: Boolean) {
        if (!active) return
        val json = runCatching { JSONArray(view.getAchievementsJson()) }.getOrNull() ?: return
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
            if (!active) return@launch
            onState(entries, hardcoreEnabled)
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
