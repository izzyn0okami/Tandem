package app.lawnchair.gamemode

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ajuda a descobrir por que um núcleo travou: registra o início e o fim de cada partida, copia
 * para o registro as mensagens do núcleo enquanto ele roda (um crash mata o app, então precisa
 * ser gravado na hora) e, na abertura seguinte, lê do Android o motivo da última morte do app.
 */
object SessionDiagnostics {

    private const val FILE = "game_mode_diag"
    private const val MAX_CORE_LINES = 400
    private const val MAX_TRACE_BYTES = 512 * 1024
    private const val MAX_TRACE_HINTS = 10
    private const val MAX_SYSTEM_LIBRARIES = 6
    private const val LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000

    private val signalPattern = Regex("""SIG[A-Z]{3,}|SEGV_[A-Z]+|Abort message[ -~]{0,120}""")
    private val libraryPattern = Regex("""/[A-Za-z0-9_./+-]+\.so""")

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun sessionStarted(context: Context, coreId: String, romPath: String, platformSlug: String?) {
        prefs(context).edit {
            putBoolean("open", true)
            putString("core", coreId)
            putString("rom", File(romPath).name)
        }
        GameLog.log("sessão", "início: núcleo=$coreId, plataforma=${platformSlug ?: "?"}, jogo=${File(romPath).name}")
    }

    fun sessionEnded(context: Context, how: String) {
        prefs(context).edit { putBoolean("open", false) }
        GameLog.log("sessão", "fim: $how")
    }

    /** Copia para o registro o que o núcleo escreve no logcat deste app, até [MAX_CORE_LINES] linhas. */
    fun startCoreLog(): Closeable {
        val stop = AtomicBoolean(false)
        var process: Process? = null
        Thread {
            runCatching {
                val started = ProcessBuilder(
                    "logcat",
                    "--pid=${android.os.Process.myPid()}",
                    "-v",
                    "brief",
                    "-T",
                    "1",
                    "*:S",
                    "Libretro Core:I",
                    "libretrodroid:I",
                    "AndroidRuntime:E",
                    "libc:F",
                ).redirectErrorStream(true).start()
                process = started
                var count = 0
                var last = ""
                started.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (stop.get() || count >= MAX_CORE_LINES) break
                        val clean = line.trim()
                        if (clean.isEmpty() || clean == last) continue
                        last = clean
                        count++
                        GameLog.log("núcleo", clean.take(300))
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "tandem-core-log"
            start()
        }
        return Closeable {
            stop.set(true)
            process?.destroy()
        }
    }

    /** Na abertura do modo jogo: diz se a última partida terminou mal e por quê (o Android guarda o motivo). */
    fun reportPreviousCrashes(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean("open", false)) {
            GameLog.log(
                "sessão",
                "o último jogo (núcleo=${prefs.getString("core", "?")}, jogo=${prefs.getString("rom", "?")}) não terminou normalmente",
            )
            prefs.edit { putBoolean("open", false) }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val since = maxOf(prefs.getLong("exit_ts", 0L), System.currentTimeMillis() - LOOKBACK_MS)
        val exits = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 10) }
            .getOrNull()
            .orEmpty()
            .filter { it.timestamp > since }
            .sortedBy { it.timestamp }
        exits.filter { it.reason in reportedReasons }.forEach { info ->
            GameLog.log(
                "crash",
                "o app morreu: motivo=${reasonName(info.reason)}, processo=${info.processName}, " +
                    "status=${info.status}, descrição=${info.description ?: "-"}",
            )
            traceHints(info).forEach { GameLog.log("crash", "  rastro: $it") }
        }
        exits.lastOrNull()?.let { last -> prefs.edit { putLong("exit_ts", last.timestamp) } }
    }

    private val reportedReasons = setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_SIGNALED -> "morto por sinal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "pouca memória"
        ApplicationExitInfo.REASON_CRASH -> "erro do app (Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo (núcleo ou motor)"
        ApplicationExitInfo.REASON_ANR -> "app travado (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falha ao iniciar"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excessivo de recursos"
        else -> "código $reason"
    }

    /** O rastro de um crash nativo é binário; daqui saem só os textos úteis (sinal, bibliotecas, função). */
    private fun traceHints(info: ApplicationExitInfo): List<String> = runCatching {
        info.traceInputStream?.use { readHints(it) }.orEmpty()
    }.getOrDefault(emptyList())

    private fun readHints(stream: InputStream): List<String> {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() < MAX_TRACE_BYTES) {
            val read = stream.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        val text = String(buffer.toByteArray(), Charsets.ISO_8859_1)
        val signals = signalPattern.findAll(text).map { it.value }.distinct().take(MAX_TRACE_HINTS)
        val libraries = libraryPattern.findAll(text).map { it.value }.distinct().toList()
        val (app, system) = libraries.partition { it.startsWith("/data/") }
        return (signals + app + system.take(MAX_SYSTEM_LIBRARIES)).map { it.take(160) }.toList()
    }
}
