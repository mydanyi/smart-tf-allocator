package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests for [MoveStatusDecoder] and the success condition.
 *
 * The platform reports an active move as a 0..100 percentage, `-100` when it
 * finished, and other negative codes on failure. A finished move is only a
 * success when the live destination matches the request.
 */
class MoveStatusDecoderTest {

    private companion object {
        const val CARD_UUID_RAW = "ABCDEF0123456789ABCDEF0123456789"
        const val CARD_UUID = "abcdef01-2345-6789-abcd-ef0123456789"
        const val OTHER_UUID_RAW = "11111111222233334444555566667777"
    }

    private fun progress(status: MoveStatus): Int {
        assertTrue("expected progress but was $status", status is MoveStatus.InProgress)
        return (status as MoveStatus.InProgress).percent
    }

    private fun failure(status: MoveStatus): MoveStatus.Failed {
        assertTrue("expected failure but was $status", status is MoveStatus.Failed)
        return status as MoveStatus.Failed
    }

    @Test
    fun zeroAndPositiveStatusesAreProgress() {
        assertEquals(0, progress(MoveStatusDecoder.decode(0)))
        assertEquals(1, progress(MoveStatusDecoder.decode(1)))
        assertEquals(57, progress(MoveStatusDecoder.decode(57)))
        assertEquals(100, progress(MoveStatusDecoder.decode(100)))
    }

    @Test
    fun minusOneHundredIsComplete() {
        assertEquals(MoveStatus.Complete, MoveStatusDecoder.decode(-100))
    }

    @Test
    fun platformFailuresAreMappedToStableNames() {
        assertEquals("insufficient_storage", failure(MoveStatusDecoder.decode(-1)).code)
        assertEquals("package_missing", failure(MoveStatusDecoder.decode(-2)).code)
        assertEquals("system_package", failure(MoveStatusDecoder.decode(-3)).code)
        assertEquals("invalid_location", failure(MoveStatusDecoder.decode(-5)).code)
        assertEquals("internal_error", failure(MoveStatusDecoder.decode(-6)).code)
        assertEquals("operation_pending", failure(MoveStatusDecoder.decode(-7)).code)
        assertEquals("device_admin", failure(MoveStatusDecoder.decode(-8)).code)
        assertEquals(
            "third_party_not_allowed_internal",
            failure(MoveStatusDecoder.decode(-9)).code,
        )
        assertEquals("locked_user", failure(MoveStatusDecoder.decode(-10)).code)
    }

    @Test
    fun unlistedNegativeCodesAreFailuresAndNeverProgress() {
        // -101/-103 are not move status values: a negative status other than
        // -100 is always a failure, never running progress.
        assertEquals("failed_native", failure(MoveStatusDecoder.decode(-101)).code)
        assertEquals("failed_native", failure(MoveStatusDecoder.decode(-103)).code)
        assertTrue(MoveStatusDecoder.decode(-101) is MoveStatus.Failed)
        assertTrue(MoveStatusDecoder.decode(-103) is MoveStatus.Failed)
    }

    @Test
    fun unknownNegativeStatusKeepsTheNativeCode() {
        val failed = failure(MoveStatusDecoder.decode(-999))
        assertEquals("failed_native", failed.code)
        assertEquals(-999, failed.nativeCode)
    }

    @Test
    fun completionAtTheRequestedCardIsAccepted() {
        assertTrue(MigrationPolicy.destinationMatches(CARD_UUID_RAW, CARD_UUID))
        assertTrue(MigrationPolicy.destinationMatches("internal", null))
    }

    @Test
    fun completionAtTheWrongDestinationIsRejected() {
        // Native -100 but the primary/app still reports internal storage.
        assertFalse(MigrationPolicy.destinationMatches(CARD_UUID_RAW, null))
        // Native -100 but the app/primary is on a different volume.
        assertFalse(MigrationPolicy.destinationMatches(CARD_UUID_RAW, OTHER_UUID_RAW))
        // Move-back reported complete but the source is still the card.
        assertFalse(MigrationPolicy.destinationMatches("internal", CARD_UUID_RAW))
    }
}
