package app.lawnchair.gamemode

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val ROMM_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "tif", "tiff", "bmp", "avif")
private val ROMM_DOCUMENT_EXTENSIONS = setOf("pdf", "md", "txt", "html", "htm")

@Composable
internal fun RomMMediaPanel(games: List<RemoteGame>) {
    val context = LocalContext.current
    val gameIds = remember(games) { games.map { it.id }.distinct() }
    val associatedFiles = remember(games) { games.flatMap { it.associatedFiles }.distinctBy { it.id } }
    val game = games.firstOrNull() ?: return
    val hasPatchBase = !game.folder && !game.isEasyRpgGame && (
        game.fileId != null ||
            associatedFiles.any {
                (
                    it.fileName.equals(game.fileName, ignoreCase = true) ||
                        it.fileName.equals(game.serverName, ignoreCase = true)
                    ) &&
                    isRomMPlayableFileCategory(it.category)
            }
        )
    val screenshots = remember(associatedFiles) {
        associatedFiles.filter {
            it.category == "screenshot" && File(it.fileName).extension.lowercase() in ROMM_IMAGE_EXTENSIONS
        }
    }
    val manuals = remember(associatedFiles) {
        associatedFiles.filter {
            it.category == "manual" && File(it.fileName).extension.lowercase() in ROMM_DOCUMENT_EXTENSIONS
        }
    }
    val walkthroughs = remember(associatedFiles) {
        associatedFiles.filter {
            it.category == "walkthrough" && File(it.fileName).extension.lowercase() in ROMM_DOCUMENT_EXTENSIONS
        }
    }
    var screenshotThumbnails by remember(gameIds) { mutableStateOf<Map<Long, android.graphics.Bitmap>>(emptyMap()) }
    var screenshotThumbnailsLoading by remember(gameIds) { mutableStateOf(false) }
    var screenshotThumbnailsError by remember(gameIds) { mutableStateOf<String?>(null) }
    var tracks by remember(gameIds) { mutableStateOf<List<RemoteSoundtrackTrack>>(emptyList()) }
    var tracksLoading by remember(gameIds) { mutableStateOf(false) }
    var tracksError by remember(gameIds) { mutableStateOf<String?>(null) }
    var selectedFile by remember(gameIds) { mutableStateOf<RemoteAssociatedFile?>(null) }
    var loadedFile by remember(gameIds) { mutableStateOf<File?>(null) }
    var previewError by remember(gameIds) { mutableStateOf<String?>(null) }
    var previewText by remember(gameIds) { mutableStateOf<String?>(null) }
    var previewBitmap by remember(gameIds) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var audioReady by remember(gameIds) { mutableStateOf(false) }
    var audioPlaying by remember(gameIds) { mutableStateOf(false) }
    var audioDurationMs by remember(gameIds) { mutableIntStateOf(0) }
    var audioPositionMs by remember(gameIds) { mutableIntStateOf(0) }
    var pdfPageCount by remember(gameIds) { mutableIntStateOf(0) }
    var pdfPageIndex by remember(gameIds) { mutableIntStateOf(0) }
    var audioPlayer by remember(gameIds) { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(screenshots, game.id, RomMSettings.url(context), RomMSettings.token(context)) {
        screenshotThumbnailsLoading = true
        screenshotThumbnailsError = null
        try {
            val connected = RomMSettings.isConnected(context)
            val result = withContext(Dispatchers.IO) {
                val cacheDirectory = File(context.cacheDir, "romm-media-thumbnails").apply { mkdirs() }
                val sourceDirectory = File(context.cacheDir, "romm-media-thumbnail-source").apply { mkdirs() }
                val client = if (connected) {
                    RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                } else {
                    null
                }
                val thumbnails = linkedMapOf<Long, android.graphics.Bitmap>()
                var failedCount = 0
                screenshots.take(MAX_ROMM_SCREENSHOTS_IN_GALLERY).forEach { screenshot ->
                    val cacheFile = screenshotThumbnailFile(cacheDirectory, screenshot)
                    val cached = if (cacheFile.isFile && cacheFile.length() > 0L) {
                        BitmapFactory.decodeFile(cacheFile.absolutePath)
                    } else {
                        null
                    }
                    val bitmap = cached ?: if (client != null) {
                        val sourceFile = File(
                            sourceDirectory,
                            "source-${game.id}-${screenshot.id}-${screenshot.sizeBytes}.${File(screenshot.fileName).extension.lowercase()}",
                        )
                        try {
                            client.downloadAssociatedFile(
                                screenshot,
                                sourceFile,
                                MAX_ROMM_SCREENSHOT_SOURCE_BYTES,
                            )
                            val decoded = decodeScreenshotThumbnail(sourceFile)
                            if (decoded != null) {
                                val temporary = File(cacheFile.parentFile, "${cacheFile.name}.part")
                                val saved = runCatching {
                                    temporary.outputStream().use { output ->
                                        decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
                                    }
                                }.getOrDefault(false)
                                if (saved && temporary.isFile && temporary.length() > 0L) {
                                    cacheFile.delete()
                                    if (!temporary.renameTo(cacheFile)) temporary.delete()
                                } else {
                                    temporary.delete()
                                }
                            }
                            decoded
                        } catch (_: Exception) {
                            null
                        } finally {
                            sourceFile.delete()
                            File(sourceFile.parentFile, "${sourceFile.name}.part").delete()
                            File(sourceFile.parentFile, "${sourceFile.name}.range").delete()
                        }
                    } else {
                        null
                    }
                    if (bitmap != null) thumbnails[screenshot.id] = bitmap else if (cached == null) failedCount++
                }
                trimRomMScreenshotCache(cacheDirectory)
                Pair(thumbnails, failedCount)
            }
            screenshotThumbnails = result.first
            if (result.second > 0 && connected) {
                screenshotThumbnailsError = "Algumas miniaturas não puderam ser carregadas. Imagens acima de 64 MiB são ignoradas na galeria."
            } else if (!connected && result.first.isEmpty()) {
                screenshotThumbnailsError = "Conecte-se ao RomM para carregar as miniaturas ainda não armazenadas em cache."
            }
        } finally {
            screenshotThumbnailsLoading = false
        }
    }

    LaunchedEffect(gameIds, RomMSettings.url(context), RomMSettings.token(context)) {
        if (!RomMSettings.isConnected(context)) return@LaunchedEffect
        tracksLoading = true
        tracksError = null
        try {
            tracks = withContext(Dispatchers.IO) {
                val client = RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                gameIds.flatMap { romId -> client.listSoundtracks(romId) }.distinctBy { it.fileId }
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
        audioDurationMs = 0
        audioPositionMs = 0
        pdfPageCount = 0
        pdfPageIndex = 0
        try {
            val target = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "romm-media").apply { mkdirs() }
                File(directory, "${game.id}-${file.id}-${File(file.fileName).name}")
            }
            withContext(Dispatchers.IO) {
                RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                    .downloadAssociatedFile(file, target)
            }
            trimRomMMediaCache(target.parentFile, target)
            loadedFile = target
            when (File(file.fileName).extension.lowercase()) {
                "png", "jpg", "jpeg", "webp", "gif", "tif", "tiff", "bmp", "avif" -> {
                    previewBitmap = withContext(Dispatchers.IO) { decodePreviewImage(target) }
                    if (previewBitmap == null) {
                        previewError = "O Android não conseguiu decodificar esta imagem."
                    }
                }

                "pdf" -> {
                    pdfPageCount = withContext(Dispatchers.IO) { getPdfPageCount(target) }
                    previewBitmap = withContext(Dispatchers.IO) { renderPdfPage(target, 0) }
                    if (previewBitmap == null) {
                        previewError = "Não foi possível abrir este PDF."
                    }
                }

                "md", "txt", "html", "htm" -> {
                    previewText = withContext(Dispatchers.IO) {
                        readTextPreview(target)
                    }
                }
            }
        } catch (error: Exception) {
            previewError = error.message ?: "Falha ao carregar o arquivo do RomM."
        }
    }

    LaunchedEffect(loadedFile, selectedFile?.id, pdfPageIndex) {
        val file = loadedFile ?: return@LaunchedEffect
        if (
            (selectedFile?.category == "manual" || selectedFile?.category == "walkthrough") &&
            File(file.name).extension.equals("pdf", ignoreCase = true) &&
            pdfPageCount > 0
        ) {
            previewBitmap = withContext(Dispatchers.IO) { renderPdfPage(file, pdfPageIndex) }
        }
    }

    LaunchedEffect(audioPlayer, audioReady, audioPlaying) {
        while (audioReady && audioPlaying) {
            audioPlayer?.let { player -> runCatching { audioPositionMs = player.currentPosition } }
            delay(500)
        }
    }

    DisposableEffect(loadedFile, selectedFile?.category) {
        val file = loadedFile
        if (file != null && selectedFile?.category == "soundtrack") {
            var released = false
            val player = MediaPlayer()
            audioPlayer = player
            player.setOnPreparedListener {
                if (!released) {
                    audioDurationMs = it.duration.coerceAtLeast(0)
                    audioPositionMs = 0
                    audioReady = true
                    it.start()
                    audioPlaying = true
                }
            }
            player.setOnCompletionListener { audioPlaying = false }
            player.setOnErrorListener { _, what, extra ->
                if (!released) {
                    previewError = "Falha na reprodução de áudio (código $what/$extra)."
                    audioReady = false
                    audioPlaying = false
                }
                true
            }
            runCatching {
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            }.onFailure {
                previewError = it.message ?: "Não foi possível preparar o áudio."
            }
            onDispose {
                released = true
                runCatching { if (player.isPlaying) player.stop() }
                player.release()
                if (audioPlayer === player) audioPlayer = null
                audioReady = false
                audioPlaying = false
            }
        } else {
            onDispose { }
        }
    }

    if (
        screenshots.isEmpty() &&
        manuals.isEmpty() &&
        walkthroughs.isEmpty() &&
        tracks.isEmpty() &&
        !tracksLoading &&
        tracksError == null &&
        !hasPatchBase
    ) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Mídias e documentos do RomM", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (hasPatchBase) RomMPatcherPanel(games)

        if (screenshots.isNotEmpty()) {
            Text("Screenshots", color = GameColors.textDim, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(screenshots.take(MAX_ROMM_SCREENSHOTS_IN_GALLERY), key = { it.id }) { file ->
                    val thumbnail = screenshotThumbnails[file.id]
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = File(file.fileName).name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(116.dp).height(76.dp).clickable {
                                selectedFile = file
                            },
                        )
                    } else {
                        PlatformChip(File(file.fileName).name, selectedFile?.id == file.id) {
                            selectedFile = file
                        }
                    }
                }
            }
            if (screenshots.size > MAX_ROMM_SCREENSHOTS_IN_GALLERY) {
                Text(
                    "Pré-visualização dos primeiros $MAX_ROMM_SCREENSHOTS_IN_GALLERY de ${screenshots.size} screenshots.",
                    color = GameColors.textDim,
                    fontSize = 11.sp,
                )
            }
            if (screenshotThumbnailsLoading) {
                Text("Carregando miniaturas…", color = GameColors.textDim, fontSize = 11.sp)
            }
            screenshotThumbnailsError?.let {
                Text(it, color = GameColors.textDim, fontSize = 11.sp)
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
                    if (audioReady && audioDurationMs > 0) {
                        Slider(
                            value = audioPositionMs.coerceIn(0, audioDurationMs).toFloat(),
                            onValueChange = { audioPositionMs = it.toInt() },
                            onValueChangeFinished = {
                                runCatching { audioPlayer?.seekTo(audioPositionMs) }
                            },
                            valueRange = 0f..audioDurationMs.toFloat(),
                        )
                        Text(
                            "${formatMediaTime(audioPositionMs)} / ${formatMediaTime(audioDurationMs)}",
                            color = GameColors.textDim,
                            fontSize = 11.sp,
                        )
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pdfPageIndex > 0) {
                                PlatformChip("‹ Anterior", false) { pdfPageIndex-- }
                            }
                            Text(
                                "Página ${pdfPageIndex + 1} de $pdfPageCount",
                                color = GameColors.textDim,
                                fontSize = 11.sp,
                            )
                            if (pdfPageIndex + 1 < pdfPageCount) {
                                PlatformChip("Próxima ›", false) { pdfPageIndex++ }
                            }
                        }
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
                        Text(
                            "HTML exibido como texto; scripts e conteúdo ativo não são executados.",
                            color = GameColors.textDim,
                            fontSize = 11.sp,
                        )
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

private fun getPdfPageCount(file: File): Int {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return descriptor.use { parcel -> PdfRenderer(parcel).use { it.pageCount } }
}

private fun renderPdfPage(file: File, pageIndex: Int): android.graphics.Bitmap? {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    descriptor.use { parcel ->
        PdfRenderer(parcel).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) return null
            renderer.openPage(pageIndex).use { page ->
                val scale = minOf(
                    1800f / page.width,
                    MAX_ROMM_PDF_PREVIEW_DIMENSION.toFloat() / maxOf(page.width, page.height),
                )
                val width = (page.width * scale).toInt().coerceIn(1, MAX_ROMM_PDF_PREVIEW_DIMENSION)
                val height = (page.height * scale).toInt().coerceIn(1, MAX_ROMM_PDF_PREVIEW_DIMENSION)
                val bitmap =
                    android.graphics.Bitmap.createBitmap(
                        width,
                        height,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}

private const val MAX_ROMM_MEDIA_CACHE_BYTES = 256L * 1024L * 1024L
private const val MAX_ROMM_SCREENSHOT_SOURCE_BYTES = 64L * 1024L * 1024L
private const val MAX_ROMM_SCREENSHOT_CACHE_BYTES = 64L * 1024L * 1024L
private const val MAX_ROMM_SCREENSHOTS_IN_GALLERY = 12
private const val MAX_ROMM_SCREENSHOT_THUMBNAIL_DIMENSION = 360
private const val MAX_ROMM_TEXT_PREVIEW_CHARS = 500_000

private fun screenshotThumbnailFile(directory: File, screenshot: RemoteAssociatedFile): File =
    File(directory, "screenshot-${screenshot.id}-${screenshot.sizeBytes}.jpg")

/** Gera uma miniatura pequena sem manter o arquivo original no cache. */
private fun decodeScreenshotThumbnail(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (largestDimension / sampleSize > MAX_ROMM_SCREENSHOT_THUMBNAIL_DIMENSION) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

/** Mantém somente as miniaturas recentes, respeitando o limite do cache temporário. */
private fun trimRomMScreenshotCache(directory: File) {
    val files = directory.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: return
    var totalBytes = files.sumOf { it.length() }
    if (totalBytes <= MAX_ROMM_SCREENSHOT_CACHE_BYTES) return
    files.sortedBy { it.lastModified() }.forEach { file ->
        if (totalBytes <= MAX_ROMM_SCREENSHOT_CACHE_BYTES) return
        val size = file.length()
        if (file.delete()) totalBytes -= size
    }
}

/** Mantém o cache temporário limitado, preservando o arquivo que a tela acabou de carregar. */
private fun trimRomMMediaCache(directory: File?, keep: File) {
    if (directory == null || !directory.isDirectory) return
    val files = directory.listFiles()?.filter { it.isFile && it != keep } ?: return
    var totalBytes = files.sumOf { it.length() } + keep.length()
    if (totalBytes <= MAX_ROMM_MEDIA_CACHE_BYTES) return
    files.sortedBy { it.lastModified() }.forEach { file ->
        if (totalBytes <= MAX_ROMM_MEDIA_CACHE_BYTES) return
        val size = file.length()
        if (file.delete()) totalBytes -= size
    }
}

private fun formatMediaTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
private const val MAX_ROMM_IMAGE_PREVIEW_DIMENSION = 2048
private const val MAX_ROMM_PDF_PREVIEW_DIMENSION = 2048

/** Decodifica imagens reduzidas para evitar carregar bitmaps gigantes na memória do launcher. */
private fun decodePreviewImage(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (largestDimension / sampleSize > MAX_ROMM_IMAGE_PREVIEW_DIMENSION) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

/** Lê no máximo o limite de caracteres; não carrega o documento inteiro antes de truncar. */
private fun readTextPreview(file: File): String = file.inputStream().bufferedReader().use { reader ->
    val preview = StringBuilder(minOf(file.length(), MAX_ROMM_TEXT_PREVIEW_CHARS.toLong()).toInt())
    val buffer = CharArray(8_192)
    while (preview.length < MAX_ROMM_TEXT_PREVIEW_CHARS) {
        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_ROMM_TEXT_PREVIEW_CHARS - preview.length))
        if (count < 0) break
        preview.append(buffer, 0, count)
    }
    if (reader.read() >= 0) preview.append("\n\n[Conteúdo truncado para preservar a memória do aplicativo.]")
    preview.toString()
}
