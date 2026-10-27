package io.github.mydanyi.armadatf.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the factory preload catalog and its path classification.
 *
 * The scanner is production code; only the APK identity reader is injected so
 * no platform `PackageParser` is needed on the host. Every temporary tree is
 * created below this project's own `build/test-factory-catalog` directory and
 * removed again, never in the OS temp directory.
 */
class FactoryAppCatalogTest {

    private val root = File("build/test-factory-catalog").absoluteFile

    @Before
    fun setUp() {
        root.deleteRecursively()
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun scanRoot(): File = File(root, "system").apply { mkdirs() }

    private fun apk(parent: File, name: String): File {
        val file = File(parent, name)
        file.parentFile?.mkdirs()
        file.writeText("apk")
        return file
    }

    private fun catalogWith(reader: ApkIdentityReader, directory: File): FactoryAppCatalog =
        FactoryAppCatalog(reader = reader, scanRoots = listOf(directory.path))

    @Test
    fun missingPreloadDirectoriesYieldAnEmptyCatalog() {
        val catalog = FactoryAppCatalog(
            reader = { error("no APK may be read") },
            scanRoots = listOf(File(root, "missing").path),
        )

        assertTrue(catalog.packages().isEmpty())
    }

    @Test
    fun identitiesAreCollectedOnceAndDeduplicated() {
        val system = scanRoot()
        val preinstall = File(system, "preinstall").apply { mkdirs() }
        val preload = File(system, "preload").apply { mkdirs() }
        apk(preinstall, "notes.apk")
        apk(preinstall, "nested/gallery.apk")
        apk(preload, "notes-again.apk")

        var reads = 0
        val reader = ApkIdentityReader { file ->
            reads++
            when (file.name) {
                "notes.apk", "notes-again.apk" -> "com.zui.notes"
                "gallery.apk" -> "com.zui.gallery"
                else -> null
            }
        }
        val catalog = catalogWith(reader, system)

        assertEquals(setOf("com.zui.notes", "com.zui.gallery"), catalog.packages())
        assertEquals(3, reads)

        // The factory image cannot change during the process lifetime: the
        // second query must be served from the cache.
        assertEquals(setOf("com.zui.notes", "com.zui.gallery"), catalog.packages())
        assertEquals(3, reads)
        assertTrue(catalog.isFactoryPackage("com.zui.notes"))
        assertFalse(catalog.isFactoryPackage("com.quark.browser"))
        assertFalse(catalog.isFactoryPackage(""))
    }

    @Test
    fun unparseableApkFailsTheCatalogInsteadOfBecomingEmpty() {
        val preinstall = File(scanRoot(), "preinstall").apply { mkdirs() }
        apk(preinstall, "broken.apk")

        val catalog = catalogWith({ null }, preinstall.parentFile)

        assertThrows(NativeMigrationException::class.java) { catalog.packages() }
    }

    @Test
    fun readerFailureFailsTheCatalog() {
        val preinstall = File(scanRoot(), "preinstall").apply { mkdirs() }
        apk(preinstall, "broken.apk")

        val catalog = catalogWith(
            { throw NativeMigrationException("parser unavailable") },
            preinstall.parentFile,
        )

        assertThrows(NativeMigrationException::class.java) { catalog.packages() }
    }

    @Test
    fun preloadPathThatIsNotADirectoryFailsTheCatalog() {
        val system = scanRoot()
        File(system, "preinstall").writeText("not a directory")

        val catalog = catalogWith({ null }, system)

        assertThrows(NativeMigrationException::class.java) { catalog.packages() }
    }

    @Test
    fun symlinkEscapingTheTrustedPartitionIsNotCatalogued() {
        val system = scanRoot()
        val preinstall = File(system, "preinstall").apply { mkdirs() }
        val outside = apk(File(root, "outside").apply { mkdirs() }, "escaped.apk")
        val link = File(preinstall, "escaped.apk")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            // Symbolic links are not available on every host; nothing to prove.
            return
        }

        val catalog = catalogWith({ "com.evil.app" }, system)

        assertTrue(catalog.packages().isEmpty())
    }

    // ------------------------------------------------------------------
    // Path classification
    // ------------------------------------------------------------------

    @Test
    fun onlyExactTrustedPartitionBoundariesAreFactoryPaths() {
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/system"))
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/system/"))
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/system/preinstall/app.apk"))
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/system_ext/app/base.apk"))
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/product/preload/x.apk"))
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/vendor/app/x.apk"))
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/odm/app/x.apk"))
        assertTrue(FactoryAppCatalog.isTrustedFactoryPath("/oem/preinstall/x.apk"))

        assertFalse(FactoryAppCatalog.isTrustedFactoryPath("/systematic/app/x.apk"))
        assertFalse(FactoryAppCatalog.isTrustedFactoryPath("/system_extra/x.apk"))
        assertFalse(FactoryAppCatalog.isTrustedFactoryPath("/data/app/~~x/base.apk"))
        assertFalse(FactoryAppCatalog.isTrustedFactoryPath("/mnt/expand/uuid/app/base.apk"))
        assertFalse(FactoryAppCatalog.isTrustedFactoryPath("system/preinstall/x.apk"))
        assertFalse(FactoryAppCatalog.isTrustedFactoryPath(null))
        assertFalse(FactoryAppCatalog.isTrustedFactoryPath("   "))
    }

    @Test
    fun pathEscapesAreNormalisedBeforeClassification() {
        assertEquals(
            "/data/app/com.example/base.apk",
            FactoryAppCatalog.normalizePath("/system/../data/app/com.example/base.apk"),
        )
        assertFalse(
            FactoryAppCatalog.isTrustedFactoryPath("/system/../data/app/com.example/base.apk"),
        )
        assertTrue(
            FactoryAppCatalog.isTrustedFactoryPath("/system/./preinstall/com.example/base.apk"),
        )
        assertFalse(FactoryAppCatalog.isTrustedFactoryPath("/system//../data/app/base.apk"))
    }
}
