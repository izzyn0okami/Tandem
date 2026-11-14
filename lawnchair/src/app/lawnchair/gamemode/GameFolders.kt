package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pastas que o usuário pode escolher fora do app, para compartilhar com outros apps ou guardar num cartão SD. */
enum class FolderKind(val key: String) {
    /** BIOS dos consoles (pasta de sistema dos núcleos). */
    Bios("folder_bios"),

    /** Saves de bateria, estados salvos e cópias de segurança. */
    Saves("folder_saves"),

    /** Imagens e dados baixados (capas do RomM e de outros scrapers). */
    Assets("folder_assets"),
}

object GameFolders {

    private const val FILE = "game_mode"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun defaultDir(context: Context, kind: FolderKind): File = when (kind) {
        FolderKind.Bios -> File(context.filesDir, "libretro/system")
        FolderKind.Saves -> File(context.filesDir, "libretro/saves")
        FolderKind.Assets -> File(context.filesDir, "assets")
    }

    /** Caminho escolhido pelo usuário, ou nulo se usa o padrão. */
    fun customPath(context: Context, kind: FolderKind): String? = prefs(context).getString(kind.key, null)

    /** A pasta em uso (criada se preciso). Se a escolhida não puder ser gravada, volta ao padrão. */
    fun dir(context: Context, kind: FolderKind): File {
        val custom = customPath(context, kind)?.let(::File)
        if (custom != null) {
            runCatching { custom.mkdirs() }
            if (custom.isDirectory && custom.canWrite()) return custom
        }
        return defaultDir(context, kind).apply { mkdirs() }
    }

    /**
     * Passa a usar [path] (nulo = padrão) e copia para lá o que já existia na pasta anterior, sem
     * sobrescrever. Devolve quantos arquivos foram copiados.
     */
    suspend fun change(context: Context, kind: FolderKind, path: String?): Int = withContext(Dispatchers.IO) {
        val old = dir(context, kind)
        prefs(context).edit {
            if (path == null) remove(kind.key) else putString(kind.key, path)
        }
        val new = dir(context, kind)
        if (old.canonicalPath == new.canonicalPath) return@withContext 0
        var copied = 0
        old.walkTopDown().filter { it.isFile }.forEach { file ->
            val target = File(new, file.relativeTo(old).path)
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                runCatching { file.copyTo(target) }.onSuccess { copied++ }
            }
        }
        GameLog.log("pastas", "${kind.name}: ${old.absolutePath} -> ${new.absolutePath} ($copied arquivos copiados)")
        copied
    }
}
