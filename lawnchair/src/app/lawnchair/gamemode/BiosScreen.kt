package app.lawnchair.gamemode

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tela de BIOS: mostra o que falta por console e traz as três formas de adicionar. */
@Composable
internal fun BiosScreen(rootPath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var kitTarget by remember { mutableStateOf<GamePlatform?>(null) }

    val kitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val platform = kitTarget ?: return@rememberLauncherForActivityResult
        kitTarget = null
        val path = uri?.let(StorageAccess::treeUriToPath)
        if (uri != null && path == null) {
            message = context.getString(R.string.game_mode_status_folder_invalid)
            return@rememberLauncherForActivityResult
        }
        if (path == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val copied = withContext(Dispatchers.IO) { BiosManager.importKit(context, platform, File(path)) }
                message = context.getString(R.string.game_bios_kit_imported, copied)
            } catch (error: Exception) {
                GameLog.error("bios", "falha ao importar kit de ${platform.slug}", error)
                message = "Kit não importado: ${error.message ?: "erro inesperado"}"
            }
            tick++
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        val input = context.contentResolver.openInputStream(uri)
                        name != null && input != null && input.use { BiosManager.import(context, name, it) } != null
                    }.onFailure { GameLog.error("bios", "arquivo BIOS rejeitado durante importação", it) }
                        .getOrDefault(false)
                }
            }
            message = context.getString(R.string.game_bios_added, added, uris.size)
            if (added < uris.size) message += " ${uris.size - added} arquivo(s) rejeitado(s); consulte o log."
            tick++
        }
    }

    val integrityByBios = remember(tick) { BiosManager.validateLocalFirmware(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.game_bios_title), color = GameColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton(stringResource(R.string.game_bios_import), onClick = { picker.launch(arrayOf("*/*")) }, compact = true, enabled = !busy)
            GameButton(
                stringResource(R.string.game_bios_scan_folder),
                compact = true,
                enabled = !busy,
                onClick = {
                    scope.launch {
                        val added = withContext(Dispatchers.IO) { BiosManager.importFromFolder(context, File(rootPath)) }
                        message = context.getString(R.string.game_bios_found, added, File(rootPath, "bios").absolutePath)
                        tick++
                    }
                },
            )
            GameButton(
                stringResource(R.string.game_bios_download),
                compact = true,
                enabled = !busy && RomMSettings.isConnected(context),
                onClick = {
                    scope.launch {
                        busy = true
                        message = context.getString(R.string.game_bios_downloading)
                        try {
                            val client = RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                            val added = withContext(Dispatchers.IO) { BiosManager.downloadFromServer(context, client) }
                            message = context.getString(R.string.game_bios_downloaded, added)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            GameLog.error("bios", "falha ao baixar BIOS do servidor", e)
                            message = context.getString(R.string.game_bios_error, e.message ?: e.toString())
                        } finally {
                            busy = false
                            tick++
                        }
                    }
                },
            )
        }
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 12.sp, maxLines = 2)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(BiosManager.platformsWithKit(), key = { "kit-" + it.slug }) { platform ->
                val (ok, total) = remember(tick) { BiosManager.kitStatus(context, platform) }
                val ready = ok == total
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${platform.name} · ${stringResource(R.string.game_bios_kit_required)}",
                        color = if (ready) GameColors.success else GameColors.danger,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.game_bios_kit_note, ok, total),
                        color = GameColors.textDim,
                        fontSize = 12.sp,
                    )
                    GameButton(
                        stringResource(R.string.game_bios_kit_import),
                        compact = true,
                        onClick = {
                            kitTarget = platform
                            kitPicker.launch(null)
                        },
                    )
                }
            }
            items(BiosManager.platformsWithBios(), key = { it.slug }) { platform ->
                val ready = remember(tick) { BiosManager.isReady(context, platform) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val need = stringResource(if (platform.biosRequired) R.string.game_bios_required else R.string.game_bios_optional)
                    Text(
                        text = "${platform.name} · $need",
                        color = when {
                            ready -> GameColors.success
                            platform.biosRequired -> GameColors.danger
                            else -> GameColors.text
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    (platform.bios + platform.biosGroups.flatten()).distinctBy { it.name.lowercase() }.forEach { file ->
                        val present = remember(tick) { BiosManager.has(context, file) }
                        Text(
                            text = (if (present) "✓ " else "✗ ") + "${file.name}  —  ${file.label}",
                            color = if (present) GameColors.success else GameColors.textDim,
                            fontSize = 12.sp,
                        )
                        if (present) {
                            val integrity = integrityByBios[file.name] ?: BiosManager.FirmwareIntegrity.UNKNOWN
                            val status = when (integrity) {
                                BiosManager.FirmwareIntegrity.VERIFIED -> "MD5 confirmado"
                                BiosManager.FirmwareIntegrity.MISMATCH -> "MD5 divergente — arquivo não confiável"
                                BiosManager.FirmwareIntegrity.UNKNOWN -> "Hash de referência indisponível"
                            }
                            Text(
                                text = status,
                                color = when (integrity) {
                                    BiosManager.FirmwareIntegrity.VERIFIED -> GameColors.success
                                    BiosManager.FirmwareIntegrity.MISMATCH -> GameColors.danger
                                    BiosManager.FirmwareIntegrity.UNKNOWN -> GameColors.textDim
                                },
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
