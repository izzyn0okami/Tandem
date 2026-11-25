package app.lawnchair.gamemode

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Captures each WorkManager job's save payload once. The manifest and files are promoted together,
 * so retries reuse exactly the same bytes even if the live SaveStore changes later.
 */
internal class SaveSnapshotStore(private val root: File) {

    fun capture(jobId: String, sources: Map<RemoteFileKind, File>): Map<RemoteFileKind, File> {
        val key = digest(jobId)
        val destination = File(root, key)
        val manifest = File(destination, "snapshot.manifest")
        if (manifest.isFile) return loadAndVerify(destination, File(destination, "snapshot.manifest"))

        if (!root.exists() && !root.mkdirs()) throw IOException("Não foi possível criar a fila local de saves")
        val staging = File(root, ".$key.capture")
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Não foi possível limpar uma captura incompleta de save")
        }
        if (!staging.mkdirs()) throw IOException("Não foi possível preparar a captura do save")

        try {
            val hashes = linkedMapOf<RemoteFileKind, String>()
            sources.forEach { (kind, source) ->
                if (source.isFile && source.length() > 0L) {
                    val snapshot = File(staging, fileName(kind))
                    source.copyTo(snapshot)
                    val sourceHash = hash(source)
                    val snapshotHash = hash(snapshot)
                    if (sourceHash != snapshotHash) {
                        throw IOException("O save mudou durante a captura; a sincronização será repetida")
                    }
                    hashes[kind] = snapshotHash
                }
            }
            manifestFile(staging).writeText(
                hashes.entries.joinToString("\n") { (kind, value) -> "${kind.name}=$value" },
            )
            if (destination.exists() && !destination.deleteRecursively()) {
                throw IOException("Não foi possível substituir uma captura incompleta")
            }
            if (!staging.renameTo(destination)) {
                throw IOException("Não foi possível confirmar a captura imutável do save")
            }
            return loadAndVerify(destination, File(destination, "snapshot.manifest"))
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun load(jobId: String): Map<RemoteFileKind, File> {
        val directory = File(root, digest(jobId))
        return loadAndVerify(directory, File(directory, "snapshot.manifest"))
    }

    fun delete(jobId: String): Boolean {
        val directory = File(root, digest(jobId))
        return !directory.exists() || directory.deleteRecursively()
    }

    private fun loadAndVerify(
        directory: File,
        manifest: File,
    ): Map<RemoteFileKind, File> {
        if (!manifest.isFile) throw IOException("A captura persistente do save está incompleta")
        val hashes = linkedMapOf<RemoteFileKind, String>()
        manifest.readLines().filter(String::isNotBlank).forEach { line ->
            val parts = line.split('=', limit = 2)
            if (parts.size != 2) throw IOException("Manifesto da captura de save inválido")
            val kind = RemoteFileKind.entries.firstOrNull { it.name == parts[0] }
                ?: throw IOException("Tipo de arquivo desconhecido na captura de save")
            val expectedHash = parts[1]
            if (!expectedHash.matches(Regex("[a-f0-9]{64}")) || hashes.put(kind, expectedHash) != null) {
                throw IOException("Hash inválido no manifesto da captura de save")
            }
        }
        return hashes.mapValues { (kind, expectedHash) ->
            val snapshot = File(directory, fileName(kind))
            if (!snapshot.isFile || snapshot.length() == 0L || hash(snapshot) != expectedHash) {
                throw IOException("A captura imutável do save foi alterada ou está incompleta")
            }
            snapshot
        }
    }

    private fun manifestFile(directory: File) = File(directory, "snapshot.manifest")

    private fun fileName(kind: RemoteFileKind): String = when (kind) {
        RemoteFileKind.Save -> "sram.srm"
        RemoteFileKind.State -> "state1.state"
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
