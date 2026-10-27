package io.github.mydanyi.armadatf.domain

/**
 * Pure allocation maths for the whole-card layouts.
 *
 * The supported Linux/public shares are the two pure endpoints (0% all-Android
 * and 100% all-Linux) plus the existing mixed range 10..90. The gaps are
 * rejected because the Android formatter cannot create those layouts.
 *
 * The Linux/public share is exactly the requested percentage of the card. The
 * Android side receives what is left after that share and after the fixed
 * adopted-storage metadata reservation. All arithmetic is done in [Long] so a
 * 256 GiB card or larger cannot overflow a 32-bit intermediate.
 */
object AllocationPolicy {

    /** Accepted overall bounds for the Linux/public share. */
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100

    /** Accepted inclusive range for the mixed layout only. */
    const val MIXED_MIN_PERCENT = 10
    const val MIXED_MAX_PERCENT = 90

    /** Android adopted-storage metadata reservation (16 MiB). */
    const val ANDROID_METADATA_BYTES = 16L * 1024L * 1024L

    private const val HUNDRED = 100L

    /** 1..4 snap down to the 0% endpoint. */
    private const val LOWER_SNAP_END = 4

    /** 96..99 snap up to the 100% endpoint; 91..95 go to 90. */
    private const val UPPER_SNAP_START = 96

    /**
     * Accepts the pure endpoints and the mixed range only; the gaps between an
     * endpoint and the mixed range stay invalid.
     */
    fun isValidPercent(percent: Int): Boolean =
        percent == MIN_PERCENT ||
            percent == MAX_PERCENT ||
            percent in MIXED_MIN_PERCENT..MIXED_MAX_PERCENT

    /**
     * Builds a validated [AllocationPlan] for [disk] and the Linux/public
     * [linuxPercent] share.
     *
     * A readable CID is always canonicalised. When the firmware hides the CID,
     * the card is accepted only with a valid removable MMC platform path; the
     * plan then carries `cardCid = ""` and that path, and the Linux handoff
     * identifies the card by volume. A missing CID with no path stays invalid.
     *
     * @throws IllegalArgumentException when the percentage is out of range, the
     *   card is not a positive-sized, adoptable SD card, the CID is malformed,
     *   or the CID is unreadable and no valid platform path is present.
     */
    fun plan(disk: DiskSnapshot, linuxPercent: Int): AllocationPlan {
        require(isValidPercent(linuxPercent)) {
            "linuxPercent must be 0, $MIXED_MIN_PERCENT..$MIXED_MAX_PERCENT or 100 " +
                "but was $linuxPercent"
        }
        require(disk.isSd) { "disk '${disk.id}' is not an SD card" }
        require(disk.isAdoptable) { "disk '${disk.id}' is not adoptable" }
        require(disk.sizeBytes > 0L) { "disk '${disk.id}' has a non-positive size" }
        val path = PlatformPaths.normalize(disk.platformPath)
            ?.takeIf { PlatformPaths.isSdMmcPath(it) }
        val rawCid = disk.cid
        val cid = if (rawCid.isEmpty()) {
            require(path != null) {
                "disk '${disk.id}' has no readable CID and no valid platform path"
            }
            ""
        } else {
            Identifiers.normalizeCid(rawCid)
        }
        return AllocationPlan(
            diskId = disk.id,
            diskBytes = disk.sizeBytes,
            cardCid = cid,
            linuxPercent = linuxPercent,
            platformPath = path,
        )
    }

    /**
     * Splits the planned card into Android bytes first and Linux/public bytes
     * second.
     *
     * The 0% endpoint reserves the whole card for Android minus the metadata
     * reservation; the 100% endpoint gives the whole card to Linux with no
     * metadata reservation. The mixed shares are unchanged: the Linux share is
     * exact and the Android share is the remainder minus
     * [ANDROID_METADATA_BYTES].
     *
     * This overload needs only the card size and share, so callers that show an
     * approximate capacity (the UI) do not have to claim a physical identity.
     *
     * @throws IllegalArgumentException for an unsupported percentage or a
     *   non-positive size.
     */
    fun capacity(diskBytes: Long, linuxPercent: Int): Pair<Long, Long> {
        require(isValidPercent(linuxPercent)) {
            "linuxPercent must be 0, $MIXED_MIN_PERCENT..$MIXED_MAX_PERCENT or 100 " +
                "but was $linuxPercent"
        }
        require(diskBytes > 0L) { "diskBytes must be positive but was $diskBytes" }
        return splitCapacity(diskBytes, linuxPercent)
    }

    /**
     * @throws IllegalArgumentException when [plan] violates the domain
     *   invariants (percentage range, positive size, CID or fallback path).
     */
    fun capacity(plan: AllocationPlan): Pair<Long, Long> {
        requireValidPlan(plan)
        return splitCapacity(plan.diskBytes, plan.linuxPercent)
    }

    private fun splitCapacity(diskBytes: Long, linuxPercent: Int): Pair<Long, Long> {
        if (linuxPercent == MAX_PERCENT) return 0L to diskBytes
        val linuxBytes = diskBytes * linuxPercent / HUNDRED
        val androidBytes = diskBytes - linuxBytes - ANDROID_METADATA_BYTES
        return androidBytes to linuxBytes
    }

    /**
     * Routes [plan] to the single native formatter call for its mode.
     *
     * Only [AllocationMode.MIXED] carries a ratio, and that ratio is always in
     * [MIXED_MIN_PERCENT]..[MIXED_MAX_PERCENT] because [AllocationMode] itself
     * refuses every other share. The 0% and 100% endpoints therefore always
     * dispatch a two-argument private/public call and can never send a bogus
     * mixed ratio.
     *
     * @throws IllegalArgumentException when [plan] is invalid.
     */
    fun partitionCall(plan: AllocationPlan): PartitionCall {
        requireValidPlan(plan)
        val mode = plan.mode
        return PartitionCall(
            method = mode.nativeMethod,
            diskId = plan.diskId,
            ratio = if (mode == AllocationMode.MIXED) plan.linuxPercent else null,
        )
    }

    /**
     * Snaps an arbitrary slider value onto the supported sequence
     * 0, 10..90, 100, clamping the input to [MIN_PERCENT]..[MAX_PERCENT] first.
     * Values in 1..4 snap down to 0, 5..9 up to 10, 91..95 down to 90 and
     * 96..99 up to 100.
     */
    fun snapPercent(input: Int): Int {
        val clamped = input.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return when {
            clamped <= LOWER_SNAP_END -> MIN_PERCENT
            clamped < MIXED_MIN_PERCENT -> MIXED_MIN_PERCENT
            clamped <= MIXED_MAX_PERCENT -> clamped
            clamped < UPPER_SNAP_START -> MIXED_MAX_PERCENT
            else -> MAX_PERCENT
        }
    }

    /**
     * Returns the next supported percentage from [current] in [direction]
     * (positive = towards 100, negative = towards 0), so a D-pad never gets
     * stuck on the 10/11 or 90/100 gaps. The supported sequence is
     * 0, 10, 11..90, 100; the ends stay put when stepped past.
     */
    fun adjacentPercent(current: Int, direction: Int): Int {
        val snapped = snapPercent(current)
        if (direction == 0) return snapped
        if (direction > 0) {
            return when (snapped) {
                MIN_PERCENT -> MIXED_MIN_PERCENT
                MAX_PERCENT -> MAX_PERCENT
                MIXED_MAX_PERCENT -> MAX_PERCENT
                else -> snapped + 1
            }
        }
        return when (snapped) {
            MIN_PERCENT -> MIN_PERCENT
            MAX_PERCENT -> MIXED_MAX_PERCENT
            MIXED_MIN_PERCENT -> MIN_PERCENT
            else -> snapped - 1
        }
    }

    /**
     * Whether [disk] is still the same physical card the [plan] was built for.
     * Compares the opaque id, the exact byte size and the SD/adoptable flags.
     * The platform path is compared when the plan captured one, and the CID is
     * compared whenever the plan knows it. A plan without a CID (the volume
     * fallback) therefore matches only a disk that still reports the same valid
     * MMC path — never a path-less disk.
     *
     * @throws IllegalArgumentException when [plan] itself is invalid.
     */
    fun matches(plan: AllocationPlan, disk: DiskSnapshot): Boolean {
        requireValidPlan(plan)
        if (!disk.isSd || !disk.isAdoptable) return false
        if (plan.diskId != disk.id) return false
        if (plan.diskBytes != disk.sizeBytes) return false
        val diskPath = PlatformPaths.normalize(disk.platformPath)
        if (plan.platformPath != null && plan.platformPath != diskPath) return false
        if (plan.cardCid.isNotEmpty()) {
            if (!Identifiers.isValidCid(disk.cid)) return false
            return plan.cardCid == Identifiers.normalizeCid(disk.cid)
        }
        return PlatformPaths.isSdMmcPath(diskPath)
    }

    /** One native formatter call: [ratio] is non-null only for a mixed layout. */
    data class PartitionCall(val method: String, val diskId: String, val ratio: Int?)

    private fun requireValidPlan(plan: AllocationPlan) {
        require(isValidPercent(plan.linuxPercent)) {
            "linuxPercent must be 0, $MIXED_MIN_PERCENT..$MIXED_MAX_PERCENT or 100 " +
                "but was ${plan.linuxPercent}"
        }
        require(plan.diskBytes > 0L) { "diskBytes must be positive but was ${plan.diskBytes}" }
        require(plan.platformPath == null || PlatformPaths.isSdMmcPath(plan.platformPath)) {
            "platformPath must be a canonical MMC block path but was ${plan.platformPath}"
        }
        if (plan.cardCid.isEmpty()) {
            require(PlatformPaths.isSdMmcPath(plan.platformPath)) {
                "cardCid is empty and no valid platform path was captured"
            }
        } else {
            Identifiers.normalizeCid(plan.cardCid)
        }
    }
}
