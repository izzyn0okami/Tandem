package app.lawnchair.gamemode

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Capas já decodificadas, em tamanho de miniatura, para a grade rolar sem engasgar. */
object RomMCovers {

    private const val TARGET_WIDTH = 240
    private val memory = LruCache<Long, ImageBitmap>(120)

    fun cached(id: Long): ImageBitmap? = memory.get(id)

    /** Onde a capa fica guardada no aparelho (dentro da pasta de imagens escolhida). */
    fun coverFile(context: Context, id: Long): File = File(File(GameFolders.dir(context, FolderKind.Assets), "covers"), "$id.img")

    /** Bloqueante: baixa (se preciso) e decodifica a capa. */
    fun load(context: Context, client: RomMClient, game: RemoteGame, allowNetwork: Boolean = true): ImageBitmap? {
        memory.get(game.id)?.let { return it }
        val cached = coverFile(context, game.id).takeIf { it.isFile && it.length() > 0 }
        val file = (if (allowNetwork) client.cover(context, game) else cached) ?: return null
        return decode(file)?.also { memory.put(game.id, it) }
    }

    /** Decodifica a imagem em tamanho de miniatura; apaga o arquivo se estiver corrompido. */
    fun decode(file: File): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap == null) {
            // Arquivo corrompido (ex.: download interrompido): apaga para baixar de novo na próxima vez.
            file.delete()
            return null
        }
        return bitmap.asImageBitmap()
    }
}
