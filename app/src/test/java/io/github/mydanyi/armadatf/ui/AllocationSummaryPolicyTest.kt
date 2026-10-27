package io.github.mydanyi.armadatf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests for the completed-allocation summary.
 *
 * They pin the presentation contract: only live volumes prove adopted storage,
 * a plain public card is never completed Linux without an identity-bound
 * app-verified record, unknown capacities stay unknown, and a move-back
 * requirement (or an unreadable app list) blocks reallocation.
 */
class AllocationSummaryPolicyTest {

    private val diskBytes = 128L * 1024L * 1024L * 1024L

    @Test
    fun adoptedAndroidOnlyUsesTheLivePrivateCapacity() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_ANDROID_ONLY,
            volumes = listOf(volume(TYPE_PRIVATE, capacity = 100L * GIB)),
            moveBackRequired = false,
        )

        val summary = requireNotNull(AllocationSummaryPolicy.summarize(info, null))

        assertEquals(AllocationCompletion.ADOPTED, summary.completion)
        assertEquals(diskBytes, summary.totalBytes)
        assertEquals(100L * GIB, summary.androidBytes)
        assertEquals(AllocationSideState.ALLOCATED, summary.androidState)
        assertNull(summary.linuxBytes)
        assertEquals(AllocationSideState.NOT_ALLOCATED, summary.linuxState)
        assertTrue(summary.migrationEntryAvailable)
        assertFalse(summary.reallocationBlocked)
    }

    @Test
    fun adoptedAndroidOnlyFallsBackToTheWholeCardWhenThePrivateCapacityIsUnknown() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_ANDROID_ONLY,
            volumes = listOf(volume(TYPE_PRIVATE, capacity = null)),
        )

        val summary = requireNotNull(AllocationSummaryPolicy.summarize(info, null))

        assertEquals(diskBytes, summary.androidBytes)
        assertEquals(AllocationSideState.ALLOCATED, summary.androidState)
    }

    @Test
    fun mixedUsesLivePrivateAndPublicCapacities() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_MIXED,
            volumes = listOf(
                volume(TYPE_PRIVATE, capacity = 70L * GIB),
                volume(TYPE_PUBLIC, capacity = 50L * GIB),
            ),
        )

        val summary = requireNotNull(AllocationSummaryPolicy.summarize(info, null))

        assertEquals(AllocationCompletion.ADOPTED, summary.completion)
        assertEquals(70L * GIB, summary.androidBytes)
        assertEquals(50L * GIB, summary.linuxBytes)
        assertEquals(AllocationSideState.ALLOCATED, summary.linuxState)
        assertTrue(summary.migrationEntryAvailable)
    }

    @Test
    fun mixedUnknownCapacityStaysUnknownAndIsNeverDerivedFromTheDiskSize() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_MIXED,
            volumes = listOf(
                volume(TYPE_PRIVATE, capacity = 70L * GIB),
                volume(TYPE_PUBLIC, capacity = null),
            ),
        )

        val summary = requireNotNull(AllocationSummaryPolicy.summarize(info, null))

        assertNull(summary.linuxBytes)
        assertEquals(AllocationSideState.UNKNOWN, summary.linuxState)
    }

    @Test
    fun publicOnlyWithoutAVerifiedRecordIsNotACompletedLinuxCard() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_PUBLIC_ONLY,
            volumes = listOf(volume(TYPE_PUBLIC, capacity = 120L * GIB, fsUuid = "1A2B-3C4D")),
        )

        assertNull(AllocationSummaryPolicy.summarize(info, null))
    }

    @Test
    fun publicOnlyWithAMatchingRecordIsACompletedLinuxCard() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_PUBLIC_ONLY,
            volumes = listOf(volume(TYPE_PUBLIC, capacity = 120L * GIB, fsUuid = "1A2B-3C4D")),
        )
        val record = requireNotNull(VerifiedLinuxCard.from(info))

        val summary = requireNotNull(AllocationSummaryPolicy.summarize(info, record))

        assertEquals(AllocationCompletion.VERIFIED_LINUX_ONLY, summary.completion)
        assertEquals(120L * GIB, summary.linuxBytes)
        assertEquals(AllocationSideState.ALLOCATED, summary.linuxState)
        assertEquals(AllocationSideState.NOT_ALLOCATED, summary.androidState)
        assertFalse(summary.migrationEntryAvailable)
    }

    @Test
    fun verifiedLinuxUnknownCapacityStaysUnknown() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_PUBLIC_ONLY,
            volumes = listOf(volume(TYPE_PUBLIC, capacity = null, fsUuid = "1A2B-3C4D")),
        )
        val record = VerifiedLinuxCard(
            diskId = info.diskId,
            diskBytes = info.diskBytes,
            cardCid = info.cardCid,
            publicFsUuid = "1A2B-3C4D",
            publicPartGuid = "",
        )

        val summary = requireNotNull(AllocationSummaryPolicy.summarize(info, record))

        assertNull(summary.linuxBytes)
        assertEquals(AllocationSideState.UNKNOWN, summary.linuxState)
    }

    @Test
    fun recordForAnotherDiskDoesNotCompleteTheSelectedCard() {
        val info = publicOnlyInfo()
        val record = requireNotNull(VerifiedLinuxCard.from(info)).copy(diskId = "disk:8,0")

        assertNull(AllocationSummaryPolicy.summarize(info, record))
    }

    @Test
    fun recordWithADifferentSizeDoesNotCompleteASwappedCard() {
        val info = publicOnlyInfo()
        val record = requireNotNull(VerifiedLinuxCard.from(info)).copy(diskBytes = diskBytes / 2)

        assertNull(AllocationSummaryPolicy.summarize(info, record))
    }

    @Test
    fun recordWithADifferentCidDoesNotCompleteASwappedCard() {
        val info = publicOnlyInfo()
        val record = requireNotNull(VerifiedLinuxCard.from(info)).copy(
            cardCid = "ffffffffffffffffffffffffffffffff",
        )

        assertNull(AllocationSummaryPolicy.summarize(info, record))
    }

    @Test
    fun recordWithADifferentPublicVolumeDoesNotCompleteTheSameDiskId() {
        val info = publicOnlyInfo()
        val record = requireNotNull(VerifiedLinuxCard.from(info)).copy(publicFsUuid = "FFFF-0000")

        assertNull(AllocationSummaryPolicy.summarize(info, record))
    }

    @Test
    fun aRecordCannotBeBuiltFromABareDiskIdWithoutVolumeIdentity() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_PUBLIC_ONLY,
            volumes = listOf(volume(TYPE_PUBLIC, capacity = 120L * GIB)),
        )

        assertNull(VerifiedLinuxCard.from(info))
    }

    @Test
    fun hiddenCidStillMatchesByIdentityOfThePublicVolume() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_PUBLIC_ONLY,
            cardCid = "",
            volumes = listOf(
                volume(
                    TYPE_PUBLIC,
                    capacity = 120L * GIB,
                    fsUuid = "1A2B-3C4D",
                    partGuid = "11111111-2222-3333-4444-555555555555",
                ),
            ),
        )
        val record = requireNotNull(VerifiedLinuxCard.from(info))

        val summary = AllocationSummaryPolicy.summarize(info, record)

        assertNotNull(summary)
        assertEquals(AllocationCompletion.VERIFIED_LINUX_ONLY, summary?.completion)
    }

    @Test
    fun adoptedLayoutWithoutALivePrivateVolumeIsNotCompleted() {
        val info = snapshot(
            layout = LiveCardInfo.LAYOUT_ANDROID_ONLY,
            volumes = listOf(volume(TYPE_PUBLIC, capacity = 120L * GIB)),
        )

        assertNull(AllocationSummaryPolicy.summarize(info, null))
    }

    @Test
    fun unsupportedLayoutAndMissingInfoProduceNoSummary() {
        val unsupported = snapshot(
            layout = "unsupported",
            volumes = listOf(
                volume(TYPE_PRIVATE, capacity = 10L * GIB),
                volume(TYPE_PUBLIC, capacity = 10L * GIB),
            ),
        )

        assertNull(AllocationSummaryPolicy.summarize(unsupported, null))
        assertNull(AllocationSummaryPolicy.summarize(null, null))
    }

    @Test
    fun moveBackRequiredAndUnreadableAppsBothBlockReallocation() {
        val required = requireNotNull(
            AllocationSummaryPolicy.summarize(
                snapshot(
                    layout = LiveCardInfo.LAYOUT_ANDROID_ONLY,
                    volumes = listOf(volume(TYPE_PRIVATE, capacity = 100L * GIB)),
                    moveBackRequired = true,
                ),
                null,
            ),
        )
        assertTrue(required.reallocationBlocked)
        assertFalse(required.moveBackUnknown)

        val unknown = requireNotNull(
            AllocationSummaryPolicy.summarize(
                snapshot(
                    layout = LiveCardInfo.LAYOUT_ANDROID_ONLY,
                    volumes = listOf(volume(TYPE_PRIVATE, capacity = 100L * GIB)),
                    moveBackRequired = null,
                ),
                null,
            ),
        )
        assertTrue(unknown.reallocationBlocked)
        assertTrue(unknown.moveBackUnknown)

        val clear = requireNotNull(
            AllocationSummaryPolicy.summarize(
                snapshot(
                    layout = LiveCardInfo.LAYOUT_MIXED,
                    volumes = listOf(
                        volume(TYPE_PRIVATE, capacity = 70L * GIB),
                        volume(TYPE_PUBLIC, capacity = 50L * GIB),
                    ),
                    moveBackRequired = false,
                ),
                null,
            ),
        )
        assertFalse(clear.reallocationBlocked)
        assertFalse(clear.moveBackUnknown)
    }

    @Test
    fun unknownAfterVerifiedCompletionKeepsTheKnownLayoutButUnknownCapacities() {
        val adopted = requireNotNull(
            AllocationSummaryPolicy.unknownAfterCompletion(
                AllocationCompletion.ADOPTED,
                diskBytes,
                linuxAllocated = false,
            ),
        )
        assertEquals(AllocationCompletion.ADOPTED, adopted.completion)
        assertNull(adopted.androidBytes)
        assertEquals(AllocationSideState.UNKNOWN, adopted.androidState)
        assertEquals(AllocationSideState.NOT_ALLOCATED, adopted.linuxState)
        assertTrue(adopted.reallocationBlocked)

        val linuxOnly = requireNotNull(
            AllocationSummaryPolicy.unknownAfterCompletion(
                AllocationCompletion.VERIFIED_LINUX_ONLY,
                diskBytes,
                linuxAllocated = false,
            ),
        )
        assertEquals(AllocationSideState.NOT_ALLOCATED, linuxOnly.androidState)
        assertEquals(AllocationSideState.UNKNOWN, linuxOnly.linuxState)
        assertFalse(linuxOnly.migrationEntryAvailable)

        assertNull(
            AllocationSummaryPolicy.unknownAfterCompletion(
                AllocationCompletion.NONE,
                diskBytes,
                linuxAllocated = false,
            ),
        )
    }

    @Test
    fun noCardKeepsTheExistingDisabledEditorState() {
        assertEquals(
            AllocationScreenState.NO_CARD,
            AllocationScreenPolicy.state(
                hasCard = false,
                liveInfo = null,
                summary = null,
                inspectionFailed = true,
                editorChosenByUser = false,
            ),
        )
    }

    @Test
    fun selectedCardWithoutALiveLayoutIsInspecting() {
        assertEquals(
            AllocationScreenState.INSPECTING,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = null,
                summary = null,
                inspectionFailed = false,
                editorChosenByUser = false,
            ),
        )
    }

    @Test
    fun initialInspectionFailureIsNotAnEditor() {
        assertEquals(
            AllocationScreenState.INSPECTION_FAILED,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = null,
                summary = null,
                inspectionFailed = true,
                editorChosenByUser = false,
            ),
        )
    }

    @Test
    fun aKnownUnallocatedLayoutMayShowTheEditor() {
        assertEquals(
            AllocationScreenState.EDITOR,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = publicOnlyInfo(),
                summary = null,
                inspectionFailed = false,
                editorChosenByUser = false,
            ),
        )
        // A later failed refresh cannot make a known layout unknown again.
        assertEquals(
            AllocationScreenState.EDITOR,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = publicOnlyInfo(),
                summary = null,
                inspectionFailed = true,
                editorChosenByUser = false,
            ),
        )
    }

    @Test
    fun aCompletedSummaryStaysReadOnlyWhenTheRefreshFails() {
        assertEquals(
            AllocationScreenState.SUMMARY,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = null,
                summary = summary(),
                inspectionFailed = true,
                editorChosenByUser = false,
            ),
        )
    }

    @Test
    fun anExplicitEditorChoiceSurvivesASuccessfulInspection() {
        assertEquals(
            AllocationScreenState.EDITOR,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = publicOnlyInfo(),
                summary = summary(),
                inspectionFailed = false,
                editorChosenByUser = true,
            ),
        )
    }

    @Test
    fun aCompletedLiveCardWithoutAnEditorChoiceIsTheSummary() {
        assertEquals(
            AllocationScreenState.SUMMARY,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = publicOnlyInfo(),
                summary = summary(),
                inspectionFailed = false,
                editorChosenByUser = false,
            ),
        )
    }

    @Test
    fun anEditorChoiceWithoutASummaryStillWaitsForInspection() {
        // An explicit editor choice cannot bypass the inspection gate: with no
        // known layout and no completed summary the editor stays hidden.
        assertEquals(
            AllocationScreenState.INSPECTING,
            AllocationScreenPolicy.state(
                hasCard = true,
                liveInfo = null,
                summary = null,
                inspectionFailed = false,
                editorChosenByUser = true,
            ),
        )
    }

    private fun summary(): AllocationSummary = requireNotNull(
        AllocationSummaryPolicy.summarize(
            snapshot(
                layout = LiveCardInfo.LAYOUT_ANDROID_ONLY,
                volumes = listOf(volume(TYPE_PRIVATE, capacity = 100L * GIB)),
                moveBackRequired = false,
            ),
            null,
        ),
    )

    private fun publicOnlyInfo(): LiveCardInfo = snapshot(
        layout = LiveCardInfo.LAYOUT_PUBLIC_ONLY,
        volumes = listOf(
            volume(
                TYPE_PUBLIC,
                capacity = 120L * GIB,
                fsUuid = "1A2B-3C4D",
                partGuid = "11111111-2222-3333-4444-555555555555",
            ),
        ),
    )

    private fun snapshot(
        layout: String,
        volumes: List<LiveVolume>,
        diskId: String = "disk:179,0",
        cardCid: String = "12345678123456781234567812345678",
        moveBackRequired: Boolean? = false,
    ): LiveCardInfo = LiveCardInfo(
        diskId = diskId,
        diskBytes = diskBytes,
        cardCid = cardCid,
        isSd = true,
        layout = layout,
        selectedPrivateUuid = volumes.firstOrNull { it.type == TYPE_PRIVATE }?.fsUuid,
        moveBackRequired = moveBackRequired,
        volumes = volumes,
    )

    private fun volume(
        type: String,
        capacity: Long?,
        fsUuid: String? = null,
        partGuid: String? = null,
    ): LiveVolume = LiveVolume(
        type = type,
        fsUuid = fsUuid,
        partGuid = partGuid,
        capacityBytes = capacity,
    )

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
        const val TYPE_PUBLIC = "public"
        const val TYPE_PRIVATE = "private"
    }
}
