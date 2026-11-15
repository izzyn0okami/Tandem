package app.lawnchair.gamemode

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import java.io.File

/** Um título do servidor e todas as versões dele (regiões, revisões) na mesma plataforma. */
internal data class TitleGroup(val title: String, val versions: List<RemoteGame>) {
    val id: Long get() = versions.first().id

    /** A versão usada para mostrar a capa: a primeira que tem imagem. */
    val coverGame: RemoteGame get() = versions.firstOrNull { it.coverPath != null || it.coverUrl != null } ?: versions.first()
}

private val tagPattern = Regex("""\s*[(\[][^)\]]*[)\]]""")
private val tagContent = Regex("""[(\[][^)\]]*[)\]]""")

private fun titleKey(game: RemoteGame): String = game.title.replace(tagPattern, "").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { game.title.lowercase() }

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
internal fun groupVersions(games: List<RemoteGame>): List<TitleGroup> = games.groupBy { titleKey(it) }.values
    .map { versions ->
        val first = versions.first()
        TitleGroup(
            title = first.title.replace(tagPattern, "").trim().ifEmpty { first.title },
            versions = versions.sortedWith(compareBy({ regionRank(it) }, { versionLabel(it) })),
        )
    }
    .sortedBy { it.title.lowercase() }

/** Lista das versões de um jogo: A baixa a escolhida, B volta. */
@Composable
internal fun VersionPicker(group: TitleGroup, root: File, diskTick: Int, progress: Map<Long, Long>, onDownload: (RemoteGame) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(group.id) { runCatching { first.requestFocus() } }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(group.title, color = GameColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.game_romm_pick_hint), color = GameColors.textDim, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(group.versions, key = { _, game -> game.id }) { index, game ->
                val downloaded = remember(game.id, diskTick) { game.destination(root).isFile }
                val bytes = progress[game.id]
                FocusSurface(
                    onClick = { if (!downloaded && bytes == null) onDownload(game) },
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
                            downloaded -> Text(stringResource(R.string.game_romm_downloaded), color = GameColors.success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            percent != null -> Text(stringResource(R.string.game_romm_downloading, percent), color = GameColors.accent, fontSize = 12.sp)
                            else -> Text(stringResource(R.string.game_romm_download), color = GameColors.accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
