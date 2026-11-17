package app.lawnchair.gamemode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R

/** Opções do núcleo em execução: A passa para o próximo valor, B fecha. As escolhas ficam salvas por núcleo. */
@Composable
internal fun CoreOptionsPanel(
    coreLabel: String,
    options: List<CoreOption>,
    onChange: (CoreOption, String) -> Unit,
    onReset: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(modifier = Modifier.fillMaxSize().background(GameColors.scrim), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .width(560.dp)
                .background(GameColors.panel, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.game_core_options_title, coreLabel),
                color = GameColors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.game_core_options_note), color = GameColors.textDim, fontSize = 12.sp)
            if (options.isEmpty()) {
                Text(stringResource(R.string.game_core_options_empty), color = GameColors.textDim, fontSize = 14.sp)
            }
            val grouped = remember(options) { groupByCategory(options) }
            var firstDrawn = false
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                grouped.forEach { (category, items) ->
                    item(key = "header_${category.name}") {
                        Text(
                            text = stringResource(category.titleRes).uppercase(),
                            color = GameColors.textDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    itemsIndexed(items, key = { _, option -> option.key }) { _, option ->
                        val isFirst = !firstDrawn
                        if (isFirst) firstDrawn = true
                        FocusSurface(
                            onClick = { onChange(option, option.next()) },
                            modifier = Modifier.fillMaxWidth().then(if (isFirst) Modifier.focusRequester(first) else Modifier),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = option.label,
                                    color = GameColors.text,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(text = option.current, color = GameColors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            GameButton(stringResource(R.string.game_core_options_reset), onReset, compact = true)
        }
    }
}

/**
 * As opções vêm direto do núcleo (RetroArch/libretro) sem categoria nenhuma, então aqui a gente
 * adivinha a categoria pelo nome da chave/rótulo — só pra agrupar na tela, nada é descartado.
 */
private enum class CoreOptionCategory(val titleRes: Int) {
    VIDEO(R.string.game_core_category_video),
    AUDIO(R.string.game_core_category_audio),
    CONTROLS(R.string.game_core_category_controls),
    SYSTEM(R.string.game_core_category_system),
    OTHER(R.string.game_core_category_other),
}

private val videoHints = listOf(
    "video", "gpu", "resolution", "screen", "filter", "shader", "aspect", "crop",
    "deinterlac", "render", "widescreen", "hires", "gfx", "vsync", "overlay", "border",
)
private val audioHints = listOf("audio", "sound", "music", "volume", "mute", "mixer", "channel")
private val controlHints = listOf("input", "button", "stick", "analog", "rumble", "touch", "lightgun", "mouse", "controller", "gamepad", "vibrat")
private val systemHints = listOf(
    "bios", "region", "system", "cpu", "timing", "overclock", "speed", "cheat", "memory",
    "rtc", "save", "autosave", "frameskip", "fastforward", "core", "emulat", "boot", "rom", "disk", "cd_",
)

private fun categoryFor(option: CoreOption): CoreOptionCategory {
    val text = "${option.key} ${option.label}".lowercase()
    return when {
        videoHints.any { text.contains(it) } -> CoreOptionCategory.VIDEO
        audioHints.any { text.contains(it) } -> CoreOptionCategory.AUDIO
        controlHints.any { text.contains(it) } -> CoreOptionCategory.CONTROLS
        systemHints.any { text.contains(it) } -> CoreOptionCategory.SYSTEM
        else -> CoreOptionCategory.OTHER
    }
}

private fun groupByCategory(options: List<CoreOption>): List<Pair<CoreOptionCategory, List<CoreOption>>> {
    val byCategory = options.groupBy(::categoryFor)
    return CoreOptionCategory.entries.mapNotNull { category ->
        byCategory[category]?.takeIf { it.isNotEmpty() }?.let { category to it }
    }
}
