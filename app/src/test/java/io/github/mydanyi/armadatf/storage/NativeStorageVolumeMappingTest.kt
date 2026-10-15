package io.github.mydanyi.armadatf.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
