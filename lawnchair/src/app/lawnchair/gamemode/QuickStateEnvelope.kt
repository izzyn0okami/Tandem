package app.lawnchair.gamemode

/**
 * Codec for the optional RetroAchievements payload stored beside a Libretro quick state.
 *
 * Older Tandem states contain only the emulator bytes. Newer states may use the TNDM
 * envelope: magic + unsigned 32-bit progress length + progress bytes + emulator bytes.
 */
internal object QuickStateEnvelope {
    private val magic = "TNDM".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 8

    data class Decoded(
        val emulatorState: ByteArray,
        val achievementsProgress: ByteArray?,
    )

    fun encode(emulatorState: ByteArray, achievementsProgress: ByteArray?): ByteArray {
        if (achievementsProgress == null) return emulatorState
        val length = achievementsProgress.size
        return magic + byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + achievementsProgress + emulatorState
    }

    /**
     * Returns null for a malformed TNDM envelope. A payload without the TNDM marker
     * remains a valid legacy Libretro state and is returned unchanged.
     */
    fun decode(payload: ByteArray): Decoded? {
        if (payload.size < magic.size || !payload.copyOfRange(0, magic.size).contentEquals(magic)) {
            return Decoded(payload, null)
        }
        if (payload.size < HEADER_SIZE) return null

        val progressLength = ((payload[4].toLong() and 0xFF) shl 24) or
            ((payload[5].toLong() and 0xFF) shl 16) or
            ((payload[6].toLong() and 0xFF) shl 8) or
            (payload[7].toLong() and 0xFF)
        val emulatorStart = HEADER_SIZE.toLong() + progressLength
        if (emulatorStart >= payload.size.toLong()) return null

        val split = emulatorStart.toInt()
        return Decoded(
            emulatorState = payload.copyOfRange(split, payload.size),
            achievementsProgress = payload.copyOfRange(HEADER_SIZE, split),
        )
    }
}
