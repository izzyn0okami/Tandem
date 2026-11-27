package app.lawnchair.gamemode

import android.content.Context
import java.io.File

/**
 * Diagnóstico somente de leitura dos diretórios usados pelo Android, LibretroDroid e SaveStore.
 * Registra metadados dos arquivos; nunca abre conteúdo, cria, move, renomeia ou remove saves.
 */
internal object SavePathDiagnostics {

    private const val MAX_LISTED_FILES = 40

    fun log(
        context: Context,
        coreId: String,
        platformSlug: String?,
        romPath: String,
        gameIdentity: String,
        effectiveDirectory: File,
        saveStore: SaveStore,
    ) {
        val customPath = GameFolders.customPath(context, FolderKind.Saves)
        val defaultPath = GameFolders.defaultDir(context, FolderKind.Saves)
        val effectivePath = runCatching { effectiveDirectory.canonicalPath }.getOrDefault(effectiveDirectory.absolutePath)
        val customCanonical = customPath?.let { path ->
            runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath)
        }
        val isFallback = customPath != null && customCanonical != effectivePath

        GameLog.log(
            "diagnóstico-saves",
            buildString {
                appendLine("Início da sessão — diagnóstico somente de leitura.")
                appendLine("Plataforma: ${platformSlug ?: "não informada"}; núcleo: $coreId")
                appendLine("ROM: $romPath")
                appendLine("Identidade do jogo: $gameIdentity")
                appendLine("Pasta personalizada configurada: ${customPath ?: "não"}")
                appendLine("Pasta padrão interna: ${defaultPath.absolutePath}")
                appendLine("Pasta efetivamente usada: ${effectiveDirectory.absolutePath}")
                appendLine(
                    "Origem efetiva: ${when {
                        customPath == null -> "padrão interno"
                        isFallback -> "fallback para padrão interno"
                        else -> "personalizada"
                    }}",
                )
                appendLine("Diretório existe: ${effectiveDirectory.isDirectory}; gravável segundo File.canWrite(): ${effectiveDirectory.canWrite()}")
                appendLine("Permissão MANAGE_EXTERNAL_STORAGE: ${StorageAccess.isGranted()}")
                appendLine("Caminho entregue ao LibretroDroid (savesDirectory): ${effectiveDirectory.absolutePath}")
                appendLine("Diretório usado pelo SaveStore: ${saveStore.sramFile.parentFile?.absolutePath}")
                appendLine("SRAM do SaveStore: ${describe(saveStore.sramFile)}")
                saveStore.nativeSaveFile?.let { appendLine("Save nativo do núcleo: ${describe(it)}") }
                appendLine("Destino de Device Sync: ${describe(saveStore.deviceSyncSaveFile)}")
                appendLine("Estado rápido do SaveStore: ${describe(saveStore.stateFile)}")
                appendLine("Arquivos diretamente na raiz efetiva:")
                appendFileList(effectiveDirectory)
                val coreDirectory = File(effectiveDirectory, coreId)
                appendLine("Entradas imediatas na pasta do núcleo ($coreId):")
                appendFileList(coreDirectory)
                appendLine("Limite de listagem: até $MAX_LISTED_FILES entradas por pasta; somente nome, tipo, tamanho e data; conteúdo não lido.")
                appendLine("Nenhum arquivo foi criado, alterado, movido ou excluído por este diagnóstico.")
            },
        )
    }

    private fun describe(file: File): String = "caminho=${file.absolutePath}; existe=${file.isFile}; bytes=${file.takeIf { it.isFile }?.length() ?: 0L}; gravável=${file.canWrite()}"

    private fun StringBuilder.appendFileList(directory: File) {
        if (!directory.isDirectory) {
            appendLine("  (pasta ausente ou inacessível)")
            return
        }
        val entries = directory.listFiles()?.sortedBy { it.name }.orEmpty()
        if (entries.isEmpty()) {
            appendLine("  (vazia)")
            return
        }
        entries.take(MAX_LISTED_FILES).forEach { file ->
            appendLine(
                "  - ${file.name}; tipo=${if (file.isDirectory) "pasta" else "arquivo"}; " +
                    "bytes=${if (file.isFile) file.length() else 0L}; modificado=${file.lastModified()}",
            )
        }
        if (entries.size > MAX_LISTED_FILES) {
            appendLine("  ... ${entries.size - MAX_LISTED_FILES} entradas omitidas")
        }
    }
}
