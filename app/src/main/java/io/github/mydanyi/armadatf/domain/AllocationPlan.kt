package io.github.mydanyi.armadatf.domain

/**
 * The supported layouts for one whole TF card.
 *
 * [nativeMethod] is the hidden `IStorageManager` method that creates this
 * layout, so routing never has to guess between private, public and mixed.
 * [handoffName] is the stable token written into the Linux handoff payload.
 */
enum class AllocationMode(
    val nativeMethod: String,
    val handoffName: String,
) {
    /** 0% Linux: the whole card becomes adopted Android private storage. */
    ANDROID_ONLY("partitionPrivate", "android_only"),

    /** 10..90% Linux: one public volume plus one adopted private volume. */
    MIXED("partitionMixed", "mixed"),

    /** 100% Linux: the whole card becomes one public FAT volume. */
    LINUX_ONLY("partitionPublic", "linux_only");

    companion object {
        /**
         * Resolves the mode for a supported Linux percentage.
         *
         * @throws IllegalArgumentException for the unsupported gaps, so an
         *   invalid share can never be routed to a native formatter.
         */
        fun forPercent(percent: Int): AllocationMode = when {
            percent == AllocationPolicy.MIN_PERCENT -> ANDROID_ONLY
            percent in AllocationPolicy.MIXED_MIN_PERCENT..AllocationPolicy.MIXED_MAX_PERCENT -> MIXED
            percent == AllocationPolicy.MAX_PERCENT -> LINUX_ONLY
            else -> throw IllegalArgumentException(
                "linuxPercent must be 0, ${AllocationPolicy.MIXED_MIN_PERCENT}.." +
                    "${AllocationPolicy.MIXED_MAX_PERCENT} or 100 but was $percent",
            )
        }
    }
}

/**
 * An immutable allocation decision for one card.
 *
 * [cardCid] is always normalised to lowercase by [AllocationPolicy.plan], and is
 * the empty string only for a card whose hardware CID the firmware does not
 * expose; such a plan is accepted only with a valid [platformPath], and the
 * Linux handoff then identifies the card by volume instead of a CID.
 * [linuxPercent] is the share of the card reserved for the native Linux/public
 * side: 0 means all-Android, 10..90 keeps the mixed layout and 100 means
 * all-Linux.
 */
data class AllocationPlan(
    val diskId: String,
    val diskBytes: Long,
    val cardCid: String,
    val linuxPercent: Int,
    val platformPath: String? = null,
) {
    /** The routed layout for [linuxPercent]; throws for an unsupported share. */
    val mode: AllocationMode get() = AllocationMode.forPercent(linuxPercent)
}
