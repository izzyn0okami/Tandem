package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import kotlinx.coroutines.launch

/** Escolha do núcleo de cada console (e download dos que faltam). As opções de cada núcleo ficam no menu de pausa do jogo. */
@Composable
internal fun CoresScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val platforms = remember { GamePlatforms.all.filter { it.launch == LaunchMode.LIBRETRO && CoreRegistry.cores(it).size > 1 } }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.game_cores_title), color = GameColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_cores_note), color = GameColors.textDim, fontSize = 12.sp)
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 12.sp, maxLines = 2)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            items(platforms, key = { it.slug }) { platform ->
                val chosen = remember(tick) { CoreRegistry.chosen(context, platform) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(platform.name, color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    CoreRegistry.cores(platform).forEach { core ->
                        val installed = remember(tick) { CoreInstaller.isInstalled(context, core.id) }
                        FocusSurface(
                            onClick = {
                                CoreRegistry.setChosen(context, platform, core.id)
                                tick++
                                if (!installed) {
                                    scope.launch {
                                        message = context.getString(R.string.game_cores_downloading, core.label)
                                        val result = CoreInstaller.ensureInstalled(context, core.id)
                                        message = result.fold(
                                            onSuccess = { context.getString(R.string.game_cores_installed, core.label) },
                                            onFailure = { context.getString(R.string.game_cores_failed, core.label, it.message ?: "") },
                                        )
                                        tick++
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (core.id == chosen) "●" else "○",
                                    color = if (core.id == chosen) GameColors.accent else GameColors.textDim,
                                    fontSize = 16.sp,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(core.label, color = GameColors.text, fontSize = 14.sp)
                                    Text(core.note, color = GameColors.textDim, fontSize = 12.sp)
                                }
                                Text(
                                    text = stringResource(if (installed) R.string.game_cores_ready else R.string.game_cores_missing),
                                    color = if (installed) GameColors.success else GameColors.textDim,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
