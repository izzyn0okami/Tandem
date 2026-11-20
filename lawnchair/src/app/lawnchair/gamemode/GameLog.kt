package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Registro local do modo jogo (RomM, saves, erros), para o usuário exportar e enviar quando algo
 * não funciona. Nunca grava senhas nem tokens.
 */
object GameLog {

    private const val FILE = "game_log.txt"
    private const val MAX_BYTES = 256 * 1024
    private const val SESSION_KEY_LENGTH = 8

    @Volatile
    private var dir: File? = null

    @Volatile
    private var sessionId: String? = null

    private val localStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val utcStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun init(context: Context) {
        dir = context.applicationContext.filesDir
        if (sessionId == null) sessionId = UUID.randomUUID().toString().take(SESSION_KEY_LENGTH)
        MemoryDiagnostics.install(context)
        log("sessão", "iniciada; fuso do aparelho=${TimeZone.getDefault().id}")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val file = dir?.let { File(it, FILE) } ?: return
        runCatching {
            if (file.length() > MAX_BYTES) {
                file.writeText(file.readText().takeLast(MAX_BYTES / 2))
            }
            val now = Date()
            val local = localStamp.format(now)
            val utc = utcStamp.format(now)
            val elapsed = SystemClock.elapsedRealtimeNanos() / 1_000_000
            val session = sessionId ?: "sem-sessao"
            file.appendText("$local [UTC $utc] [mono +${elapsed}ms] [sessão $session] [$tag] $message\n")
        }
    }

    fun error(tag: String, message: String, error: Throwable) {
        val stack = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString().take(8_000)
        log(tag, "$message: ${error.javaClass.name}: ${error.message}\n$stack")
    }

    private fun installedCores(context: Context): String = CoreInstaller.coresDir(context)
        .listFiles { file -> file.name.endsWith("_libretro_android.so") }
        .orEmpty()
        .joinToString { it.name.removeSuffix("_libretro_android.so") }
        .ifEmpty { "nenhum" }

    /** Texto completo para exportar: cabeçalho do aparelho + registro. */
    @Synchronized
    fun export(context: Context): String {
        val appContext = context.applicationContext
        val file = File(appContext.filesDir, FILE)
        val packageInfo = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()
        val header = buildString {
            appendLine("Tandem – registro do modo jogo")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Versão do app: ${packageInfo?.versionName ?: "desconhecida"} (versionCode ${packageInfo?.versionCode ?: "desconhecido"})")
            appendLine("Fuso do aparelho: ${TimeZone.getDefault().id}; offset atual=${localStamp.timeZone.getOffset(System.currentTimeMillis()) / 3_600_000.0}h")
            appendLine("Sessão atual: ${sessionId ?: "não inicializada"}")
            appendLine("Relógio UTC atual: ${utcStamp.format(Date())}")
            appendLine("Relógio monotônico desde boot: ${SystemClock.elapsedRealtimeNanos() / 1_000_000} ms")
            appendLine("Servidor RomM: ${RomMSettings.url(context) ?: "não conectado"}")
            appendLine("Rede local permitida: ${localNetworkGranted(context)}")
            appendLine("Núcleos instalados: ${installedCores(context)}")
            appendLine("Espaço livre: ${context.filesDir.usableSpace / (1024 * 1024)} MB")
            appendLine("----")
        }
        return header + (runCatching { file.readText() }.getOrDefault("(registro vazio)\n"))
    }
}

/** Devolve uma ação que abre o seletor "salvar como" e grava o registro no arquivo escolhido. */
@Composable
internal fun rememberLogExporter(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(GameLog.export(context).toByteArray()) }
        }
    }
    return remember(launcher) { { launcher.launch("tandem-log.txt") } }
}

/** Abre os Ajustes de Acessibilidade do Android, onde o usuário liga o menu global do Tandem. */
@Composable
internal fun rememberAccessibilitySettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
