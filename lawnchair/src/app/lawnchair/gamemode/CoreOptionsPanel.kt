package app.lawnchair.gamemode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R

/** Navegação de opções do núcleo: categorias e opções ocupam telas próprias, sem janela flutuante. */
@Composable
internal fun CoreOptionsPanel(
    coreLabel: String,
    options: List<CoreOption>,
    selectedCategory: CoreOptionCategory?,
    onSelectCategory: (CoreOptionCategory) -> Unit,
    onBack: () -> Unit,
    onChange: (CoreOption, String) -> Unit,
    onReset: () -> Unit,
) {
    val first = remember(selectedCategory) { FocusRequester() }
    val grouped = remember(options) { groupByCategory(options) }
    val categories = remember(grouped) {
        CoreOptionCategory.entries.filter { grouped[it].orEmpty().isNotEmpty() }
    }
    LaunchedEffect(selectedCategory) { runCatching { first.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize().background(GameColors.panel),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GameIconButton(
                    label = if (selectedCategory == null) "Fechar opções do núcleo" else "Voltar às categorias",
                    symbol = "←",
                    onClick = onBack,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedCategory == null) "Opções do núcleo" else selectedCategory.label,
                        color = GameColors.text,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = coreLabel,
                        color = GameColors.textDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = stringResource(R.string.game_core_options_note),
                color = GameColors.textDim,
                fontSize = 12.sp,
            )

            if (options.isEmpty()) {
                Text(
                    text = stringResource(R.string.game_core_options_empty),
                    color = GameColors.textDim,
                    fontSize = 14.sp,
                )
            } else if (selectedCategory == null) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(categories, key = { _, category -> category.name }) { index, category ->
                        val count = grouped[category].orEmpty().size
                        FocusSurface(
                            onClick = { onSelectCategory(category) },
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(category.label, color = GameColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text("$count opções", color = GameColors.textDim, fontSize = 11.sp)
                                }
                                Text("›", color = GameColors.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                val categoryOptions = grouped[selectedCategory].orEmpty()
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(categoryOptions, key = { _, option -> option.key }) { index, option ->
                        FocusSurface(
                            onClick = { onChange(option, option.next()) },
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
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
                                Text(
                                    text = option.current,
                                    color = GameColors.accent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GameButton(
                    label = stringResource(R.string.game_core_options_reset),
                    onClick = onReset,
                    compact = true,
                )
                GameButton(
                    label = if (selectedCategory == null) "Fechar opções" else "Voltar às categorias",
                    onClick = onBack,
                    compact = true,
                )
            }
        }
    }
}

/** As opções vêm do próprio núcleo; a classificação só organiza a apresentação e nunca descarta itens. */
internal enum class CoreOptionCategory(val label: String) {
    VIDEO("Vídeo e imagem"),
    AUDIO("Áudio"),
    CONTROLS("Controles e entrada"),
    SYSTEM("Sistema e desempenho"),
    SAVES("Saves e memória"),
    NETWORK("Rede e serviços"),
    OTHER("Outros"),
}

private val videoHints = listOf(
    "video", "gpu", "resolution", "screen", "filter", "shader", "aspect", "crop",
    "deinterlac", "render", "widescreen", "hires", "gfx", "vsync", "overlay", "border",
)
private val audioHints = listOf("audio", "sound", "music", "volume", "mute", "mixer", "channel")
private val controlHints = listOf("input", "button", "stick", "analog", "rumble", "touch", "lightgun", "mouse", "controller", "gamepad", "vibrat")
private val saveHints = listOf("save", "sram", "memory card", "memcard", "autosave", "savefile", "savestate", "state slot")
private val networkHints = listOf("network", "netplay", "wifi", "wi-fi", "http", "online", "socket", "server")
private val systemHints = listOf(
    "bios", "region", "system", "cpu", "timing", "overclock", "speed", "cheat",
    "rtc", "frameskip", "fastforward", "core", "emulat", "boot", "rom", "disk", "cd_",
)

private fun categoryFor(option: CoreOption): CoreOptionCategory {
    val text = "${option.key} ${option.label}".lowercase()
    return when {
        videoHints.any { text.contains(it) } -> CoreOptionCategory.VIDEO
        audioHints.any { text.contains(it) } -> CoreOptionCategory.AUDIO
        controlHints.any { text.contains(it) } -> CoreOptionCategory.CONTROLS
        saveHints.any { text.contains(it) } -> CoreOptionCategory.SAVES
        networkHints.any { text.contains(it) } -> CoreOptionCategory.NETWORK
        systemHints.any { text.contains(it) } -> CoreOptionCategory.SYSTEM
        else -> CoreOptionCategory.OTHER
    }
}

private fun groupByCategory(options: List<CoreOption>): Map<CoreOptionCategory, List<CoreOption>> =
    options.groupBy(::categoryFor)
