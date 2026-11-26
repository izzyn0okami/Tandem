package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val platforms = remember { GamePlatforms.all.filter { it.launch == LaunchMode.LIBRETRO } }
    val driverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                message = "Importando driver Vulkan…"
                val result = VulkanDriverManager.importPackage(context, uri)
                message = result.fold(
                    onSuccess = { "Driver importado: ${it.name} ${it.version}. Selecione-o abaixo." },
                    onFailure = { "Falha ao importar driver: ${it.message ?: "pacote inválido"}" },
                )
                tick++
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.game_cores_title), color = GameColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_cores_note), color = GameColors.textDim, fontSize = 12.sp)
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 12.sp, maxLines = 2)
        if (VulkanDriverManager.isLikelyAdreno()) {
            val drivers = remember(tick) { VulkanDriverManager.installed(context) }
            val selectedDriver = remember(tick) { VulkanDriverManager.selectedId(context) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Driver Vulkan (Snapdragon / Adreno)", color = GameColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Opcional e sem root. O driver do sistema continua disponível; alterações só valem ao iniciar uma nova sessão.", color = GameColors.textDim, fontSize = 12.sp)
                FocusSurface(
                    onClick = { driverPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Importar pacote Turnip / AdrenoTools (.zip)", color = GameColors.text, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
                FocusSurface(
                    onClick = {
                        VulkanDriverManager.select(context, null)
                        tick++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${if (selectedDriver == null) "●" else "○"} Driver Vulkan do sistema",
                        color = if (selectedDriver == null) GameColors.accent else GameColors.text,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                drivers.forEach { driver ->
                    FocusSurface(
                        onClick = {
                            VulkanDriverManager.select(context, driver.id)
                            tick++
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                text = "${if (selectedDriver == driver.id) "●" else "○"} ${driver.name} ${driver.version}",
                                color = if (selectedDriver == driver.id) GameColors.accent else GameColors.text,
                                fontSize = 13.sp,
                            )
                            Text("${driver.vendor} • ${driver.author} • SHA-256 ${driver.sha256.take(12)}…", color = GameColors.textDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
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
