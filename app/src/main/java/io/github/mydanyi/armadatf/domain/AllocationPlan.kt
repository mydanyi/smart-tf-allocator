package io.github.mydanyi.armadatf.domain

/**
 * An immutable allocation decision for one card.
 *
 * [cardCid] is always normalised to lowercase by [AllocationPolicy.plan].
 * [linuxPercent] is the share of the card reserved for the native Linux/public
 * partition; the Android side receives the remainder minus the adopted-storage
 * metadata reservation.
 */
data class AllocationPlan(
    val diskId: String,
    val diskBytes: Long,
    val cardCid: String,
    val linuxPercent: Int,
)
