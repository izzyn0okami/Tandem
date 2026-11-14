package app.lawnchair.gamemode

import android.content.Context
import java.io.File

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

    private val savesRoot = GameFolders.dir(context, FolderKind.Saves)
    private val gameDirName = romFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    private val dir = File(File(savesRoot, coreId), gameDirName).apply { mkdirs() }

    val sramFile = File(dir, "sram.srm")
    val stateFile = File(dir, "state1.state")

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
    private fun write(target: File, bytes: ByteArray) {
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(bytes)
        target.delete()
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }
}
