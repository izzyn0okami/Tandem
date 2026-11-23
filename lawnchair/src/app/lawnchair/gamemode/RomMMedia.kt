package app.lawnchair.gamemode

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROMM_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "tif", "tiff", "bmp", "avif")
private val ROMM_DOCUMENT_EXTENSIONS = setOf("pdf", "md", "txt", "html", "htm")

@Composable
internal fun RomMMediaPanel(game: RemoteGame) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenshots = remember(game.id, game.associatedFiles) {
        game.associatedFiles.filter {
            it.category == "screenshot" && File(it.fileName).extension.lowercase() in ROMM_IMAGE_EXTENSIONS
        }
    }
    val manuals = remember(game.id, game.associatedFiles) {
        game.associatedFiles.filter {
            it.category == "manual" && File(it.fileName).extension.lowercase() in ROMM_DOCUMENT_EXTENSIONS
        }
    }
    val walkthroughs = remember(game.id, game.associatedFiles) {
        game.associatedFiles.filter {
            it.category == "walkthrough" && File(it.fileName).extension.lowercase() in ROMM_DOCUMENT_EXTENSIONS
        }
    }
    var tracks by remember(game.id) { mutableStateOf<List<RemoteSoundtrackTrack>>(emptyList()) }
    var tracksLoading by remember(game.id) { mutableStateOf(false) }
    var tracksError by remember(game.id) { mutableStateOf<String?>(null) }
    var selectedFile by remember(game.id) { mutableStateOf<RemoteAssociatedFile?>(null) }
    var loadedFile by remember(game.id) { mutableStateOf<File?>(null) }
    var previewError by remember(game.id) { mutableStateOf<String?>(null) }
    var previewText by remember(game.id) { mutableStateOf<String?>(null) }
    var previewBitmap by remember(game.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var audioReady by remember(game.id) { mutableStateOf(false) }
    var audioPlaying by remember(game.id) { mutableStateOf(false) }
    var audioPlayer by remember(game.id) { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(game.id, RomMSettings.url(context), RomMSettings.token(context)) {
        if (!RomMSettings.isConnected(context)) return@LaunchedEffect
        tracksLoading = true
        tracksError = null
        try {
            tracks = withContext(Dispatchers.IO) {
                RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context)).listSoundtracks(game.id)
            }
        } catch (error: Exception) {
            tracksError = error.message ?: "Não foi possível consultar as trilhas."
        } finally {
            tracksLoading = false
        }
    }

    LaunchedEffect(selectedFile?.id, game.id) {
        val file = selectedFile ?: return@LaunchedEffect
        loadedFile = null
        previewError = null
        previewText = null
        previewBitmap = null
        audioReady = false
        audioPlaying = false
        try {
            val target = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "romm-media").apply { mkdirs() }
                File(directory, "${game.id}-${file.id}-${File(file.fileName).name}")
            }
            withContext(Dispatchers.IO) {
                RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                    .downloadAssociatedFile(file, target)
            }
            loadedFile = target
            when (File(file.fileName).extension.lowercase()) {
                "png", "jpg", "jpeg", "webp", "gif", "tif", "tiff", "bmp", "avif" -> {
                    previewBitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(target.absolutePath) }
                    if (previewBitmap == null) previewError = "O Android não conseguiu decodificar esta imagem."
                }
                "pdf" -> {
                    previewBitmap = withContext(Dispatchers.IO) { renderFirstPdfPage(target) }
                    if (previewBitmap == null) previewError = "Não foi possível abrir a primeira página deste PDF."
                }
                "md", "txt", "html", "htm" -> {
                    previewText = withContext(Dispatchers.IO) {
                        target.inputStream().bufferedReader().use { it.readText().take(500_000) }
                    }
                }
            }
        } catch (error: Exception) {
            previewError = error.message ?: "Falha ao carregar o arquivo do RomM."
        }
    }

    DisposableEffect(loadedFile, selectedFile?.category) {
        val file = loadedFile
        if (file != null && selectedFile?.category == "soundtrack") {
            val player = MediaPlayer()
            audioPlayer = player
            player.setOnPreparedListener {
                audioReady = true
                it.start()
                audioPlaying = true
            }
            player.setOnCompletionListener { audioPlaying = false }
            player.setOnErrorListener { _, what, extra ->
                previewError = "Falha na reprodução de áudio (código $what/$extra)."
                audioReady = false
                audioPlaying = false
                true
            }
            runCatching {
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            }.onFailure {
                previewError = it.message ?: "Não foi possível preparar o áudio."
            }
            onDispose {
                runCatching { player.stop() }
                player.release()
                if (audioPlayer === player) audioPlayer = null
                audioReady = false
                audioPlaying = false
            }
        } else {
            onDispose { }
        }
    }

    if (screenshots.isEmpty() && manuals.isEmpty() && walkthroughs.isEmpty() && tracks.isEmpty() && !tracksLoading) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Mídias e documentos do RomM", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        if (screenshots.isNotEmpty()) {
            Text("Screenshots", color = GameColors.textDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                screenshots.take(12).forEach { file ->
                    PlatformChip(File(file.fileName).name, selectedFile?.id == file.id) {
                        selectedFile = file
                    }
                }
            }
        }
        if (manuals.isNotEmpty()) {
            Text("Manuais", color = GameColors.textDim, fontSize = 13.sp)
            manuals.forEach { file ->
                PlatformChip(File(file.fileName).name, selectedFile?.id == file.id) { selectedFile = file }
            }
        }
        if (walkthroughs.isNotEmpty()) {
            Text("Walkthroughs", color = GameColors.textDim, fontSize = 13.sp)
            walkthroughs.forEach { file ->
                PlatformChip(File(file.fileName).name, selectedFile?.id == file.id) { selectedFile = file }
            }
        }
        if (tracksLoading) Text("Consultando trilhas sonoras…", color = GameColors.textDim, fontSize = 12.sp)
        tracksError?.let { Text("Trilhas indisponíveis: $it", color = GameColors.danger, fontSize = 12.sp) }
        if (tracks.isNotEmpty()) {
            Text("Trilha sonora", color = GameColors.textDim, fontSize = 13.sp)
            tracks.forEach { track ->
                val label = listOfNotNull(
                    track.trackNumber?.let { "%02d".format(it) },
                    track.title ?: File(track.fileName).name,
                    track.artist,
                ).joinToString(" · ")
                PlatformChip(label, selectedFile?.id == track.fileId) {
                    selectedFile = RemoteAssociatedFile(
                        id = track.fileId,
                        fileName = track.fileName,
                        sizeBytes = track.sizeBytes,
                        category = "soundtrack",
                        isTopLevel = false,
                    )
                }
            }
        }

        loadedFile?.let { file ->
            when {
                selectedFile?.category == "soundtrack" -> {
                    Text(
                        selectedFile?.fileName.orEmpty(),
                        color = GameColors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlatformChip(if (audioPlaying) "Pausar" else "Reproduzir", audioPlaying) {
                            audioPlayer?.let { player ->
                                if (player.isPlaying) {
                                    player.pause()
                                    audioPlaying = false
                                } else if (audioReady) {
                                    player.start()
                                    audioPlaying = true
                                }
                            }
                        }
                        if (!audioReady) Text("Preparando áudio…", color = GameColors.textDim, fontSize = 12.sp)
                    }
                }
                previewBitmap != null -> {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = selectedFile?.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    )
                    if (File(file.name).extension.lowercase() == "pdf") {
                        Text("Visualização integrada · primeira página do PDF", color = GameColors.textDim, fontSize = 11.sp)
                    }
                }
                previewText != null -> {
                    Text(
                        text = previewText!!,
                        color = GameColors.text,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                    if (File(file.name).extension.lowercase() in setOf("html", "htm")) {
                        Text("HTML exibido como texto; scripts e conteúdo ativo não são executados.", color = GameColors.textDim, fontSize = 11.sp)
                    }
                }
            }
        }
        previewError?.let { Text(it, color = GameColors.danger, fontSize = 12.sp) }
        if (selectedFile != null && loadedFile == null && previewError == null) {
            Text("Carregando ${File(selectedFile!!.fileName).name}…", color = GameColors.textDim, fontSize = 12.sp)
        }
    }
}

private fun renderFirstPdfPage(file: File): android.graphics.Bitmap? {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    descriptor.use { parcel ->
        PdfRenderer(parcel).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val width = page.width.coerceAtMost(1800)
                val height = (page.height.toFloat() * width / page.width).toInt().coerceAtLeast(1)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}
