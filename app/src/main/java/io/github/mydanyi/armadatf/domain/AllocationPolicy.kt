package io.github.mydanyi.armadatf.domain

/**
 * Pure allocation maths for the native "mixed" adopted-storage layout.
 *
 * The Linux/public share is exactly the requested percentage of the card. The
 * Android side receives what is left after that share and after the fixed
 * adopted-storage metadata reservation. All arithmetic is done in [Long] so a
 * 256 GiB card or larger cannot overflow a 32-bit intermediate.
 */
object AllocationPolicy {

    /** Accepted inclusive range for the Linux/public share. */
    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 90

    /** Android adopted-storage metadata reservation (16 MiB). */
    const val ANDROID_METADATA_BYTES = 16L * 1024L * 1024L

    private const val HUNDRED = 100L

    fun isValidPercent(percent: Int): Boolean = percent in MIN_PERCENT..MAX_PERCENT

    /**
     * Builds a validated [AllocationPlan] for [disk] and the Linux/public
     * [linuxPercent] share.
     *
     * @throws IllegalArgumentException when the percentage is out of range, the
     *   card is not a positive-sized, adoptable SD card, or the CID is not a
     *   32-hex value.
     */
    fun plan(disk: DiskSnapshot, linuxPercent: Int): AllocationPlan {
        require(isValidPercent(linuxPercent)) {
            "linuxPercent must be in $MIN_PERCENT..$MAX_PERCENT but was $linuxPercent"
        }
        require(disk.isSd) { "disk '${disk.id}' is not an SD card" }
        require(disk.isAdoptable) { "disk '${disk.id}' is not adoptable" }
        require(disk.sizeBytes > 0L) { "disk '${disk.id}' has a non-positive size" }
        val cid = Identifiers.normalizeCid(disk.cid)
        return AllocationPlan(
            diskId = disk.id,
            diskBytes = disk.sizeBytes,
            cardCid = cid,
            linuxPercent = linuxPercent,
        )
    }

    /**
     * Splits the planned card into Android bytes first and Linux/public bytes
     * second. The Linux share is exact; the Android share is the remainder
     * minus [ANDROID_METADATA_BYTES].
     *
     * @throws IllegalArgumentException when [plan] violates the domain
     *   invariants (percentage range, positive size, valid CID).
     */
    fun capacity(plan: AllocationPlan): Pair<Long, Long> {
        requireValidPlan(plan)
        val linuxBytes = plan.diskBytes * plan.linuxPercent / HUNDRED
        val androidBytes = plan.diskBytes - linuxBytes - ANDROID_METADATA_BYTES
        return androidBytes to linuxBytes
    }

    /**
     * Whether [disk] is still the same physical card the [plan] was built for.
     * Compares the opaque id, the exact byte size, the CID (case-insensitive)
     * and the SD/adoptable flags.
     *
     * @throws IllegalArgumentException when [plan] itself is invalid.
     */
    fun matches(plan: AllocationPlan, disk: DiskSnapshot): Boolean {
        requireValidPlan(plan)
        if (!disk.isSd || !disk.isAdoptable) return false
        if (plan.diskId != disk.id) return false
        if (plan.diskBytes != disk.sizeBytes) return false
        if (!Identifiers.isValidCid(disk.cid)) return false
        return Identifiers.normalizeCid(plan.cardCid) == Identifiers.normalizeCid(disk.cid)
    }

    private fun requireValidPlan(plan: AllocationPlan) {
        require(isValidPercent(plan.linuxPercent)) {
            "linuxPercent must be in $MIN_PERCENT..$MAX_PERCENT but was ${plan.linuxPercent}"
        }
        require(plan.diskBytes > 0L) { "diskBytes must be positive but was ${plan.diskBytes}" }
        Identifiers.normalizeCid(plan.cardCid)
    }
}
