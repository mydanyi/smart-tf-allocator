package io.github.mydanyi.armadatf.storage

import io.github.mydanyi.armadatf.domain.AllocationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests for [VolumeLayoutVerifier].
 *
 * The verifier is pure, so every endpoint rule is exercised with synthetic
 * snapshots only: no binder, no card and no privileged access. The point of
 * these tests is that the two pure endpoints must prove exactly what the mixed
 * layout proves before the service may report `partitioned` — the private
 * volume actually mounted and canonical for all-Android, the public volume
 * actually mounted for all-Linux — and that a stale sibling volume or an old
 * identifier keeps the verification waiting.
 */
class VolumeLayoutVerifierTest {

    private companion object {
        const val DISK_ID = "disk:179,64"
        const val TYPE_PUBLIC = 0
        const val TYPE_PRIVATE = 1
        const val STATE_MOUNTED = 2
        const val STATE_UNMOUNTED = 0

        /** Public FAT ids are short and never canonical UUIDs. */
        const val PUBLIC_FS_UUID = "1A2B-3C4D"

        const val PRIVATE_FS_UUID = "ABCDEF0123456789ABCDEF0123456789"
        const val PRIVATE_GUID = "11111111222233334444555566667777"

        const val OLD_PRIVATE_FS_UUID = "00000000111122223333444455556666"
        const val OLD_PRIVATE_GUID = "99999999888877776666555544443333"
        const val OLD_PUBLIC_FS_UUID = "5E6F-7A8B"
    }

    private fun publicVolume(
        state: Int = STATE_MOUNTED,
        fsUuid: String? = PUBLIC_FS_UUID,
    ): NativeStorage.VolumeSnapshot = NativeStorage.VolumeSnapshot(
        id = "public:$PUBLIC_FS_UUID",
        diskId = DISK_ID,
        type = TYPE_PUBLIC,
        state = state,
        fsUuid = fsUuid,
        partGuid = null,
        path = "/storage/$PUBLIC_FS_UUID",
    )

    private fun privateVolume(
        state: Int = STATE_MOUNTED,
        fsUuid: String? = PRIVATE_FS_UUID,
        partGuid: String? = PRIVATE_GUID,
    ): NativeStorage.VolumeSnapshot = NativeStorage.VolumeSnapshot(
        id = "private:$PRIVATE_FS_UUID",
        diskId = DISK_ID,
        type = TYPE_PRIVATE,
        state = state,
        fsUuid = fsUuid,
        partGuid = partGuid,
        path = "/mnt/expand/$PRIVATE_FS_UUID",
    )

    private fun waitingReason(check: VolumeLayoutCheck): String {
        assertTrue("expected Waiting but was $check", check is VolumeLayoutCheck.Waiting)
        return (check as VolumeLayoutCheck.Waiting).reason
    }

    private fun verified(check: VolumeLayoutCheck): VolumeLayoutCheck.Verified {
        assertTrue("expected Verified but was $check", check is VolumeLayoutCheck.Verified)
        return check as VolumeLayoutCheck.Verified
    }

    // ------------------------------------------------------------------
    // All-Android endpoint
    // ------------------------------------------------------------------

    @Test
    fun androidOnlyMountedFreshPrivateVolumeIsVerified() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.ANDROID_ONLY,
            listOf(privateVolume()),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        val verified = verified(check)
        assertNull(verified.publicVolume)
        assertEquals(PRIVATE_GUID, verified.privateVolume?.partGuid)
    }

    @Test
    fun androidOnlyUnmountedPrivateVolumeWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.ANDROID_ONLY,
            listOf(privateVolume(state = STATE_UNMOUNTED)),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("private_volume_not_mounted", waitingReason(check))
    }

    @Test
    fun androidOnlyNonCanonicalPrivateFsUuidWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.ANDROID_ONLY,
            listOf(privateVolume(fsUuid = "1A2B-3C4D")),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("private_fs_uuid_not_canonical", waitingReason(check))
    }

    @Test
    fun androidOnlyMissingPrivateFsUuidWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.ANDROID_ONLY,
            listOf(privateVolume(fsUuid = null)),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("private_fs_uuid_not_canonical", waitingReason(check))
    }

    @Test
    fun androidOnlyOldPrivateGuidWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.ANDROID_ONLY,
            listOf(privateVolume(fsUuid = OLD_PRIVATE_FS_UUID, partGuid = OLD_PRIVATE_GUID)),
            knownPrivateGuids = setOf(OLD_PRIVATE_GUID),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("private_partition_guid_not_new", waitingReason(check))
    }

    @Test
    fun androidOnlyStalePublicVolumeWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.ANDROID_ONLY,
            listOf(privateVolume(), publicVolume()),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("public_volume_present", waitingReason(check))
    }

    // ------------------------------------------------------------------
    // All-Linux endpoint
    // ------------------------------------------------------------------

    @Test
    fun linuxOnlyMountedFreshPublicVolumeIsVerified() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.LINUX_ONLY,
            listOf(publicVolume()),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        val verified = verified(check)
        assertEquals(PUBLIC_FS_UUID, verified.publicVolume?.fsUuid)
        assertNull(verified.privateVolume)
    }

    @Test
    fun linuxOnlyUnmountedPublicVolumeWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.LINUX_ONLY,
            listOf(publicVolume(state = STATE_UNMOUNTED)),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("public_volume_not_mounted", waitingReason(check))
    }

    @Test
    fun linuxOnlyOldPublicFsUuidWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.LINUX_ONLY,
            listOf(publicVolume(fsUuid = OLD_PUBLIC_FS_UUID)),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = setOf(OLD_PUBLIC_FS_UUID.lowercase()),
        )

        assertEquals("public_fs_uuid_not_new", waitingReason(check))
    }

    @Test
    fun linuxOnlyStalePrivateVolumeWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.LINUX_ONLY,
            listOf(publicVolume(), privateVolume()),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("private_volume_present", waitingReason(check))
    }

    // ------------------------------------------------------------------
    // Mixed layout stays as it was
    // ------------------------------------------------------------------

    @Test
    fun mixedMountedPairWithFreshGuidIsVerified() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.MIXED,
            listOf(publicVolume(), privateVolume()),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        val verified = verified(check)
        assertEquals(PUBLIC_FS_UUID, verified.publicVolume?.fsUuid)
        assertEquals(PRIVATE_GUID, verified.privateVolume?.partGuid)
    }

    @Test
    fun mixedUnmountedPairWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.MIXED,
            listOf(publicVolume(), privateVolume(state = STATE_UNMOUNTED)),
            knownPrivateGuids = emptySet(),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("volume_pair_not_mounted", waitingReason(check))
    }

    @Test
    fun mixedOldPrivateGuidWaits() {
        val check = VolumeLayoutVerifier.check(
            AllocationMode.MIXED,
            listOf(
                publicVolume(),
                privateVolume(fsUuid = OLD_PRIVATE_FS_UUID, partGuid = OLD_PRIVATE_GUID),
            ),
            knownPrivateGuids = setOf(OLD_PRIVATE_GUID),
            knownPublicFsUuids = emptySet(),
        )

        assertEquals("private_partition_guid_not_new", waitingReason(check))
    }
}
