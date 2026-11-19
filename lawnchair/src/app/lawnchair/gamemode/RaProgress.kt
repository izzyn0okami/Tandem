package app.lawnchair.gamemode

import com.swordfish.libretrodroid.LibretroDroid

/**
 * Ponte Kotlin para o progresso das conquistas do RetroAchievements:
 * - [raSerializeProgress] grava o progresso junto ao estado rápido do emulador;
 * - [raDeserializeProgress] restaura esse progresso ao carregar o estado (nulo reinicia);
 * - [raReset] reinicia o progresso quando o jogo é reiniciado;
 * - [raIdle] processa eventos pendentes enquanto o jogo está pausado;
 * - [raCanPause] evita pausas seguidas no hardcore, que poderiam burlar condições.
 */
object RaProgress {

    fun serialize(): ByteArray? = runCatching { LibretroDroid.raSerializeProgress() }.getOrNull()

    fun deserialize(progress: ByteArray?): Boolean = runCatching {
        LibretroDroid.raDeserializeProgress(progress)
    }.getOrDefault(false)

    fun reset() {
        runCatching { LibretroDroid.raReset() }
    }

    fun idle() {
        runCatching { LibretroDroid.raIdle() }
    }

    fun canPause(): Boolean = runCatching { LibretroDroid.raCanPause() }.getOrDefault(true)
}
