package app.lawnchair.gamemode

import android.content.Context
import java.io.File

/**
 * Um jogo da biblioteca unificada: o que está no aparelho ([locals]) e/ou no servidor RomM ([group]).
 * Se há arquivo no aparelho, o botão é Jogar; senão, Baixar.
 */
internal data class LibraryEntry(
    val key: String,
    val title: String,
    val platform: GamePlatform,
    val locals: List<LocalGame>,
    val group: TitleGroup?,
    /** Jogo usado só para achar a capa (o local ou, se não baixado, um com o caminho de destino). */
    val cover: LocalGame,
) {
    /** A versão do aparelho mais recente, se houver. */
    val local: LocalGame? get() = locals.firstOrNull()

    /** Já está no aparelho: pode jogar. */
    val playable: Boolean get() = locals.isNotEmpty()

    /** Só existe no servidor: precisa baixar. */
    val remoteOnly: Boolean get() = locals.isEmpty() && group != null

    /** Versão do servidor que corresponde a um arquivo do aparelho. */
    fun localFor(version: RemoteGame): LocalGame? = locals.firstOrNull { File(it.path).name.equals(version.fileName, ignoreCase = true) }

    /** Percentual (0-100) de download em andamento de qualquer versão, ou nulo. */
    fun percent(progress: Map<Long, Long>): Int? = group?.versions?.firstNotNullOfOrNull { version ->
        progress[version.id]?.let { bytes ->
            if (version.sizeBytes > 0) (bytes * 100 / version.sizeBytes).toInt().coerceIn(0, 100) else 0
        }
    }
}

/** Junta os jogos do aparelho e os do servidor: o mesmo jogo (arquivo ou título) vira uma só entrada. */
internal fun buildEntries(context: Context, locals: List<LocalGame>, remote: List<RemoteGame>, root: File): List<LibraryEntry> {
    val groups = remote.groupBy { it.platform }.values.flatMap { groupVersions(it) }
    val groupByKey = groups.associateBy { it.key }
    val keyByFile = HashMap<String, String>()
    groups.forEach { group ->
        group.versions.forEach { keyByFile["${it.platform.slug}|${it.fileName.lowercase()}"] = group.key }
    }

    val localsByKey = HashMap<String, MutableList<LocalGame>>()
    val alone = mutableListOf<LibraryEntry>()
    for (local in locals) {
        val slug = local.platform.slug
        val key = keyByFile["$slug|${File(local.path).name.lowercase()}"]
            ?: "$slug|${normalizeTitle(local.title)}".takeIf { it in groupByKey }
        if (key != null) {
            localsByKey.getOrPut(key) { mutableListOf() }.add(local)
        } else {
            alone += LibraryEntry("local|${local.path}", local.title, local.platform, listOf(local), null, local)
        }
    }

    val merged = groups.map { group ->
        val platform = group.versions.first().platform
        val mine = localsByKey[group.key].orEmpty().sortedByDescending { PlayHistory.get(context, it.path).lastPlayedMs }
        val cover = mine.firstOrNull() ?: LocalGame(group.title, platform, group.coverGame.destination(root).path)
        LibraryEntry(group.key, group.title, platform, mine, group, cover)
    }
    return (merged + alone).sortedWith(compareBy({ it.platform.sortOrder }, { it.title.lowercase() }))
}
