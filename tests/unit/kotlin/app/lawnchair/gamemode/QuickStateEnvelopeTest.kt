package app.lawnchair.gamemode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class QuickStateEnvelopeTest {

    @Test
    fun legacyStateWithoutEnvelopeIsReturnedUnchanged() {
        val state = byteArrayOf(1, 2, 3, 4)

        val decoded = QuickStateEnvelope.decode(state)

        assertNotNull(decoded)
        assertArrayEquals(state, decoded!!.emulatorState)
        assertNull(decoded.achievementsProgress)
    }

    @Test
    fun envelopeRoundTripsAchievementsProgressAndEmulatorState() {
        val progress = byteArrayOf(5, 6, 7)
        val state = byteArrayOf(8, 9, 10)

        val decoded = QuickStateEnvelope.decode(QuickStateEnvelope.encode(state, progress))

        assertNotNull(decoded)
        assertArrayEquals(progress, decoded!!.achievementsProgress)
        assertArrayEquals(state, decoded.emulatorState)
    }

    @Test
    fun envelopeWithoutCompleteHeaderIsRejected() {
        assertNull(QuickStateEnvelope.decode("TNDM".toByteArray(Charsets.US_ASCII)))
        assertNull(QuickStateEnvelope.decode("TNDM\u0000\u0000".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun envelopeWithUnsignedLengthLargerThanPayloadIsRejected() {
        val payload = "TNDM".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 5) +
            byteArrayOf(1, 2, 3, 4, 5)

        assertNull(QuickStateEnvelope.decode(payload))
    }

    @Test
    fun envelopeWithNegativeSignedIntLengthIsRejectedAsOversizedUnsignedLength() {
        val payload = "TNDM".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x80.toByte(), 0, 0, 0) +
            byteArrayOf(1)

        assertNull(QuickStateEnvelope.decode(payload))
    }

    @Test
    fun envelopeWithoutEmulatorStateIsRejected() {
        val payload = QuickStateEnvelope.encode(byteArrayOf(), byteArrayOf(1, 2))

        assertNull(QuickStateEnvelope.decode(payload))
    }
}
