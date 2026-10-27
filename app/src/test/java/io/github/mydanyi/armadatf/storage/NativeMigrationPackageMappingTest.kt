package io.github.mydanyi.armadatf.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake stand-in for the platform `ApplicationInfo`, exposing the same public
 * fields the mapper reads. It must stay public: reflective field access needs a
 * public declaring class, exactly like the real platform class.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class FakeApplicationInfo {
    @JvmField var packageName: String = "com.example.app"
    @JvmField var flags: Int = 0
    @JvmField var installLocation: Int = 0
    @JvmField var volumeUuid: String? = null
    @JvmField var sourceDir: String? = null
    @JvmField var dataDir: String? = null
}

/**
 * Fake stand-in for the platform `PackageInfo`, exposing the same public fields
 * the mapper reads. Like the real platform type it keeps the application
 * metadata in a nested `applicationInfo` field.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class FakePackageInfo {
    @JvmField var applicationInfo: FakeApplicationInfo? = null
    @JvmField var installLocation: Int = 0
}

/**
 * Host-side tests for the package metadata mapping in [NativeMigration].
 *
 * No binder or privileged call happens: the mapper only reads public fields and
 * derives the source volume from a `/mnt/expand/<uuid>/...` path when the
 * platform leaves `volumeUuid` empty.
 */
class NativeMigrationPackageMappingTest {

    private companion object {
        const val CARD_UUID_RAW = "ABCDEF0123456789ABCDEF0123456789"
        const val OTHER_UUID_RAW = "11111111222233334444555566667777"
    }

    @Test
    fun systemAndUpdatedSystemFlagsAreMapped() {
        val system = FakeApplicationInfo().apply { flags = 1 }
        val updated = FakeApplicationInfo().apply { flags = 1 shl 7 }

        assertTrue(NativeMigration().toPackageSnapshot(system).isSystem)
        assertFalse(NativeMigration().toPackageSnapshot(system).isUpdatedSystem)
        assertTrue(NativeMigration().toPackageSnapshot(updated).isUpdatedSystem)
        assertFalse(NativeMigration().toPackageSnapshot(updated).isSystem)
    }

    @Test
    fun installLocationIsPreserved() {
        val info = FakeApplicationInfo().apply { installLocation = 2 }
        assertEquals(2, NativeMigration().toPackageSnapshot(info).installLocation)
    }

    @Test
    fun volumeUuidIsTrimmedAndPreferred() {
        val info = FakeApplicationInfo().apply {
            volumeUuid = " $OTHER_UUID_RAW "
            sourceDir = "/mnt/expand/$CARD_UUID_RAW/app/com.example/base.apk"
        }

        val snapshot = NativeMigration().toPackageSnapshot(info)

        assertEquals(OTHER_UUID_RAW, snapshot.sourceUuid)
    }

    @Test
    fun sourceDirProvidesTheUuidWhenVolumeUuidIsEmpty() {
        val info = FakeApplicationInfo().apply {
            sourceDir = "/mnt/expand/$CARD_UUID_RAW/app/com.example/base.apk"
        }

        assertEquals(CARD_UUID_RAW, NativeMigration().toPackageSnapshot(info).sourceUuid)
    }

    @Test
    fun dataDirProvidesTheUuidAsALastResort() {
        val info = FakeApplicationInfo().apply {
            dataDir = "/mnt/expand/$CARD_UUID_RAW/user/0/com.example"
        }

        assertEquals(CARD_UUID_RAW, NativeMigration().toPackageSnapshot(info).sourceUuid)
    }

    @Test
    fun internalStorageStaysNull() {
        val info = FakeApplicationInfo().apply {
            sourceDir = "/data/app/~~abc/com.example-xyz/base.apk"
            dataDir = "/data/user/0/com.example"
        }

        assertNull(NativeMigration().toPackageSnapshot(info).sourceUuid)
    }

    @Test
    fun malformedExpandSegmentIsNotGuessedAsUuid() {
        val info = FakeApplicationInfo().apply {
            sourceDir = "/mnt/expand/not-a-uuid/app/com.example/base.apk"
        }

        assertNull(NativeMigration().toPackageSnapshot(info).sourceUuid)
        assertNull(NativeMigration().volumeUuidFromPath("/mnt/expand//app/base.apk"))
        assertNull(NativeMigration().volumeUuidFromPath("/storage/1A2B-3C4D/app.apk"))
    }

    @Test
    fun packageInfoIsUnwrappedAndKeepsItsOwnInstallLocation() {
        val applicationInfo = FakeApplicationInfo().apply {
            packageName = "com.example.nested"
            flags = 1
            installLocation = 2
            sourceDir = "/mnt/expand/$CARD_UUID_RAW/app/com.example/base.apk"
        }
        val packageInfo = FakePackageInfo().apply {
            this.applicationInfo = applicationInfo
            installLocation = 1
        }

        val snapshot = NativeMigration().packageInfoSnapshot(packageInfo)

        assertEquals("com.example.nested", snapshot.packageName)
        assertTrue(snapshot.isSystem)
        assertEquals(1, snapshot.installLocation)
        assertEquals(CARD_UUID_RAW, snapshot.sourceUuid)
    }

    @Test
    fun factorySourceDirectoryIsClassifiedWithoutSystemFlags() {
        val info = FakeApplicationInfo().apply {
            packageName = "com.zui.notes"
            flags = 0
            sourceDir = "/system/preinstall/com.zui.notes/com.zui.notes.apk"
        }

        val snapshot = NativeMigration().toPackageSnapshot(info)

        assertFalse(snapshot.isSystem)
        assertFalse(snapshot.isUpdatedSystem)
        assertTrue(snapshot.isPreinstalled)
        assertNull(snapshot.sourceUuid)
    }

    @Test
    fun everyTrustedPartitionSourceDirectoryIsClassified() {
        for (partition in listOf("system_ext", "product", "vendor", "odm", "oem")) {
            val info = FakeApplicationInfo().apply {
                packageName = "com.oem.app"
                flags = 0
                sourceDir = "/$partition/preload/com.oem.app/base.apk"
            }
            assertTrue(
                "$partition must be trusted",
                NativeMigration().toPackageSnapshot(info).isPreinstalled,
            )
        }
    }

    @Test
    fun updatedUnflaggedOemAppStaysPreinstalledThroughCatalogIdentity() {
        // The OEM update moved the code under /data/app and dropped the system
        // flags, but the original factory identity is still catalogued.
        val info = FakeApplicationInfo().apply {
            packageName = "com.zui.gallery"
            flags = 0
            sourceDir = "/data/app/~~abc==/com.zui.gallery-def==/base.apk"
            dataDir = "/data/user/0/com.zui.gallery"
        }

        val snapshot = NativeMigration().toPackageSnapshot(
            info,
            setOf("com.zui.notes", "com.zui.gallery"),
        )

        assertNull(snapshot.sourceUuid)
        assertFalse(snapshot.isSystem)
        assertTrue(snapshot.isPreinstalled)
    }

    @Test
    fun ordinaryDataAppAbsentFromTheCatalogIsNotPreinstalled() {
        for (packageName in listOf("com.quark.browser", "tv.danmaku.bili")) {
            val info = FakeApplicationInfo().apply {
                this.packageName = packageName
                flags = 0
                sourceDir = "/data/app/~~xyz==/$packageName-abc==/base.apk"
            }
            val snapshot = NativeMigration().toPackageSnapshot(info, setOf("com.zui.notes"))
            assertFalse(packageName, snapshot.isPreinstalled)
        }
    }

    @Test
    fun packageInfoAlsoClassifiesFactoryOrigin() {
        val applicationInfo = FakeApplicationInfo().apply {
            packageName = "com.zui.notes"
            flags = 0
            sourceDir = "/system/preinstall/com.zui.notes/com.zui.notes.apk"
        }
        val packageInfo = FakePackageInfo().apply { this.applicationInfo = applicationInfo }

        assertTrue(NativeMigration().packageInfoSnapshot(packageInfo).isPreinstalled)

        val updated = FakeApplicationInfo().apply {
            packageName = "com.zui.notes"
            flags = 0
            sourceDir = "/data/app/~~abc==/com.zui.notes-def==/base.apk"
        }
        val updatedPackageInfo = FakePackageInfo().apply { this.applicationInfo = updated }
        assertTrue(
            NativeMigration().packageInfoSnapshot(updatedPackageInfo, setOf("com.zui.notes"))
                .isPreinstalled,
        )
    }
}
