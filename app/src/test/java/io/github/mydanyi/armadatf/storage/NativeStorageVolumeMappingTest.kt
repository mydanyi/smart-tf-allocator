package io.github.mydanyi.armadatf.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake stand-in for the platform volume object used by the mapping test.
 *
 * It must be a public class with public fields: [java.lang.reflect.Field.get]
 * refuses to read even public fields when the declaring class is not public,
 * exactly like the real (public) platform volume class.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class FakePlatformVolume {
    @JvmField var id: String = "public:1A2B-3C4D"
    @JvmField var disk: Any? = null
    @JvmField var type: Int = 0
    @JvmField var state: Int = 2
    @JvmField var fsUuid: String? = null
    @JvmField var partGuid: String? = null
    @JvmField var path: String? = null
}

/**
 * Host-side mapping test for [NativeStorage.toVolumeSnapshot].
 *
 * The fake above exposes the same public fields the platform's volume object
 * does, so the reflective mapper can be exercised entirely on the JVM. No
 * Android binder, ServiceManager or other privileged access happens here.
 */
class NativeStorageVolumeMappingTest {

    @Test
    fun publicFatFsUuidIsPreservedVerbatimAndNotCanonicalised() {
        val info = FakePlatformVolume().apply {
            fsUuid = " 1A2B-3C4D "
            partGuid = null
        }

        val snapshot = NativeStorage().toVolumeSnapshot(info)

        // The short FAT id must survive; normalizeUuid would have nulled it.
        assertEquals("1A2B-3C4D", snapshot.fsUuid)
        assertNull(snapshot.partGuid)
    }

    @Test
    fun partGuidIsCanonicalisedToDashedLowercase() {
        val info = FakePlatformVolume().apply {
            fsUuid = "1A2B-3C4D"
            partGuid = "ABCDEF0123456789ABCDEF0123456789"
        }

        val snapshot = NativeStorage().toVolumeSnapshot(info)

        assertEquals("abcdef01-2345-6789-abcd-ef0123456789", snapshot.partGuid)
        assertEquals("1A2B-3C4D", snapshot.fsUuid)
    }
}

/**
 * Host-side tests for [VolumeIdentity], the pure guard used by the
 * volume-identity fallback when Android cannot read the card CID.
 *
 * The comparison must prove the same public/private volumes are still on the
 * disk, while tolerating a remount and platform enumeration order. Any change
 * to a volume identity field must fail closed.
 */
class VolumeIdentityTest {

    private companion object {
        const val DISK_ID = "disk:179,0"
        const val OTHER_DISK_ID = "disk:179,1"
        const val TYPE_PUBLIC = 0
        const val TYPE_PRIVATE = 1
        const val TYPE_EMULATED = 2
        const val STATE_MOUNTED = 2
        const val STATE_UNMOUNTED = 0
        const val PUBLIC_FS_UUID = "1A2B-3C4D"
        const val PRIVATE_FS_UUID = "ABCDEF0123456789ABCDEF0123456789"
        const val PRIVATE_GUID = "11111111222233334444555566667777"
    }

    private fun volume(
        id: String,
        type: Int,
        state: Int = STATE_MOUNTED,
        fsUuid: String?,
        partGuid: String? = null,
        path: String? = null,
        diskId: String = DISK_ID,
    ): NativeStorage.VolumeSnapshot = NativeStorage.VolumeSnapshot(
        id = id,
        diskId = diskId,
        type = type,
        state = state,
        fsUuid = fsUuid,
        partGuid = partGuid,
        path = path,
    )

    private fun publicVolume(
        fsUuid: String? = PUBLIC_FS_UUID,
        state: Int = STATE_MOUNTED,
        path: String? = "/storage/$PUBLIC_FS_UUID",
        diskId: String = DISK_ID,
    ): NativeStorage.VolumeSnapshot = volume(
        id = "public:$PUBLIC_FS_UUID",
        type = TYPE_PUBLIC,
        state = state,
        fsUuid = fsUuid,
        path = path,
        diskId = diskId,
    )

    private fun privateVolume(
        fsUuid: String? = PRIVATE_FS_UUID,
        partGuid: String? = PRIVATE_GUID,
        state: Int = STATE_MOUNTED,
        path: String? = "/mnt/expand/$PRIVATE_FS_UUID",
        diskId: String = DISK_ID,
    ): NativeStorage.VolumeSnapshot = volume(
        id = "private:$PRIVATE_FS_UUID",
        type = TYPE_PRIVATE,
        state = state,
        fsUuid = fsUuid,
        partGuid = partGuid,
        path = path,
        diskId = diskId,
    )

    @Test
    fun sameVolumesMatch() {
        val expected = listOf(publicVolume(), privateVolume())
        val fresh = listOf(publicVolume(), privateVolume())

        assertTrue(VolumeIdentity.matches(expected, fresh, DISK_ID))
    }

    @Test
    fun enumerationOrderIsIgnored() {
        val expected = listOf(publicVolume(), privateVolume())
        val fresh = listOf(privateVolume(), publicVolume())

        assertTrue(VolumeIdentity.matches(expected, fresh, DISK_ID))
    }

    @Test
    fun remountChangesStateAndPathButStillMatches() {
        val expected = listOf(publicVolume(), privateVolume())
        val fresh = listOf(
            publicVolume(state = STATE_UNMOUNTED, path = "/mnt/media_rw/1A2B-3C4D"),
            privateVolume(state = STATE_UNMOUNTED, path = "/mnt/expand/remounted"),
        )

        assertTrue(VolumeIdentity.matches(expected, fresh, DISK_ID))
    }

    @Test
    fun emulatedVolumesAndOtherDisksAreIgnored() {
        val expected = listOf(publicVolume(), privateVolume())
        val fresh = listOf(
            publicVolume(),
            privateVolume(),
            volume(id = "emulated:0", type = TYPE_EMULATED, fsUuid = "emulated"),
            publicVolume(diskId = OTHER_DISK_ID),
        )

        assertTrue(VolumeIdentity.matches(expected, fresh, DISK_ID))
    }

    @Test
    fun changedVolumeTypeIsRefused() {
        val expected = listOf(publicVolume(), privateVolume())
        val fresh = listOf(
            volume(id = "public:$PUBLIC_FS_UUID", type = TYPE_PRIVATE, fsUuid = PUBLIC_FS_UUID),
            privateVolume(),
        )

        assertFalse(VolumeIdentity.matches(expected, fresh, DISK_ID))
    }

    @Test
    fun changedFsUuidIsRefused() {
        val expected = listOf(publicVolume(), privateVolume())
        val fresh = listOf(publicVolume(fsUuid = "1A2B-3C4E"), privateVolume())

        assertFalse(VolumeIdentity.matches(expected, fresh, DISK_ID))
    }

    @Test
    fun changedPartitionGuidIsRefused() {
        val expected = listOf(publicVolume(), privateVolume())
        val fresh = listOf(
            publicVolume(),
            privateVolume(partGuid = "99999999888877776666555544443333"),
        )

        assertFalse(VolumeIdentity.matches(expected, fresh, DISK_ID))
    }

    @Test
    fun missingVolumeIsRefused() {
        val expected = listOf(publicVolume(), privateVolume())

        assertFalse(VolumeIdentity.matches(expected, listOf(publicVolume()), DISK_ID))
    }
}
