package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Contract tests for [AllocationPolicy].
 *
 * The domain layer is a pure, host-testable Kotlin unit: no Android framework
 * classes are referenced anywhere in this file.
 */
class AllocationPolicyTest {

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * KIB
        const val GIB = 1024L * MIB

        /** Android adopted-storage metadata reservation, in bytes. */
        const val METADATA_BYTES = 16L * MIB

        /**
         * Small slack allowed between exact long arithmetic and the real
         * partition sizes. It only covers sector/GPT rounding, not a wrongly
         * computed share, so it is deliberately kept well under 16 MiB.
         */
        const val ALIGNMENT_TOLERANCE_BYTES = 8L * MIB

        const val VALID_CID_LOWER = "1b2c3d4e5f60718293a4b5c6d7e8f901"
        const val VALID_CID_UPPER = "1B2C3D4E5F60718293A4B5C6D7E8F901"
    }

    private fun disk(
        id: String = "disk-1",
        sizeBytes: Long = 64L * GIB,
        cid: String = VALID_CID_LOWER,
        isSd: Boolean = true,
        isAdoptable: Boolean = true,
    ): DiskSnapshot = DiskSnapshot(
        id = id,
        sizeBytes = sizeBytes,
        cid = cid,
        isSd = isSd,
        isAdoptable = isAdoptable,
    )

    // ------------------------------------------------------------------
    // isValidPercent
    // ------------------------------------------------------------------

    @Test
    fun isValidPercentAcceptsBoundsInclusive() {
        assertTrue(AllocationPolicy.isValidPercent(10))
        assertTrue(AllocationPolicy.isValidPercent(50))
        assertTrue(AllocationPolicy.isValidPercent(90))
    }

    @Test
    fun isValidPercentRejectsOutOfRange() {
        assertFalse(AllocationPolicy.isValidPercent(9))
        assertFalse(AllocationPolicy.isValidPercent(91))
        assertFalse(AllocationPolicy.isValidPercent(0))
        assertFalse(AllocationPolicy.isValidPercent(100))
        assertFalse(AllocationPolicy.isValidPercent(-1))
    }

    // ------------------------------------------------------------------
    // plan(...) happy path
    // ------------------------------------------------------------------

    @Test
    fun planAccepts10_50_90() {
        for (percent in intArrayOf(10, 50, 90)) {
            val plan = AllocationPolicy.plan(disk(), percent)
            assertEquals(percent, plan.linuxPercent)
        }
    }

    @Test
    fun planCapturesDiskIdentity() {
        val source = disk(id = "sd-card-A", sizeBytes = 128L * GIB, cid = VALID_CID_UPPER)
        val plan = AllocationPolicy.plan(source, 50)

        assertEquals(source.id, plan.diskId)
        assertEquals(source.sizeBytes, plan.diskBytes)
        assertEquals(source.cid.lowercase(), plan.cardCid.lowercase())
        assertEquals(50, plan.linuxPercent)
    }

    @Test
    fun planAcceptsUpperCaseCid() {
        val plan = AllocationPolicy.plan(disk(cid = VALID_CID_UPPER), 50)
        assertEquals(50, plan.linuxPercent)
    }

    // ------------------------------------------------------------------
    // plan(...) rejections -> IllegalArgumentException
    // ------------------------------------------------------------------

    @Test
    fun planRejects9() {
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.plan(disk(), 9)
        }
    }

    @Test
    fun planRejects91() {
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.plan(disk(), 91)
        }
    }

    @Test
    fun planRejectsInvalidCid() {
        val badCids = listOf(
            "",                                      // empty
            "abc",                                   // too short
            VALID_CID_LOWER + "0",                   // 33 chars
            VALID_CID_LOWER.dropLast(1),             // 31 chars
            "zz2c3d4e5f60718293a4b5c6d7e8f901",      // 32 chars but not hex
            "1b2c3d4e5f60718293a4b5c6d7e8f90g",      // trailing non-hex
        )
        for (bad in badCids) {
            assertThrows(
                "cid='$bad' should be rejected",
                IllegalArgumentException::class.java,
            ) {
                AllocationPolicy.plan(disk(cid = bad), 50)
            }
        }
    }

    @Test
    fun planRejectsNonSd() {
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.plan(disk(isSd = false), 50)
        }
    }

    @Test
    fun planRejectsNonAdoptable() {
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.plan(disk(isAdoptable = false), 50)
        }
    }

    @Test
    fun planRejectsZeroSizedDisk() {
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.plan(disk(sizeBytes = 0L), 50)
        }
    }

    // ------------------------------------------------------------------
    // capacity(...) share math
    // ------------------------------------------------------------------

    @Test
    fun capacityLinuxShareEqualsRequestedArgument() {
        val source = disk(sizeBytes = 200L * MIB)
        for (percent in intArrayOf(10, 50, 90)) {
            val plan = AllocationPolicy.plan(source, percent)
            val (_, linuxBytes) = AllocationPolicy.capacity(plan)
            assertEquals(source.sizeBytes * percent / 100, linuxBytes)
        }
    }

    @Test
    fun capacityLinuxShareIsArgumentAndNotTheAndroidComplement() {
        // For 10 and 90 the Linux share and Android complement are distinct,
        // so this guards against swapping the two sides.
        val source = disk(sizeBytes = 200L * MIB)

        val lowPlan = AllocationPolicy.plan(source, 10)
        val lowLinux = AllocationPolicy.capacity(lowPlan).second
        assertEquals(20L * MIB, lowLinux)
        assertNotEquals(180L * MIB, lowLinux)

        val highPlan = AllocationPolicy.plan(source, 90)
        val highLinux = AllocationPolicy.capacity(highPlan).second
        assertEquals(180L * MIB, highLinux)
        assertNotEquals(20L * MIB, highLinux)
    }

    @Test
    fun capacityReturnsAndroidBytesFirstThenLinuxBytes() {
        val source = disk(sizeBytes = 200L * MIB)
        val (androidBytes, linuxBytes) = AllocationPolicy.capacity(
            AllocationPolicy.plan(source, 10),
        )

        // Linux side is the exact 10% share; the Android side is the larger remainder.
        assertEquals(20L * MIB, linuxBytes)
        assertTrue(androidBytes > linuxBytes)
    }

    @Test
    fun capacityAndroidShareSubtractsMetadataWithinAlignmentTolerance() {
        val source = disk(sizeBytes = 128L * GIB)
        val (androidBytes, linuxBytes) = AllocationPolicy.capacity(
            AllocationPolicy.plan(source, 50),
        )

        val expectedApproxAndroid = source.sizeBytes - linuxBytes - METADATA_BYTES
        assertTrue(
            "android share $androidBytes differs from approximate " +
                "$expectedApproxAndroid by more than $ALIGNMENT_TOLERANCE_BYTES bytes",
            abs(androidBytes - expectedApproxAndroid) <= ALIGNMENT_TOLERANCE_BYTES,
        )

        // Metadata is reserved out of the Android total, so the two shares
        // must not add up to the full disk.
        assertTrue(androidBytes < source.sizeBytes - linuxBytes)
    }

    @Test
    fun capacityHandles256GiBWithoutOverflow() {
        val source = disk(sizeBytes = 256L * GIB)
        val (androidBytes, linuxBytes) = AllocationPolicy.capacity(
            AllocationPolicy.plan(source, 90),
        )

        val expectedLinux = 256L * GIB * 90 / 100
        assertEquals(expectedLinux, linuxBytes)

        // Values far exceed Int range; a 32-bit intermediate would go negative.
        assertTrue(linuxBytes > Int.MAX_VALUE.toLong())
        assertTrue(androidBytes > Int.MAX_VALUE.toLong())
        assertTrue(androidBytes + linuxBytes <= source.sizeBytes)
    }

    // ------------------------------------------------------------------
    // capacity(...) / matches(...) with a bad plan
    // ------------------------------------------------------------------

    @Test
    fun capacityRejectsPlanWithInvalidPercent() {
        val bad = AllocationPlan(
            diskId = "disk-1",
            diskBytes = 64L * GIB,
            cardCid = VALID_CID_LOWER,
            linuxPercent = 9,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.capacity(bad)
        }
    }

    @Test
    fun capacityRejectsPlanWithNegativeDiskBytes() {
        val bad = AllocationPlan(
            diskId = "disk-1",
            diskBytes = -1L,
            cardCid = VALID_CID_LOWER,
            linuxPercent = 50,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.capacity(bad)
        }
    }

    @Test
    fun matchesRejectsPlanWithInvalidCid() {
        val bad = AllocationPlan(
            diskId = "disk-1",
            diskBytes = 64L * GIB,
            cardCid = "not-hex",
            linuxPercent = 50,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AllocationPolicy.matches(bad, disk())
        }
    }

    // ------------------------------------------------------------------
    // matches(...)
    // ------------------------------------------------------------------

    @Test
    fun matchesAcceptsIdenticalDisk() {
        val source = disk()
        val plan = AllocationPolicy.plan(source, 50)
        assertTrue(AllocationPolicy.matches(plan, source))
    }

    @Test
    fun matchesRejectsDiskWithDifferentId() {
        val plan = AllocationPolicy.plan(disk(id = "disk-1"), 50)
        assertFalse(AllocationPolicy.matches(plan, disk(id = "disk-2")))
    }

    @Test
    fun matchesRejectsSameIdWithChangedCid() {
        val plan = AllocationPolicy.plan(disk(id = "disk-1", cid = VALID_CID_LOWER), 50)
        val changed = disk(id = "disk-1", cid = "0123456789abcdef0123456789abcdef")
        assertFalse(AllocationPolicy.matches(plan, changed))
    }

    @Test
    fun matchesRejectsSameIdWithChangedSize() {
        val plan = AllocationPolicy.plan(disk(id = "disk-1", sizeBytes = 64L * GIB), 50)
        val changed = disk(id = "disk-1", sizeBytes = 64L * GIB + MIB)
        assertFalse(AllocationPolicy.matches(plan, changed))
    }

    @Test
    fun matchesTreatsUpperAndLowerCaseCidAsEquivalent() {
        val planFromLower = AllocationPolicy.plan(disk(cid = VALID_CID_LOWER), 50)
        val planFromUpper = AllocationPolicy.plan(disk(cid = VALID_CID_UPPER), 50)

        assertTrue(AllocationPolicy.matches(planFromLower, disk(cid = VALID_CID_UPPER)))
        assertTrue(AllocationPolicy.matches(planFromUpper, disk(cid = VALID_CID_LOWER)))
    }
}
