package io.github.mydanyi.armadatf.storage

import io.github.mydanyi.armadatf.domain.AllocationMode
import io.github.mydanyi.armadatf.domain.AllocationPolicy
import io.github.mydanyi.armadatf.domain.DiskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The 128 GB card under test, as the platform reports it. */
private const val CARD_ID = "disk:179,0"
private const val CARD_BYTES = 128_177_930_240L
private const val VALID_CID = "1b2c3d4e5f60718293a4b5c6d7e8f901"

/** `DiskInfo.FLAG_ADOPTABLE` (bit 0). */
private const val FLAG_ADOPTABLE = 1

/** `DiskInfo.FLAG_SD` (bit 2). */
private const val FLAG_SD = 4

/**
 * The sysfs path the platform reports for a removable MMC card.
 *
 * The doubled slash after `/sys` is what the platform actually produces, so the
 * mapping must accept it without relying on a normalised path.
 */
private const val SD_SYS_PATH =
    "/sys//devices/platform/soc/8804000.sdhci/mmc_host/mmc1/mmc1:b368/block/mmcblk1"

/** A USB mass-storage disk: the same flags, but not a removable SD card. */
private const val USB_SYS_PATH =
    "/sys/devices/pci0000:00/0000:00:14.0/usb1/1-1/1-1:1.0/host4/target4:0:0/4:0:0:0/block/sda"

/**
 * Fake stand-in for the platform disk object used by the mapping test.
 *
 * It must be a public class with public fields: `java.lang.reflect.Field.get`
 * refuses to read even public fields when the declaring class is not public,
 * exactly like the real (public) platform disk class. `sysPath` is one of those
 * public fields, and it is the only place the device path can enter the
 * snapshot: the resulting [DiskSnapshot] has no path field of its own.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class FakeDiskInfo(
    @JvmField var id: String = CARD_ID,
    @JvmField var flags: Int = FLAG_SD or FLAG_ADOPTABLE,
    @JvmField var size: Long = CARD_BYTES,
    @JvmField var sysPath: String = SD_SYS_PATH,
)

/**
 * Host-side mapping test for the private `NativeStorage.toDiskSnapshot`.
 *
 * The mapping is the only place where "is this an SD card?" is decided, and it
 * runs in the privileged process where `/sys/dev/block/<major>:<minor>` may be
 * unreadable (SELinux, restricted sysfs, a card that has just been re-inserted).
 * The platform's own `sysPath` field therefore has to be enough on its own: a
 * disk that carries the SD flag and an MMC `sysPath` is an SD card even when the
 * sysfs attributes cannot be read.
 *
 * The fake exposes the same public fields the platform disk object does, so the
 * reflective mapper can be exercised entirely on the JVM. No Android binder,
 * ServiceManager or other privileged access happens here, and every sysfs read
 * in the mapper fails on this host, which is exactly the restricted-sysfs case.
 */
class RestrictedDiskMappingTest {

    @Test
    fun sdFlagAndMmcSystemPathMapTheDiskAsSdWithoutSysfs() {
        val mapped = mapDisk(FakeDiskInfo())

        assertTrue(
            "an SD-flagged MMC disk must still map to isSd when sysfs is unreadable",
            mapped.isSd,
        )
        assertTrue("flags bit 0 is FLAG_ADOPTABLE", mapped.isAdoptable)
        assertEquals(CARD_ID, mapped.id)
        assertEquals(CARD_BYTES, mapped.sizeBytes)
    }

    @Test
    fun cidIsEmptyWhenSysfsIsUnreadable() {
        // The host cannot read /sys/dev/block/179:0/device/cid, so the read-only
        // listing reports "no CID" rather than a fabricated value. The identity
        // is then proven from the volume instead of the CID.
        assertEquals("", mapDisk(FakeDiskInfo()).cid)
    }

    @Test
    fun diskWithoutTheSdFlagIsNotSdAndCannotBePlanned() {
        val mapped = mapDisk(FakeDiskInfo(flags = FLAG_ADOPTABLE))

        assertFalse("without FLAG_SD the disk is not an SD card", mapped.isSd)
        assertIllegalArgument { AllocationPolicy.plan(mapped, 50) }
    }

    @Test
    fun nonMmcOrMalformedSystemPathIsNotSdAndCannotBePlanned() {
        val paths = listOf(
            USB_SYS_PATH,
            "",
            "not-a-sysfs-path",
            "block/mmcblk1",
            "/sys/class/block/mmcblk1",
        )
        for (path in paths) {
            val mapped = mapDisk(FakeDiskInfo(sysPath = path))
            assertFalse("'$path' is not a removable MMC sysfs path", mapped.isSd)
            assertIllegalArgument("'$path' must not be plannable") {
                AllocationPolicy.plan(mapped, 50)
            }
        }
    }

    @Test
    fun planAcceptsTheMappedCardAtEverySupportedShare() {
        val mapped = mapDisk(FakeDiskInfo())

        for (percent in listOf(0, 50, 100)) {
            val plan = AllocationPolicy.plan(mapped, percent)

            assertEquals(CARD_ID, plan.diskId)
            assertEquals(CARD_BYTES, plan.diskBytes)
            assertEquals(mapped.cid, plan.cardCid)
            assertEquals(percent.toLong(), plan.linuxPercent.toLong())
            assertEquals(AllocationMode.forPercent(percent), plan.mode)

            val (androidBytes, linuxBytes) = AllocationPolicy.capacity(plan)
            assertTrue("android bytes must never be negative at $percent%", androidBytes >= 0L)
            assertTrue("linux bytes must never be negative at $percent%", linuxBytes >= 0L)
            if (percent == 100) {
                assertEquals(0L, androidBytes)
                assertEquals(CARD_BYTES, linuxBytes)
            } else {
                assertEquals(
                    "the two shares plus the metadata reservation must be the whole card at $percent%",
                    CARD_BYTES,
                    androidBytes + linuxBytes + AllocationPolicy.ANDROID_METADATA_BYTES,
                )
            }
            if (percent == 0) {
                assertEquals(0L, linuxBytes)
            }
        }
    }

    @Test
    fun planRejectsAnUnsupportedShare() {
        assertIllegalArgument { AllocationPolicy.plan(card(), 5) }
        assertIllegalArgument { AllocationPolicy.plan(card(), 95) }
    }

    @Test
    fun matchesAcceptsTheSameCard() {
        assertTrue(AllocationPolicy.matches(AllocationPolicy.plan(card(), 50), card()))
    }

    @Test
    fun matchesRejectsAChangedSize() {
        val plan = AllocationPolicy.plan(card(), 50)

        assertFalse(AllocationPolicy.matches(plan, card(sizeBytes = CARD_BYTES - 512L)))
    }

    @Test
    fun matchesRejectsAChangedId() {
        val plan = AllocationPolicy.plan(card(), 50)

        assertFalse(AllocationPolicy.matches(plan, card(id = "disk:179,1")))
    }

    @Test
    fun matchesRejectsACardDiscoveredThroughANonMmcPath() {
        val plan = AllocationPolicy.plan(card(), 50)

        // Same id, size and flags, but the card now sits behind a non-MMC
        // sysfs path: it is a different physical device, so it cannot match.
        val elsewhere = mapDisk(FakeDiskInfo(sysPath = USB_SYS_PATH))
        assertFalse(AllocationPolicy.matches(plan, elsewhere))
    }

    @Test
    fun existingSnapshotWithNoCidAndNoPathIsStillRejected() {
        // A snapshot built directly (no `sysPath`, no CID) proves nothing about
        // the physical card, so the path-only fallback must not launder it into
        // an acceptable plan.
        val noCidNoPath = DiskSnapshot(
            id = CARD_ID,
            sizeBytes = CARD_BYTES,
            cid = "",
            isSd = true,
            isAdoptable = true,
        )

        assertIllegalArgument { AllocationPolicy.plan(noCidNoPath, 50) }
        val plan = AllocationPolicy.plan(card(), 50)
        assertFalse(AllocationPolicy.matches(plan, noCidNoPath))
    }

    /** A good card snapshot, used where the test needs a plan that builds today. */
    private fun card(
        id: String = CARD_ID,
        sizeBytes: Long = CARD_BYTES,
        cid: String = VALID_CID,
        isSd: Boolean = true,
        isAdoptable: Boolean = true,
    ): DiskSnapshot = DiskSnapshot(
        id = id,
        sizeBytes = sizeBytes,
        cid = cid,
        isSd = isSd,
        isAdoptable = isAdoptable,
    )

    /** Invokes the private mapper the same way the privileged listing does. */
    private fun mapDisk(info: Any): DiskSnapshot {
        val method = NativeStorage::class.java.getDeclaredMethod("toDiskSnapshot", Any::class.java)
        method.isAccessible = true
        return method.invoke(NativeStorage(), info) as DiskSnapshot
    }

    private fun assertIllegalArgument(message: String = "", block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail(if (message.isEmpty()) "expected IllegalArgumentException" else message)
    }
}
