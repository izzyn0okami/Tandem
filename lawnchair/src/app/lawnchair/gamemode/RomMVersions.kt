package app.lawnchair.gamemode

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R

/** Um título do servidor e todas as versões dele (regiões, revisões) na mesma plataforma. */
internal data class TitleGroup(val title: String, val versions: List<RemoteGame>) {
    val id: Long get() = versions.first().id

    /** A versão usada para mostrar a capa: a primeira que tem imagem. */
    val coverGame: RemoteGame get() = versions.firstOrNull { it.coverPath != null || it.coverUrl != null } ?: versions.first()
}

private val tagPattern = Regex("""\s*[(\[][^)\]]*[)\]]""")
private val tagContent = Regex("""[(\[][^)\]]*[)\]]""")

/** Título sem as marcas de região/revisão, em minúsculas e só letras e números (para comparar jogos). */
internal fun normalizeTitle(title: String): String = title.replace(tagPattern, "").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { title.lowercase() }

private fun titleKey(game: RemoteGame): String = normalizeTitle(game.title)

/** Chave única do título dentro da plataforma, igual à usada para juntar com os jogos do aparelho. */
internal val TitleGroup.key: String get() = "${versions.first().platform.slug}|${normalizeTitle(title)}"

/** Texto da versão: as marcas do nome do arquivo, como "(USA) (Rev 1)". */
internal fun versionLabel(game: RemoteGame): String = tagContent.findAll(game.fileName.substringBeforeLast('.')).joinToString(" ") { it.value }.ifEmpty { game.fileName }

private fun regionRank(game: RemoteGame): Int {
    val label = versionLabel(game)
    return when {
        label.contains("USA") -> 0
        label.contains("World") -> 1
        label.contains("Europe") -> 2
        else -> 3
    }
}

/** Junta as versões do mesmo jogo (mesmo título sem as marcas de região/revisão) numa só entrada. */
internal fun groupVersions(games: List<RemoteGame>): List<TitleGroup> = games.groupBy { it.platform }.values
    .flatMap { platformGames ->
        val parent = platformGames.associate { it.id to it.id }.toMutableMap()
        fun find(id: Long): Long {
            var current = id
            while (parent[current] != current) current = parent[current]!!
            val root = current
            current = id
            while (parent[current] != current) {
                val next = parent[current]!!
                parent[current] = root
                current = next
            }
            return root
        }
        fun union(first: Long, second: Long) {
            val a = find(first)
            val b = find(second)
            if (a != b) parent[b] = a
        }
        val ids = parent.keys
        platformGames.forEach { game ->
            game.siblingIds.filter { it in ids }.forEach { siblingId -> union(game.id, siblingId) }
        }
        // Mantém o agrupamento por título como fallback, mas respeita primeiro
        // as relações explícitas de sibling_roms que o RomM fornece.
        platformGames.groupBy { titleKey(it) }.values.forEach { sameTitle ->
            sameTitle.drop(1).forEach { union(sameTitle.first().id, it.id) }
        }
        platformGames.groupBy { find(it.id) }.values.map { versions ->
            val mainId = versions.firstNotNullOfOrNull { it.mainSiblingId }
            val first = versions.firstOrNull { it.id == mainId }
                ?: versions.firstOrNull { it.userData?.isMainSibling == true }
                ?: versions.first()
            TitleGroup(
                title = first.title.replace(tagPattern, "").trim().ifEmpty { first.title },
                versions = versions.sortedWith(compareBy({ regionRank(it) }, { versionLabel(it) })),
            )
        }
    }
    .sortedBy { it.title.lowercase() }

/** Versões de um jogo no detalhe: A joga a versão baixada ou baixa a que ainda não está no aparelho. */
@Composable
internal fun VersionList(
    entry: LibraryEntry,
    progress: Map<Long, Long>,
    firstFocus: FocusRequester?,
    onPlay: (LocalGame) -> Unit,
    onDownload: (RemoteGame) -> Unit,
) {
    val context = LocalContext.current
    val group = entry.group ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(stringResource(R.string.game_romm_versions_title), color = GameColors.textDim, fontSize = 12.sp)
        group.versions.forEachIndexed { index, game ->
            val local = entry.localFor(game)
            val bytes = progress[game.id]
            FocusSurface(
                onClick = {
                    when {
                        local != null -> onPlay(local)
                        bytes == null -> onDownload(game)
                    }
                },
                modifier = Modifier.fillMaxWidth().then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(versionLabel(game), color = GameColors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(game.fileName, color = GameColors.textDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (game.sizeBytes > 0) {
                        Text(Formatter.formatShortFileSize(context, game.sizeBytes), color = GameColors.textDim, fontSize = 12.sp)
                    }
                    val percent = bytes?.let { if (game.sizeBytes > 0) (it * 100 / game.sizeBytes).toInt().coerceIn(0, 100) else 0 }
                    when {
                        local != null -> Text(stringResource(R.string.game_play), color = GameColors.success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        percent != null -> Text(stringResource(R.string.game_romm_downloading, percent), color = GameColors.accent, fontSize = 12.sp)
                        else -> Text(stringResource(R.string.game_romm_download), color = GameColors.accent, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
