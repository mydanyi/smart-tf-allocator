package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpoint contract tests for [AllocationPolicy].
 *
 * The full-range contract accepts both pure endpoints (0% and 100% Linux) in
 * addition to the existing 10..90 mixed range. The values between an endpoint
 * and the mixed range stay invalid so an unsupported share can never reach the
 * native formatter.
 */
class FullCardAllocationTest {

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * KIB
        const val GIB = 1024L * MIB
        const val METADATA_BYTES = 16L * MIB
        const val CARD_BYTES = 32L * GIB
        const val CID = "1b2c3d4e5f60718293a4b5c6d7e8f901"
    }

    private fun disk(): DiskSnapshot = DiskSnapshot(
        id = "sd-card-full-range",
        sizeBytes = CARD_BYTES,
        cid = CID,
        isSd = true,
        isAdoptable = true,
    )

    @Test
    fun isValidPercentAcceptsEndpointsAndMixedRange() {
        val accepted = intArrayOf(0, 10, 50, 90, 100)
        for (percent in accepted) {
            assertTrue("$percent% must be accepted", AllocationPolicy.isValidPercent(percent))
        }
    }

    @Test
    fun isValidPercentRejectsGapsOutsideTheContract() {
        val rejected = intArrayOf(-1, 1, 5, 9, 91, 95, 99, 101)
        for (percent in rejected) {
            assertFalse("$percent% must be rejected", AllocationPolicy.isValidPercent(percent))
        }
    }

    @Test
    fun planAcceptsBothEndpoints() {
        for (percent in intArrayOf(0, 100)) {
            assertEquals(percent, AllocationPolicy.plan(disk(), percent).linuxPercent)
        }
    }

    @Test
    fun capacityAtZeroPercentIsAllAndroidButMetadata() {
        val plan = AllocationPolicy.plan(disk(), 0)
        val (androidBytes, linuxBytes) = AllocationPolicy.capacity(plan)
        assertEquals(0L, linuxBytes)
        assertEquals(CARD_BYTES - METADATA_BYTES, androidBytes)
    }

    @Test
    fun capacityAtHundredPercentIsAllLinux() {
        val plan = AllocationPolicy.plan(disk(), 100)
        val (androidBytes, linuxBytes) = AllocationPolicy.capacity(plan)
        assertEquals(CARD_BYTES, linuxBytes)
        assertEquals(0L, androidBytes)
    }

    @Test
    fun capacityAtFiftyPercentKeepsTheMixedSplit() {
        val plan = AllocationPolicy.plan(disk(), 50)
        val (androidBytes, linuxBytes) = AllocationPolicy.capacity(plan)
        assertEquals(CARD_BYTES / 2, linuxBytes)
        assertEquals(CARD_BYTES / 2 - METADATA_BYTES, androidBytes)
    }

    // ------------------------------------------------------------------
    // Routing: every supported percentage maps to exactly one native call
    // ------------------------------------------------------------------

    @Test
    fun endpointsRouteToPrivateAndPublicWithoutAMixedRatio() {
        val allAndroid = AllocationPolicy.plan(disk(), 0)
        assertEquals(AllocationMode.ANDROID_ONLY, allAndroid.mode)
        val androidCall = AllocationPolicy.partitionCall(allAndroid)
        assertEquals("partitionPrivate", androidCall.method)
        assertEquals(allAndroid.diskId, androidCall.diskId)
        assertNull("0% must never dispatch a mixed ratio", androidCall.ratio)

        val allLinux = AllocationPolicy.plan(disk(), 100)
        assertEquals(AllocationMode.LINUX_ONLY, allLinux.mode)
        val linuxCall = AllocationPolicy.partitionCall(allLinux)
        assertEquals("partitionPublic", linuxCall.method)
        assertEquals(allLinux.diskId, linuxCall.diskId)
        assertNull("100% must never dispatch a mixed ratio", linuxCall.ratio)
    }

    @Test
    fun mixedRangeRoutesToMixedWithTheRequestedRatio() {
        for (percent in intArrayOf(10, 50, 90)) {
            val plan = AllocationPolicy.plan(disk(), percent)
            assertEquals(AllocationMode.MIXED, plan.mode)
            val call = AllocationPolicy.partitionCall(plan)
            assertEquals("partitionMixed", call.method)
            assertEquals(percent, call.ratio)
        }
    }

    @Test
    fun unsupportedShareCannotReachTheFormatter() {
        val forged = AllocationPlan(
            diskId = disk().id,
            diskBytes = CARD_BYTES,
            cardCid = CID,
            linuxPercent = 95,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.partitionCall(forged)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AllocationMode.forPercent(5)
        }
    }

    // ------------------------------------------------------------------
    // Pure slider helpers used by the upcoming UI ticket
    // ------------------------------------------------------------------

    @Test
    fun snapPercentMapsEveryGapAndClampsTheInput() {
        val expected = mapOf(
            -10 to 0,
            0 to 0,
            1 to 0,
            4 to 0,
            5 to 10,
            9 to 10,
            10 to 10,
            50 to 50,
            90 to 90,
            91 to 90,
            95 to 90,
            96 to 100,
            99 to 100,
            100 to 100,
            140 to 100,
        )
        for ((input, snapped) in expected) {
            assertEquals("snap($input)", snapped, AllocationPolicy.snapPercent(input))
        }
    }

    @Test
    fun adjacentPercentWalksTheSupportedSequenceWithoutGettingStuck() {
        assertEquals(10, AllocationPolicy.adjacentPercent(0, 1))
        assertEquals(11, AllocationPolicy.adjacentPercent(10, 1))
        assertEquals(51, AllocationPolicy.adjacentPercent(50, 1))
        assertEquals(90, AllocationPolicy.adjacentPercent(89, 1))
        assertEquals(100, AllocationPolicy.adjacentPercent(90, 1))
        assertEquals(100, AllocationPolicy.adjacentPercent(100, 1))

        assertEquals(0, AllocationPolicy.adjacentPercent(10, -1))
        assertEquals(0, AllocationPolicy.adjacentPercent(0, -1))
        assertEquals(10, AllocationPolicy.adjacentPercent(11, -1))
        assertEquals(49, AllocationPolicy.adjacentPercent(50, -1))
        assertEquals(89, AllocationPolicy.adjacentPercent(90, -1))
        assertEquals(90, AllocationPolicy.adjacentPercent(100, -1))
    }
}
