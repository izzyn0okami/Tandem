package app.lawnchair.gamemode

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

/**
 * Estado do servidor RomM compartilhado pela biblioteca: lista de jogos (guardada no aparelho e
 * atualizada em segundo plano), downloads em andamento e conexão.
 */
@Stable
internal class RemoteLibrary(private val context: Context, private val scope: CoroutineScope) {

    var connected by mutableStateOf(RomMSettings.isConnected(context))
        private set
    var games by mutableStateOf<List<RemoteGame>?>(RomMSettings.url(context)?.let { RomMCache.load(context, it) })
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var report by mutableStateOf("")
        private set

    /** Sobe a cada pedido de atualização; a biblioteca reage a ele. */
    var tick by mutableIntStateOf(0)
        private set

    /** Bytes já baixados por jogo do servidor (id do RomM). */
    val progress: SnapshotStateMap<Long, Long> = mutableStateMapOf()

    private var forced = false

    val url: String get() = RomMSettings.url(context).orEmpty()

    fun requestRefresh() {
        forced = true
        tick++
    }

    fun onConnected() {
        connected = true
        games = null
        error = ""
        requestRefresh()
    }

    fun disconnect() {
        RomMSettings.clear(context)
        connected = false
        games = null
        error = ""
    }

    /** Atualiza a lista do servidor, no máximo a cada 5 minutos, salvo pedido explícito. */
    suspend fun refresh() {
        if (!connected) return
        val force = forced
        forced = false
        val fresh = System.currentTimeMillis() - RomMCache.lastRefreshMs < REFRESH_INTERVAL_MS
        if (!force && games != null && fresh) {
            val cachedGames = games.orEmpty()
            withContext(Dispatchers.IO) {
                val localGames = GameLibraryScanner.scan(File(GameLibrarySettings.rootPath(context)))
                GameLibraryReconciler.reconcile(context, localGames, cachedGames)
            }
            return
        }
        error = ""
        refreshing = true
        try {
            val client = RomMClient(url, RomMSettings.token(context))
            val fetched = withContext(Dispatchers.IO) {
                val catalog = client.listGames()
                val localGames = GameLibraryScanner.scan(File(GameLibrarySettings.rootPath(context)))
                GameLibraryReconciler.reconcile(context, localGames, catalog)
                catalog
            }
            games = fetched
            report = client.listReport
            GameLog.log("romm", "lista: ${fetched.size} jogos. $report")
            RomMCache.save(context, url, fetched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GameLog.error("romm", "falha ao listar", e)
            if (games == null) games = emptyList()
            error = romMUserFacingError(e, "carregar a biblioteca RomM")
        } finally {
            refreshing = false
        }
    }

    /** Baixa o jogo para a pasta de jogos; [onDone] roda no fim (com ou sem sucesso). */
    fun download(game: RemoteGame, root: File, onDone: () -> Unit) {
        if (progress.containsKey(game.id)) return
        scope.launch {
            progress[game.id] = 0L
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = game.destination(root)
                    RomMClient(url, RomMSettings.token(context)).download(game, target) { bytes -> progress[game.id] = bytes }
                    SaveSync.link(context, target.absolutePath, game.id)
                }
            }.onFailure {
                GameLog.error("romm", "falha ao baixar ${game.title}", it)
                error = romMUserFacingError(it, "baixar ${game.title}")
            }
            progress.remove(game.id)
            onDone()
        }
    }
}
