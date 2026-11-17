package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import com.swordfish.libretrodroid.AchievementDef
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /** Conquistas desbloqueadas sem rede, guardadas como "idConquista|hashDoJogo|h" (h = 1 hardcore) para reenviar depois. */
    fun pending(context: Context): Set<String> = prefs(context).getStringSet("pending", emptySet()).orEmpty()

    fun setPending(context: Context, entries: Set<String>) {
        prefs(context).edit { putStringSet("pending", entries) }
    }
}

data class RaAchievement(val id: Long, val title: String, val description: String, val points: Int, val memAddr: String, val badge: String = "")

/** Uma conquista do jogo e se o usuário já a tem (no modo em que está jogando). */
data class RaEntry(val achievement: RaAchievement, val unlocked: Boolean)

/** Conquistas que o usuário já tem: no modo normal e no hardcore. */
data class RaUnlocks(val softcore: Set<Long>, val hardcore: Set<Long>)

/**
 * Bloqueante: lista de conquistas e o que já foi ganho, para mostrar na tela de detalhes sem o jogo
 * estar aberto. Só funciona depois que o jogo foi aberto pelo menos uma vez (é quando o id do jogo no
 * RetroAchievements é descoberto e guardado; ver [RaSettings.cachedGameId]). Nulo = sem conta, ou jogo
 * ainda não reconhecido.
 */
fun loadAchievementPreview(context: Context, romPath: String): List<RaEntry>? {
    val user = RaSettings.user(context) ?: return null
    val token = RaSettings.token(context) ?: return null
    val gameId = RaSettings.cachedGameId(context, romPath) ?: return null
    return try {
        val client = RaClient()
        val all = client.achievements(user, token, gameId)
        if (all.isEmpty()) return emptyList()
        // Só consulta (startsession também marca "jogando agora", mas é inofensivo e é a forma já usada no resto do app).
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
    /** A ROM não bate com nenhum hash conhecido (pode ser uma versão diferente, tradução ou hack). */
    UNSUPPORTED,

    /** O jogo é reconhecido, mas ninguém cadastrou conquistas para ele ainda. */
    NO_ACHIEVEMENTS,

    /** Jogo reconhecido e com conquistas sendo acompanhadas nesta partida. */
    TRACKING,
}

/** Cliente mínimo da API clássica do RetroAchievements (dorequest.php). Chamadas bloqueantes. */
class RaClient {

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

    /** Código do jogo no RetroAchievements a partir do hash da ROM; nulo se não for reconhecido. */
    fun gameIdForHash(hash: String): Long? {
        val id = call("r" to "gameid", "m" to hash).optLong("GameID", 0L)
        return id.takeIf { it > 0 }
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
                    memAddr = it.optString("MemAddr"),
                    badge = it.optString("BadgeName"),
                )
            }
            .filter { it.memAddr.isNotBlank() && !it.title.contains("Unknown Emulator", ignoreCase = true) }
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

    /** Registra a conquista. "Já tinha" conta como sucesso. */
    fun award(user: String, token: String, achievementId: Long, gameHash: String, hardcore: Boolean): Boolean {
        val mode = if (hardcore) "1" else "0"
        val validation = MessageDigest.getInstance("MD5")
            .digest("$achievementId$user$mode".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val json = call(
            "r" to "awardachievement",
            "u" to user,
            "t" to token,
            "a" to achievementId.toString(),
            "h" to mode,
            "m" to gameHash,
            "v" to validation,
        )
        return json.optBoolean("Success", false) || json.optString("Error").contains("already", ignoreCase = true)
    }
}

/**
 * Conquistas durante uma partida (normal ou hardcore): descobre o jogo pelo hash da ROM,
 * entrega as conquistas ao motor e registra no site as que forem desbloqueadas. Sem rede, as
 * conquistas ficam guardadas e são reenviadas na próxima partida.
 */
class RaSession(
    private val context: Context,
    private val view: GLRetroView,
    private val platform: GamePlatform,
    private val romPath: String,
    private val scope: CoroutineScope,
    private val hardcore: Boolean,
    private val onUnlock: (RaAchievement) -> Unit,
    private val onState: (entries: List<RaEntry>, hardcore: Boolean) -> Unit,
    private val onStatus: (status: RaGameStatus, unlocked: Int, total: Int) -> Unit = { _, _, _ -> },
) {
    private val client = RaClient()
    private var all = emptyList<RaAchievement>()
    private var achievementsById = emptyMap<Long, RaAchievement>()
    private val unlockedIds = mutableSetOf<Long>()
    private var gameHash: String? = null

    /** Chamar só depois que o jogo já está rodando (o motor precisa do mapa de memória do núcleo). */
    fun start() {
        val user = RaSettings.user(context) ?: return
        val token = RaSettings.token(context) ?: return
        val consoleId = platform.raConsoleId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                resendPending(user, token)
                val hash = LibretroDroid.computeRomHash(romPath, consoleId) ?: return@launch
                gameHash = hash
                val gameId = client.gameIdForHash(hash)
                if (gameId == null) {
                    withContext(Dispatchers.Main) { onStatus(RaGameStatus.UNSUPPORTED, 0, 0) }
                    return@launch
                }
                RaSettings.setCachedGameId(context, romPath, gameId)
                val unlocks = runCatching { client.startSession(user, token, gameId, hardcore) }
                    .getOrDefault(RaUnlocks(emptySet(), emptySet()))
                // No hardcore só contam as já ganhas em hardcore; no normal, qualquer uma.
                unlockedIds += if (hardcore) unlocks.hardcore else unlocks.softcore + unlocks.hardcore
                all = client.achievements(user, token, gameId)
                achievementsById = all.associateBy { it.id }
                publish()
                withContext(Dispatchers.Main) {
                    onStatus(
                        if (all.isEmpty()) RaGameStatus.NO_ACHIEVEMENTS else RaGameStatus.TRACKING,
                        unlockedIds.size,
                        all.size,
                    )
                }
                val toWatch = all.filter { it.id !in unlockedIds }
                if (toWatch.isNotEmpty()) {
                    LibretroDroid.initAchievements(toWatch.map { AchievementDef(it.id, it.memAddr) }.toTypedArray(), consoleId)
                    view.achievementUnlockListener = { id -> handleUnlock(id, user, token) }
                }
            }
        }
    }

    fun stop() {
        view.achievementUnlockListener = null
        runCatching { LibretroDroid.clearAchievements() }
    }

    private fun handleUnlock(id: Long, user: String, token: String) {
        val achievement = achievementsById[id] ?: return
        unlockedIds += id
        scope.launch(Dispatchers.Main) {
            onUnlock(achievement)
            publish()
        }
        val hash = gameHash ?: return
        scope.launch(Dispatchers.IO) {
            val sent = runCatching { client.award(user, token, id, hash, hardcore) }.getOrDefault(false)
            if (!sent) RaSettings.setPending(context, RaSettings.pending(context) + "$id|$hash|${if (hardcore) 1 else 0}")
        }
    }

    private suspend fun publish() {
        val entries = all.map { RaEntry(it, it.id in unlockedIds) }
        withContext(Dispatchers.Main) { onState(entries, hardcore) }
    }

    private fun resendPending(user: String, token: String) {
        val pending = RaSettings.pending(context)
        if (pending.isEmpty()) return
        val remaining = pending.filterNot { entry ->
            val parts = entry.split("|")
            val id = parts[0].toLongOrNull()
            val hash = parts.getOrNull(1)
            val wasHardcore = parts.getOrNull(2) == "1"
            id != null && hash != null && runCatching { client.award(user, token, id, hash, wasHardcore) }.getOrDefault(false)
        }
        RaSettings.setPending(context, remaining.toSet())
    }
}
