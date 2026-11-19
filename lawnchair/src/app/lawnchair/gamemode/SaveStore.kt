package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Saves por jogo, na pasta de saves escolhida (padrão: dentro do app) em `<núcleo>/<jogo>/` com `sram.srm`
 * (save de bateria, gravado sozinho) e `state1.state` (estado rápido, gravado pelo menu).
 */
class SaveStore(
    context: Context,
    private val coreId: String,
    romFile: File,
    /** Outros núcleos da mesma plataforma: o save de bateria deles serve como ponto de partida. */
    private val siblingCores: List<String> = emptyList(),
) {

    private val savesRoot: File = GameFolders.dir(context, FolderKind.Saves)
    private val gameDirName: String = romFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    private val dir: File = File(File(savesRoot, coreId), gameDirName).apply { mkdirs() }

    val sramFile = File(dir, "sram.srm")
    val stateFile = File(dir, "state1.state")

    private fun raProgressFile(user: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8))
        val accountKey = digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(dir, "ra-progress-$accountKey.bin")
    }

    /** Progresso interno do rc_client, separado dos estados de emulação (inclusive em Hardcore). */
    fun readRaProgress(user: String): ByteArray? = raProgressFile(user).takeIf { it.isFile && it.length() > 0 }?.readBytes()

    fun writeRaProgress(user: String, bytes: ByteArray) = write(raProgressFile(user), bytes)

    fun deleteRaProgress(user: String) {
        raProgressFile(user).delete()
    }

    /** Cópias de segurança de saves substituídos durante a sincronização. */
    val backupDir = File(dir, "backups")

    /** Save de bateria deste núcleo; se não houver, o mais recente de outro núcleo do mesmo console (estados não servem entre núcleos). */
    fun readSram(): ByteArray? {
        sramFile.takeIf { it.isFile && it.length() > 0 }?.let { return it.readBytes() }
        val other = siblingCores
            .filter { it != coreId }
            .map { File(File(File(savesRoot, it), gameDirName), "sram.srm") }
            .filter { it.isFile && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
        if (other != null) GameLog.log("saves", "usando o save de ${other.parentFile?.parentFile?.name} para $coreId")
        return other?.readBytes()
    }

    fun writeSram(bytes: ByteArray) = write(sramFile, bytes)

    fun hasState(): Boolean = stateFile.isFile && stateFile.length() > 0

    fun readState(): ByteArray? = stateFile.takeIf { hasState() }?.readBytes()

    fun writeState(bytes: ByteArray) = write(stateFile, bytes)

    /** Grava por arquivo temporário e renomeia, para um corte de energia não estragar o save. */
    @Synchronized
    private fun write(target: File, bytes: ByteArray) {
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(bytes)
        // rename(2) substitui o destino atomicamente neste filesystem; não remova o save antigo
        // antes, pois uma interrupção entre delete e rename poderia perder o último progresso.
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }
}
