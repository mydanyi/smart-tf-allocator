package io.github.mydanyi.armadatf.storage

import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Reads the public package identity of one APK file without installing it.
 *
 * A separate interface keeps the catalog scanner host-testable: production uses
 * the platform parser, tests inject a plain identity mapping.
 */
fun interface ApkIdentityReader {

    /** @return the APK's package name, or `null` when it carries no identity. */
    fun packageNameOf(apk: File): String?
}

/**
 * Reads the identity with the platform's own
 * `PackageParser.parsePackageLite(File, int)`.
 *
 * `PackageParser` is hidden from the public SDK, so the class and method are
 * resolved reflectively; only the public `packageName` field of the returned
 * `PackageLite` is read. Read-only: nothing is installed and no Context is
 * needed.
 */
object PlatformApkIdentityReader : ApkIdentityReader {

    override fun packageNameOf(apk: File): String? {
        val parser = try {
            Class.forName(PACKAGE_PARSER)
        } catch (e: ClassNotFoundException) {
            throw NativeMigrationException("$PACKAGE_PARSER is not available", e)
        }
        val method = try {
            parser.getMethod("parsePackageLite", File::class.java, Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            throw NativeMigrationException("$PACKAGE_PARSER.parsePackageLite is not available", e)
        }
        val lite = try {
            method.invoke(null, apk, 0)
        } catch (e: InvocationTargetException) {
            throw NativeMigrationException("${apk.path} could not be parsed", e.cause ?: e)
        } catch (e: ReflectiveOperationException) {
            throw NativeMigrationException("${apk.path} could not be parsed", e)
        } ?: return null
        return try {
            lite.javaClass.getField("packageName").get(lite) as? String
        } catch (e: ReflectiveOperationException) {
            throw NativeMigrationException("${apk.path} has no package identity", e)
        }
    }

    private const val PACKAGE_PARSER = "android.content.pm.PackageParser"
}

/**
 * Identity catalog of the factory-preloaded application code shipped in the
 * read-only system image.
 *
 * An OEM preload can lose its `FLAG_SYSTEM`/`FLAG_UPDATED_SYSTEM_APP` flags and
 * have its code moved under `/data/app` by its own update, so flags alone cannot
 * prove origin. The catalog therefore remembers the package identities of the
 * APKs found in the `preinstall`/`preload` directories directly under the six
 * trusted partitions; the actual source directory is classified independently.
 *
 * Scanning is read-only and happens at most once per catalog instance: the
 * factory image cannot change during the service's lifetime. A directory or APK
 * that exists but cannot be read or parsed is an error — an empty catalog would
 * silently make a factory app movable. Missing directories are normal.
 */
class FactoryAppCatalog(
    private val reader: ApkIdentityReader = PlatformApkIdentityReader,
    private val scanRoots: List<String> = TRUSTED_PARTITIONS,
    private val preloadDirectories: List<String> = PRELOAD_DIRECTORIES,
) {

    @Volatile
    private var cached: Set<String>? = null

    /**
     * Cached factory package identities.
     *
     * @throws NativeMigrationException when a present preload directory or APK
     *   cannot be read or parsed.
     */
    fun packages(): Set<String> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load().also { cached = it }
        }
    }

    /** True when [packageName] belongs to the factory-preloaded catalog. */
    fun isFactoryPackage(packageName: String?): Boolean {
        val name = packageName?.trim().orEmpty()
        if (name.isEmpty()) return false
        return name in packages()
    }

    private fun load(): Set<String> {
        val identities = LinkedHashSet<String>()
        for (root in scanRoots) {
            for (name in preloadDirectories) {
                val directory = File(root, name)
                if (!directory.exists()) continue
                if (!directory.isDirectory) {
                    throw NativeMigrationException(
                        "factory preload path is not a directory: ${directory.path}",
                    )
                }
                val canonical = trustedCanonical(directory)
                    ?: throw NativeMigrationException(
                        "factory preload directory is outside the system image: " +
                            directory.path,
                    )
                scanDirectory(canonical, identities)
            }
        }
        return identities.toSet()
    }

    private fun scanDirectory(directory: File, identities: MutableSet<String>) {
        val children = directory.listFiles()
            ?: throw NativeMigrationException(
                "factory preload directory could not be read: ${directory.path}",
            )
        for (child in children) {
            // A symlink whose target leaves the trusted partitions must never
            // contribute a package identity.
            val canonical = trustedCanonical(child) ?: continue
            when {
                canonical.isDirectory -> scanDirectory(canonical, identities)
                canonical.isFile && canonical.name.lowercase().endsWith(APK_SUFFIX) ->
                    identities.add(identityOf(canonical))
            }
        }
    }

    /** Canonical path of [file], or `null` when it lies outside the scan roots. */
    private fun trustedCanonical(file: File): File? {
        val canonical = try {
            file.canonicalFile
        } catch (e: java.io.IOException) {
            throw NativeMigrationException("${file.path} could not be resolved", e)
        }
        return canonical.takeIf { candidate ->
            scanRoots.any { isWithinRoot(candidate.path, it) }
        }
    }

    private fun identityOf(apk: File): String {
        if (!apk.canRead()) {
            throw NativeMigrationException("factory application APK is not readable: ${apk.path}")
        }
        val identity = reader.packageNameOf(apk)?.trim().orEmpty()
        if (identity.isEmpty()) {
            throw NativeMigrationException(
                "factory application APK has no package identity: ${apk.path}",
            )
        }
        return identity
    }

    companion object {

        /** Read-only partitions whose code is always factory-preloaded. */
        val TRUSTED_PARTITIONS = listOf(
            "/system",
            "/system_ext",
            "/product",
            "/vendor",
            "/odm",
            "/oem",
        )

        /** OEM preload directories looked up directly under each partition. */
        val PRELOAD_DIRECTORIES = listOf("preinstall", "preload")

        private const val APK_SUFFIX = ".apk"

        /**
         * Lexically canonical absolute path: duplicate/trailing separators and
         * `.`/`..` segments are resolved, and `..` never escapes the root.
         *
         * @return the normalized path, or `null` for a relative/blank value.
         */
        fun normalizePath(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (!trimmed.startsWith("/")) return null
            val segments = ArrayList<String>()
            for (segment in trimmed.split('/')) {
                when (segment) {
                    "", "." -> continue
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> segments.add(segment)
                }
            }
            return "/" + segments.joinToString("/")
        }

        /**
         * True when [raw] is an absolute path inside one of
         * [TRUSTED_PARTITIONS], using exact path-component boundaries so a
         * directory such as `/systematic` is not mistaken for `/system`.
         */
        fun isTrustedFactoryPath(raw: String?): Boolean {
            val path = normalizePath(raw) ?: return false
            return TRUSTED_PARTITIONS.any { isWithinRoot(path, it) }
        }

        /** True when [path] equals [root] or lies below it, component-wise. */
        internal fun isWithinRoot(path: String?, root: String?): Boolean {
            val normalizedPath = normalizePath(path) ?: return false
            val normalizedRoot = normalizePath(root) ?: return false
            if (normalizedRoot == "/") return true
            return normalizedPath == normalizedRoot ||
                normalizedPath.startsWith("$normalizedRoot/")
        }
    }
}
