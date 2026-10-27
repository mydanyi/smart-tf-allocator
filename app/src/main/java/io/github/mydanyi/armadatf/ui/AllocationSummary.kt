package io.github.mydanyi.armadatf.ui

/**
 * Pure presentation policy for the completed-allocation summary.
 *
 * The summary never derives a split from the slider or from a saved default:
 * capacities come exclusively from the live volumes reported for the selected
 * card. A plain public-only card is ordinary portable storage and is only
 * presented as a completed Linux allocation when an app-verified [record]
 * matches the very same physical card and the very same public volume
 * identities.
 */

/** What currently proves that the selected card is already allocated. */
enum class AllocationCompletion {
    /** No proof; the editor is the only truthful presentation. */
    NONE,

    /** A live adopted private volume is present (all-Android or mixed). */
    ADOPTED,

    /** Public-only card whose identities match a verified app completion. */
    VERIFIED_LINUX_ONLY,
}

/** Per-side presentation of an allocated card. */
enum class AllocationSideState {
    ALLOCATED,
    NOT_ALLOCATED,
    UNKNOWN,
}

/**
 * One live volume of the selected disk, reduced to what the summary needs.
 * [capacityBytes] is `null` when the platform could not report a readable
 * capacity; it is never replaced by a guessed split.
 */
data class LiveVolume(
    val type: String,
    val fsUuid: String?,
    val partGuid: String?,
    val capacityBytes: Long?,
)

/**
 * Read-only live state of one card, as returned by the native
 * `migrationInfo(diskId)` call. Only the fields the summary uses are kept.
 */
data class LiveCardInfo(
    val diskId: String,
    val diskBytes: Long,
    val cardCid: String,
    val isSd: Boolean,
    val layout: String,
    val selectedPrivateUuid: String?,
    val moveBackRequired: Boolean?,
    val volumes: List<LiveVolume>,
) {
    /** The single adopted private volume of an android_only/mixed card. */
    val privateVolume: LiveVolume?
        get() = volumes.singleOrNull { it.type == TYPE_PRIVATE }

    /** The single public volume of a mixed/public_only card. */
    val publicVolume: LiveVolume?
        get() = volumes.singleOrNull { it.type == TYPE_PUBLIC }

    companion object {
        const val LAYOUT_ANDROID_ONLY = "android_only"
        const val LAYOUT_MIXED = "mixed"
        const val LAYOUT_PUBLIC_ONLY = "public_only"

        private const val TYPE_PUBLIC = "public"
        private const val TYPE_PRIVATE = "private"
    }
}

/**
 * Identity-bound proof of an app-verified all-Linux completion.
 *
 * Completion is deliberately not keyed on [diskId] alone: a swapped card can
 * reuse the same platform disk id, so the exact byte size, the CID (when the
 * firmware exposes one) and the live public volume identities must all still
 * match before the summary may claim a finished Linux allocation.
 */
data class VerifiedLinuxCard(
    val diskId: String,
    val diskBytes: Long,
    val cardCid: String,
    val publicFsUuid: String,
    val publicPartGuid: String,
) {
    /** True only when this record matches the live card and public volume. */
    fun matches(info: LiveCardInfo): Boolean {
        if (!info.isSd) return false
        if (info.diskId != diskId) return false
        if (diskBytes <= 0L || info.diskBytes != diskBytes) return false
        if (cardCid.isNotBlank() && !info.cardCid.equals(cardCid, ignoreCase = true)) return false
        if (info.layout != LiveCardInfo.LAYOUT_PUBLIC_ONLY) return false
        val public = info.publicVolume ?: return false
        if (publicFsUuid.isBlank() && publicPartGuid.isBlank()) return false
        if (publicFsUuid.isNotBlank() &&
            !public.fsUuid.orEmpty().equals(publicFsUuid, ignoreCase = true)
        ) {
            return false
        }
        if (publicPartGuid.isNotBlank() &&
            !public.partGuid.orEmpty().equals(publicPartGuid, ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    /**
     * True only when the live card actively contradicts this record: a
     * different size/CID, a non-public layout, or a different known public
     * volume identity. An unreadable/unmounted live volume does not contradict
     * a stored proof, so a transient mount state cannot erase completion.
     */
    fun isContradictedBy(info: LiveCardInfo): Boolean {
        if (info.diskId != diskId) return false
        if (!info.isSd) return true
        if (info.diskBytes != diskBytes) return true
        if (cardCid.isNotBlank() && info.cardCid.isNotBlank() &&
            !info.cardCid.equals(cardCid, ignoreCase = true)
        ) {
            return true
        }
        if (info.layout == LiveCardInfo.LAYOUT_ANDROID_ONLY ||
            info.layout == LiveCardInfo.LAYOUT_MIXED
        ) {
            return true
        }
        if (info.layout != LiveCardInfo.LAYOUT_PUBLIC_ONLY) return false
        val public = info.publicVolume ?: return false
        if (publicFsUuid.isNotBlank() && !public.fsUuid.isNullOrBlank() &&
            !public.fsUuid.equals(publicFsUuid, ignoreCase = true)
        ) {
            return true
        }
        if (publicPartGuid.isNotBlank() && !public.partGuid.isNullOrBlank() &&
            !public.partGuid.equals(publicPartGuid, ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    companion object {
        /**
         * Builds the durable record for a just-verified live public-only card.
         * Returns `null` when the live volumes carry no usable identity, so a
         * completion can never be bound to a bare disk id.
         */
        fun from(info: LiveCardInfo): VerifiedLinuxCard? {
            if (!info.isSd) return null
            if (info.layout != LiveCardInfo.LAYOUT_PUBLIC_ONLY) return null
            if (info.diskBytes <= 0L) return null
            val public = info.publicVolume ?: return null
            val fsUuid = public.fsUuid.orEmpty().trim()
            val partGuid = public.partGuid.orEmpty().trim()
            if (fsUuid.isEmpty() && partGuid.isEmpty()) return null
            return VerifiedLinuxCard(
                diskId = info.diskId,
                diskBytes = info.diskBytes,
                cardCid = info.cardCid.trim(),
                publicFsUuid = fsUuid,
                publicPartGuid = partGuid,
            )
        }
    }
}

/**
 * Everything the completed summary renders. [moveBackRequired] keeps the
 * native tri-state: `true` and `null` (apps unreadable) both block a
 * reallocation; only `false` allows it.
 */
data class AllocationSummary(
    val completion: AllocationCompletion,
    val totalBytes: Long?,
    val androidBytes: Long?,
    val androidState: AllocationSideState,
    val linuxBytes: Long?,
    val linuxState: AllocationSideState,
    val migrationEntryAvailable: Boolean,
    val moveBackRequired: Boolean?,
) {
    /** True while the card cannot be safely repartitioned. */
    val reallocationBlocked: Boolean get() = moveBackRequired != false

    /** True when the question could not be answered (apps unreadable). */
    val moveBackUnknown: Boolean get() = moveBackRequired == null
}

/** Which allocation presentation is currently truthful for the selected card. */
enum class AllocationScreenState {
    /** No card: keep the existing disabled empty editor. */
    NO_CARD,

    /** A card is selected but its live layout has not been read yet. */
    INSPECTING,

    /** The initial live inspection failed, so no layout is known. */
    INSPECTION_FAILED,

    /** A live layout is known and the card is not provably allocated. */
    EDITOR,

    /** A completed allocation is proven (live adopted or verified Linux). */
    SUMMARY,
}

/**
 * Decides which presentation may be shown. The editor is never shown for a
 * selected card whose live layout is still unknown: the default slider split
 * must not be presented as the card's real allocation. A completed summary
 * stays visible read-only if a later refresh fails.
 */
object AllocationScreenPolicy {
    fun state(
        hasCard: Boolean,
        liveInfo: LiveCardInfo?,
        summary: AllocationSummary?,
        inspectionFailed: Boolean,
        editorChosenByUser: Boolean,
    ): AllocationScreenState = when {
        !hasCard -> AllocationScreenState.NO_CARD
        summary != null && !editorChosenByUser -> AllocationScreenState.SUMMARY
        liveInfo != null -> AllocationScreenState.EDITOR
        inspectionFailed -> AllocationScreenState.INSPECTION_FAILED
        else -> AllocationScreenState.INSPECTING
    }
}

/** Pure decision table for the completed summary. */
object AllocationSummaryPolicy {

    /**
     * Returns the summary to present, or `null` when the selected card is not
     * provably allocated and the editor must stay visible.
     */
    fun summarize(info: LiveCardInfo?, record: VerifiedLinuxCard?): AllocationSummary? {
        if (info == null || !info.isSd) return null
        return when (info.layout) {
            LiveCardInfo.LAYOUT_ANDROID_ONLY -> adopted(info, linuxAllocated = false)
            LiveCardInfo.LAYOUT_MIXED -> adopted(info, linuxAllocated = true)
            LiveCardInfo.LAYOUT_PUBLIC_ONLY -> linuxOnly(info, record)
            else -> null
        }
    }

    /**
     * Presentation used immediately after a verified native partition while
     * the refresh is still in flight or failed: the completion is real, but
     * the live capacities are not known yet and stay unknown.
     */
    fun unknownAfterCompletion(
        completion: AllocationCompletion,
        totalBytes: Long?,
        linuxAllocated: Boolean,
    ): AllocationSummary? {
        if (completion == AllocationCompletion.NONE) return null
        return AllocationSummary(
            completion = completion,
            totalBytes = totalBytes?.takeIf { it > 0L },
            androidBytes = null,
            androidState = if (completion == AllocationCompletion.ADOPTED) {
                AllocationSideState.UNKNOWN
            } else {
                AllocationSideState.NOT_ALLOCATED
            },
            linuxBytes = null,
            linuxState = if (linuxAllocated) {
                AllocationSideState.UNKNOWN
            } else if (completion == AllocationCompletion.ADOPTED) {
                AllocationSideState.NOT_ALLOCATED
            } else {
                AllocationSideState.UNKNOWN
            },
            migrationEntryAvailable = completion == AllocationCompletion.ADOPTED,
            moveBackRequired = null,
        )
    }

    private fun adopted(info: LiveCardInfo, linuxAllocated: Boolean): AllocationSummary? {
        val privateVolume = info.privateVolume ?: return null
        val androidBytes = privateVolume.capacityBytes?.takeIf { it > 0L }
            ?: info.diskBytes.takeIf { it > 0L && !linuxAllocated }
        val publicBytes = info.publicVolume?.capacityBytes?.takeIf { it > 0L }
        val linuxBytes = if (linuxAllocated) publicBytes else null
        return AllocationSummary(
            completion = AllocationCompletion.ADOPTED,
            totalBytes = info.diskBytes.takeIf { it > 0L },
            androidBytes = androidBytes,
            androidState = if (androidBytes != null) {
                AllocationSideState.ALLOCATED
            } else {
                AllocationSideState.UNKNOWN
            },
            linuxBytes = linuxBytes,
            linuxState = when {
                !linuxAllocated -> AllocationSideState.NOT_ALLOCATED
                linuxBytes != null -> AllocationSideState.ALLOCATED
                else -> AllocationSideState.UNKNOWN
            },
            migrationEntryAvailable = true,
            moveBackRequired = info.moveBackRequired,
        )
    }

    private fun linuxOnly(
        info: LiveCardInfo,
        record: VerifiedLinuxCard?,
    ): AllocationSummary? {
        if (record == null || !record.matches(info)) return null
        val publicVolume = info.publicVolume ?: return null
        val linuxBytes = publicVolume.capacityBytes?.takeIf { it > 0L }
        return AllocationSummary(
            completion = AllocationCompletion.VERIFIED_LINUX_ONLY,
            totalBytes = info.diskBytes.takeIf { it > 0L },
            androidBytes = null,
            androidState = AllocationSideState.NOT_ALLOCATED,
            linuxBytes = linuxBytes,
            linuxState = if (linuxBytes != null) {
                AllocationSideState.ALLOCATED
            } else {
                AllocationSideState.UNKNOWN
            },
            migrationEntryAvailable = false,
            moveBackRequired = info.moveBackRequired,
        )
    }
}
