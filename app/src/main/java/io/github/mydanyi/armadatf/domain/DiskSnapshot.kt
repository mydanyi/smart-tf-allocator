package io.github.mydanyi.armadatf.domain

/**
 * A pure, host-testable view of one physical storage disk.
 *
 * [id] is an opaque disk identifier. The privileged service builds it from the
 * platform `DiskInfo.id` (`disk:<major>,<minor>`), but the domain never parses
 * or constrains its syntax.
 *
 * [cid] is the hardware card id when the platform exposes a readable, valid one,
 * and the empty string otherwise. [platformPath] is the normalised `sysPath` the
 * platform reports for a removable MMC card (`/sys/devices/.../mmc_host/.../
 * block/mmcblkN`); it is the only fallback identity when the CID is unreadable.
 * Both default to the pre-existing behaviour so older callers keep a readable
 * CID and no path.
 */
data class DiskSnapshot(
    val id: String,
    val sizeBytes: Long,
    val cid: String,
    val isSd: Boolean,
    val isAdoptable: Boolean,
    val platformPath: String? = null,
)

/**
 * Normalisation and validation for the platform disk `sysPath`.
 *
 * Firmware that blocks `/sys/dev/block/<major>:<minor>` also reports a doubled
 * slash in `DiskInfo.sysPath`, so only repeated slashes are collapsed — nothing
 * else about the path is rewritten. A usable fallback path must be an absolute
 * MMC block path (`/sys/devices/.../mmc_host/.../block/mmcblkN`); dot segments,
 * relative paths, `/sys/class/...` aliases and USB paths are rejected rather
 * than guessed at.
 */
internal object PlatformPaths {

    private val DUPLICATE_SLASHES = Regex("/{2,}")
    private val MMC_BLOCK_NAME = Regex("mmcblk[0-9]+")

    /**
     * Collapses duplicate slashes and returns the canonical path, or `null` when
     * [raw] is absent or is not a usable removable MMC block path.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        if (!trimmed.startsWith('/')) return null
        val segments = trimmed.replace(DUPLICATE_SLASHES, "/")
            .split('/')
            .filter { it.isNotEmpty() }
        if (segments.size < 3) return null
        if (segments.any { it == "." || it == ".." }) return null
        if (segments[0] != "sys" || segments[1] != "devices") return null
        val mmcIndex = segments.indexOf("mmc_host")
        // At least one device segment before mmc_host and one between it and
        // the block directory.
        if (mmcIndex <= 2) return null
        val blockIndex = segments.size - 2
        if (segments[blockIndex] != "block") return null
        if (blockIndex <= mmcIndex + 1) return null
        if (!MMC_BLOCK_NAME.matches(segments.last())) return null
        return "/" + segments.joinToString("/")
    }

    /** Whether [path] is already a canonical removable MMC block path. */
    fun isSdMmcPath(path: String?): Boolean = path != null && normalize(path) == path
}
