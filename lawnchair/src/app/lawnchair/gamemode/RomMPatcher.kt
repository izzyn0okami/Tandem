package app.lawnchair.gamemode

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RomMPatcherPanel(games: List<RemoteGame>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val game = games.firstOrNull() ?: return
    val gameIds = remember(games) { games.map { it.id }.distinct() }
    val files = remember(games) { games.flatMap { it.associatedFiles }.distinctBy { it.id } }
    val baseFile = remember(gameIds, files) {
        files.firstOrNull { it.id == game.fileId }
            ?: files.firstOrNull {
                it.fileName.equals(game.fileName, ignoreCase = true) ||
                    it.fileName.equals(game.serverName, ignoreCase = true)
            }
    }
    val baseFileId = game.fileId ?: baseFile?.id
    val baseFileName = baseFile?.fileName ?: game.fileName
    val baseExtension = File(baseFileName).extension.lowercase()
    val patchFiles = remember(files) { files.filter { it.category == "patch" } }

    var supportedExtensions by remember(gameIds) {
        mutableStateOf(setOf("ips", "ups", "bps", "ppf", "rup", "aps", "bdf", "pmsr", "vcdiff", "xdelta"))
    }
    var selectedServerPatch by remember(gameIds) { mutableStateOf<RemoteAssociatedFile?>(null) }
    var localPatchUri by remember(gameIds) { mutableStateOf<Uri?>(null) }
    var localPatchName by remember(gameIds) { mutableStateOf<String?>(null) }
    var isApplying by remember(gameIds) { mutableStateOf(false) }
    var status by remember(gameIds) { mutableStateOf<String?>(null) }
    var error by remember(gameIds) { mutableStateOf<String?>(null) }
    var readyFile by remember(gameIds) { mutableStateOf<File?>(null) }
    var readyFileName by remember(gameIds) { mutableStateOf<String?>(null) }

    val openPatchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedServerPatch = null
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "patch"
            val extension = File(name).extension.lowercase()
            if (extension !in supportedExtensions) {
                error = "Formato .$extension não suportado pelo patcher deste RomM."
                localPatchUri = null
                localPatchName = null
            } else {
                selectedServerPatch = null
                localPatchUri = uri
                localPatchName = name
                error = null
                status = "Patch local selecionado: $name"
            }
        }
    }

    val saveOutputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = readyFile
        if (uri != null && file != null) {
            scope.launch {
                status = "Salvando a ROM modificada…"
                error = null
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "w")
                            ?: throw IOException("Não foi possível abrir o destino escolhido.")
                        output.use { stream ->
                            file.inputStream().use { input -> input.copyTo(stream) }
                        }
                    }
                    file.delete()
                    readyFile = null
                    status = "ROM modificada salva separadamente. A ROM original foi preservada."
                } catch (failure: Exception) {
                    error = failure.message ?: "Não foi possível salvar a ROM modificada."
                    status = null
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(gameIds, RomMSettings.url(context), RomMSettings.token(context)) {
        if (RomMSettings.isConnected(context)) {
            supportedExtensions = withContext(Dispatchers.IO) {
                runCatching {
                    RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                        .listSupportedPatchExtensions()
                }.getOrDefault(supportedExtensions)
            }
        }
    }

    val selectedExtension = localPatchName?.let { File(it).extension.lowercase() }
        ?: selectedServerPatch?.let { File(it.fileName).extension.lowercase() }
    val selectedPatchId = selectedServerPatch?.id
    val hasPatchSelection = selectedExtension != null && selectedExtension in supportedExtensions &&
        (selectedPatchId != null || localPatchUri != null)
    val suggestedName = remember(game.title, baseExtension) {
        val safeTitle = game.title.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(150)
            .ifBlank { "rom" }
        if (baseExtension.isBlank()) "$safeTitle-patched" else "$safeTitle-patched.$baseExtension"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Aplicar patch", color = GameColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "O RomM aplica o patch no servidor. O resultado será salvo como um novo arquivo no destino escolhido; a ROM original não será alterada.",
            color = GameColors.textDim,
            fontSize = 12.sp,
        )
        if (game.folder || game.isEasyRpgGame) {
            Text(
                "Este jogo é composto por vários arquivos. Selecione uma ROM/disco individual para aplicar o patch.",
                color = GameColors.textDim,
                fontSize = 12.sp,
            )
        } else if (baseFileId == null) {
            Text(
                "O RomM não informou o ID do arquivo base necessário para aplicar o patch.",
                color = GameColors.danger,
                fontSize = 12.sp,
            )
        } else {
            Text("Arquivo base: ${File(baseFileName).name}", color = GameColors.textDim, fontSize = 12.sp)
            if (patchFiles.isNotEmpty()) {
                Text("Patches associados", color = GameColors.textDim, fontSize = 12.sp)
                patchFiles.forEach { patch ->
                    val extension = File(patch.fileName).extension.lowercase()
                    if (extension in supportedExtensions) {
                        PlatformChip(
                            File(patch.fileName).name,
                            selectedServerPatch?.id == patch.id,
                        ) {
                            selectedServerPatch = patch
                            localPatchUri = null
                            localPatchName = null
                            error = null
                            status = "Patch do RomM selecionado: ${File(patch.fileName).name}"
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlatformChip("Selecionar patch do aparelho", localPatchUri != null) {
                    openPatchPicker.launch(arrayOf("*/*"))
                }
                if (selectedServerPatch != null || localPatchUri != null) {
                    PlatformChip("Limpar seleção", false) {
                        selectedServerPatch = null
                        localPatchUri = null
                        localPatchName = null
                        status = null
                        error = null
                    }
                }
            }
            (localPatchName ?: selectedServerPatch?.fileName)?.let {
                Text("Patch escolhido: ${File(it).name}", color = GameColors.textDim, fontSize = 12.sp)
            }
            Text(
                "Formatos aceitos: ${supportedExtensions.sorted().joinToString(", ") { ".$it" }}",
                color = GameColors.textDim,
                fontSize = 11.sp,
            )
            Text(
                "Um patch incompatível com a revisão da ROM pode gerar um arquivo incorreto. Preserve o original.",
                color = GameColors.textDim,
                fontSize = 11.sp,
            )
            if (hasPatchSelection) {
                PlatformChip(
                    if (isApplying) "Aplicando patch…" else "Aplicar patch",
                    false,
                ) {
                    if (!isApplying) {
                        isApplying = true
                        status = "Enviando solicitação ao RomM…"
                        error = null
                        val patchUri = localPatchUri
                        val patchId = selectedPatchId
                        val patchName = localPatchName
                        scope.launch {
                            var uploadFile: File? = null
                            try {
                                val output = withContext(Dispatchers.IO) {
                                    val directory = File(context.cacheDir, "romm-patched").apply { mkdirs() }
                                    val extension = baseExtension.ifBlank { "rom" }
                                    val target = File.createTempFile("patched-", ".$extension", directory)
                                    if (patchUri != null) {
                                        val name = patchName ?: "patch"
                                        val patchExtension = File(name).extension.lowercase()
                                        uploadFile = File.createTempFile("patch-upload-", ".$patchExtension", directory)
                                        val input = context.contentResolver.openInputStream(patchUri)
                                            ?: throw IOException("Não foi possível ler o patch selecionado.")
                                        input.use { source ->
                                            uploadFile!!.outputStream().use { destination -> source.copyTo(destination) }
                                        }
                                    }
                                    RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                                        .applyPatch(
                                            baseFileId = checkNotNull(baseFileId),
                                            patchFileId = patchId,
                                            patchUpload = uploadFile,
                                            outputFileName = suggestedName,
                                            target = target,
                                        )
                                    target
                                }
                                readyFile = output
                                readyFileName = suggestedName
                                status = "Patch aplicado. Escolha onde salvar a nova ROM."
                                saveOutputPicker.launch(suggestedName)
                            } catch (failure: Exception) {
                                error = failure.message ?: "Não foi possível aplicar o patch."
                                status = null
                            } finally {
                                uploadFile?.delete()
                                isApplying = false
                            }
                        }
                    }
                }
            } else {
                Text("Selecione um patch compatível para continuar.", color = GameColors.textDim, fontSize = 12.sp)
            }
            if (readyFile != null) {
                PlatformChip("Salvar ROM modificada", true) {
                    saveOutputPicker.launch(readyFileName ?: suggestedName)
                }
            }
        }
        status?.let { Text(it, color = GameColors.textDim, fontSize = 12.sp) }
        error?.let { Text(it, color = GameColors.danger, fontSize = 12.sp) }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
}
