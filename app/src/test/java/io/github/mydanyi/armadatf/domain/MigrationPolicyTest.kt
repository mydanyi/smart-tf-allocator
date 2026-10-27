package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the pure migration rules.
 *
 * No Android framework type is used here: the tests pin down which applications
 * may move in each direction, which source/target pairs are legal, how the
 * internal-storage sentinel is normalised, and when a reported success really
 * matches the requested destination.
 */
class MigrationPolicyTest {

    private companion object {
        /** Adopted private volumes are commonly reported without hyphens. */
        const val CARD_UUID_RAW = "ABCDEF0123456789ABCDEF0123456789"
        const val CARD_UUID = "abcdef01-2345-6789-abcd-ef0123456789"
        const val OTHER_UUID_RAW = "11111111222233334444555566667777"
        const val OTHER_UUID = "11111111-2222-3333-4444-555566667777"

        const val MMC_PATH =
            "/sys/devices/platform/soc/8804000.sdhci/mmc_host/mmc1/mmc1:b368/block/mmcblk1"
        const val MMC_PATH_OTHER =
            "/sys/devices/platform/soc/8804000.sdhci/mmc_host/mmc2/mmc2:b368/block/mmcblk2"
        const val VALID_CID = "1b2c3d4e5f60718293a4b5c6d7e8f901"
    }

    private fun app(
        packageName: String = "com.example.app",
        isSystem: Boolean = false,
        isUpdatedSystem: Boolean = false,
        isPreinstalled: Boolean = false,
        installLocation: Int = MigrationPolicy.INSTALL_LOCATION_AUTO,
        sourceUuid: String? = null,
        isDeviceAdmin: Boolean? = false,
    ): InstalledApp = InstalledApp(
        packageName = packageName,
        isSystem = isSystem,
        isUpdatedSystem = isUpdatedSystem,
        isPreinstalled = isPreinstalled,
        installLocation = installLocation,
        sourceUuid = sourceUuid,
        isDeviceAdmin = isDeviceAdmin,
        sizeBytes = null,
    )

    private fun disk(
        id: String = "disk:179,0",
        sizeBytes: Long = 64L * 1024L * 1024L * 1024L,
        cid: String = VALID_CID,
        isSd: Boolean = true,
        isAdoptable: Boolean = true,
        platformPath: String? = null,
    ): DiskSnapshot = DiskSnapshot(
        id = id,
        sizeBytes = sizeBytes,
        cid = cid,
        isSd = isSd,
        isAdoptable = isAdoptable,
        platformPath = platformPath,
    )

    // ------------------------------------------------------------------
    // Volume UUID normalisation
    // ------------------------------------------------------------------

    @Test
    fun internalStorageIsNormalisedToTheJsonSentinel() {
        assertEquals(VolumeUuids.INTERNAL, VolumeUuids.normalize(null))
        assertEquals(VolumeUuids.INTERNAL, VolumeUuids.normalize(""))
        assertEquals(VolumeUuids.INTERNAL, VolumeUuids.normalize("  "))
        assertEquals(VolumeUuids.INTERNAL, VolumeUuids.normalize("internal"))
        assertEquals(VolumeUuids.INTERNAL, VolumeUuids.normalize("private"))
    }

    @Test
    fun physicalPrimaryStorageIsNotTreatedAsInternal() {
        // A physical-primary device is outside the supported internal <->
        // adopted-SD pair. The raw platform value must survive normalisation so
        // the policy rejects it instead of claiming it is already internal.
        assertEquals("primary_physical", VolumeUuids.normalize("primary_physical"))
        assertFalse(VolumeUuids.isInternal("primary_physical"))
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validatePrimaryMove("primary_physical", "internal", CARD_UUID_RAW),
        )
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validatePrimaryMove("primary_physical", CARD_UUID_RAW, CARD_UUID_RAW),
        )
        assertFalse(MigrationPolicy.destinationMatches("internal", "primary_physical"))
    }

    @Test
    fun privateUuidIsCanonicalisedForComparison() {
        assertEquals(CARD_UUID, VolumeUuids.normalize(CARD_UUID_RAW))
        assertEquals(CARD_UUID, VolumeUuids.normalize(CARD_UUID))
        assertTrue(VolumeUuids.same(CARD_UUID_RAW, CARD_UUID))
        assertFalse(VolumeUuids.same(CARD_UUID_RAW, OTHER_UUID_RAW))
    }

    @Test
    fun nativeArgumentIsNullOnlyForInternalStorage() {
        assertNull(VolumeUuids.toNative("internal"))
        assertNull(VolumeUuids.toNative(null))
        assertEquals(CARD_UUID_RAW, VolumeUuids.toNative(" $CARD_UUID_RAW "))
        assertEquals(CARD_UUID_RAW, VolumeUuids.toNative(CARD_UUID_RAW))
    }

    @Test
    fun onlyInternalAndRealVolumeUuidsAreValidTargets() {
        assertTrue(VolumeUuids.isValidTarget("internal"))
        assertTrue(VolumeUuids.isValidTarget(CARD_UUID_RAW))
        assertTrue(VolumeUuids.isValidTarget(CARD_UUID))
        assertFalse(VolumeUuids.isValidTarget(null))
        assertFalse(VolumeUuids.isValidTarget(""))
        assertFalse(VolumeUuids.isValidTarget("1A2B-3C4D"))
        assertFalse(VolumeUuids.isValidTarget("disk:179,0"))
    }

    // ------------------------------------------------------------------
    // Application eligibility for the card
    // ------------------------------------------------------------------

    @Test
    fun autoAndPreferExternalAppsAreEligibleForTheCard() {
        assertTrue(
            MigrationPolicy.eligibleForSd(
                app(installLocation = MigrationPolicy.INSTALL_LOCATION_AUTO),
                CARD_UUID_RAW,
            ),
        )
        assertTrue(
            MigrationPolicy.eligibleForSd(
                app(installLocation = MigrationPolicy.INSTALL_LOCATION_PREFER_EXTERNAL),
                CARD_UUID_RAW,
            ),
        )
    }

    @Test
    fun everyManifestInstallLocationIsEligibleForTheCard() {
        // An adopted private card is not gated by the manifest install
        // location: the platform's MovePackageHelper moves a package onto the
        // private volume whatever auto/internalOnly/preferExternal says, so
        // none of these values may hide the app or reject the request.
        for (location in listOf(
            MigrationPolicy.INSTALL_LOCATION_UNSPECIFIED,
            MigrationPolicy.INSTALL_LOCATION_AUTO,
            MigrationPolicy.INSTALL_LOCATION_INTERNAL_ONLY,
            MigrationPolicy.INSTALL_LOCATION_PREFER_EXTERNAL,
        )) {
            val internal = app(installLocation = location)
            assertTrue(MigrationPolicy.eligibleForSd(internal, CARD_UUID_RAW))
            assertNull(MigrationPolicy.validateAppMove(internal, CARD_UUID_RAW, CARD_UUID_RAW))
        }
    }

    @Test
    fun unspecifiedAndInternalOnlyAppsRoundTripThroughTheCard() {
        for (location in listOf(
            MigrationPolicy.INSTALL_LOCATION_UNSPECIFIED,
            MigrationPolicy.INSTALL_LOCATION_INTERNAL_ONLY,
        )) {
            // On internal storage: listed and offered the card.
            val onInternal = app(installLocation = location)
            assertTrue(MigrationPolicy.visibleInInventory(onInternal, CARD_UUID_RAW))
            assertTrue(MigrationPolicy.eligibleForSd(onInternal, CARD_UUID_RAW))
            assertFalse(MigrationPolicy.eligibleForInternal(onInternal, CARD_UUID_RAW))
            assertNull(
                MigrationPolicy.validateAppMove(onInternal, CARD_UUID_RAW, CARD_UUID_RAW),
            )

            // After the move to the card: still listed, now only movable back.
            val onCard = onInternal.copy(sourceUuid = CARD_UUID_RAW)
            assertTrue(MigrationPolicy.visibleInInventory(onCard, CARD_UUID_RAW))
            assertFalse(MigrationPolicy.eligibleForSd(onCard, CARD_UUID_RAW))
            assertTrue(MigrationPolicy.eligibleForInternal(onCard, CARD_UUID_RAW))
            assertNull(
                MigrationPolicy.validateAppMove(onCard, VolumeUuids.INTERNAL, CARD_UUID_RAW),
            )

            // Back on internal storage: the card direction is offered again.
            val backOnInternal = onCard.copy(sourceUuid = null)
            assertTrue(MigrationPolicy.visibleInInventory(backOnInternal, CARD_UUID_RAW))
            assertTrue(MigrationPolicy.eligibleForSd(backOnInternal, CARD_UUID_RAW))
            assertNull(
                MigrationPolicy.validateAppMove(
                    backOnInternal,
                    CARD_UUID_RAW,
                    CARD_UUID_RAW,
                ),
            )
        }
    }

    @Test
    fun inventoryListsEveryNonProtectedUserAppWhateverItsVolume() {
        assertTrue(MigrationPolicy.visibleInInventory(app(), CARD_UUID_RAW))
        assertTrue(MigrationPolicy.visibleInInventory(app(), null))
        assertTrue(
            MigrationPolicy.visibleInInventory(
                app(installLocation = MigrationPolicy.INSTALL_LOCATION_INTERNAL_ONLY),
                CARD_UUID_RAW,
            ),
        )
        assertTrue(
            MigrationPolicy.visibleInInventory(app(sourceUuid = OTHER_UUID_RAW), CARD_UUID_RAW),
        )
        assertTrue(
            MigrationPolicy.visibleInInventory(app(sourceUuid = CARD_UUID_RAW), CARD_UUID_RAW),
        )
    }

    @Test
    fun inventoryStillExcludesProtectedAndFactoryApps() {
        assertFalse(
            MigrationPolicy.visibleInInventory(
                app(packageName = MigrationPolicy.ALLOCATOR_PACKAGE),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.visibleInInventory(
                app(packageName = MigrationPolicy.SHIZUKU_PACKAGE),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(MigrationPolicy.visibleInInventory(app(isSystem = true), CARD_UUID_RAW))
        assertFalse(
            MigrationPolicy.visibleInInventory(app(isUpdatedSystem = true), CARD_UUID_RAW),
        )
        assertFalse(
            MigrationPolicy.visibleInInventory(app(isPreinstalled = true), CARD_UUID_RAW),
        )
        // A factory app already on the selected card stays hidden too: it is not
        // migratable in either direction, so no move-back row may appear.
        assertFalse(
            MigrationPolicy.visibleInInventory(
                app(isSystem = true, sourceUuid = CARD_UUID_RAW),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.visibleInInventory(
                app(isUpdatedSystem = true, sourceUuid = CARD_UUID_RAW),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.visibleInInventory(
                app(isPreinstalled = true, sourceUuid = CARD_UUID_RAW),
                CARD_UUID_RAW,
            ),
        )
        // An ordinary app on the card is still listed so it can move back.
        assertTrue(
            MigrationPolicy.visibleInInventory(app(sourceUuid = CARD_UUID_RAW), CARD_UUID_RAW),
        )
    }

    @Test
    fun systemAndUpdatedSystemAppsAreNeverMovedToTheCard() {
        assertFalse(MigrationPolicy.eligibleForSd(app(isSystem = true), CARD_UUID_RAW))
        assertFalse(MigrationPolicy.eligibleForSd(app(isUpdatedSystem = true), CARD_UUID_RAW))
        assertFalse(MigrationPolicy.eligibleForSd(app(isPreinstalled = true), CARD_UUID_RAW))
    }

    @Test
    fun factoryAppsAreNeverMovableInEitherDirectionEvenOnTheCard() {
        for (factory in listOf(
            app(isSystem = true, sourceUuid = CARD_UUID_RAW),
            app(isUpdatedSystem = true, sourceUuid = CARD_UUID_RAW),
            app(isPreinstalled = true, sourceUuid = CARD_UUID_RAW),
        )) {
            assertFalse(MigrationPolicy.eligibleForSd(factory, CARD_UUID_RAW))
            assertFalse(MigrationPolicy.eligibleForInternal(factory, CARD_UUID_RAW))
            assertEquals(
                MigrationErrors.PACKAGE_NOT_ELIGIBLE,
                MigrationPolicy.validateAppMove(factory, "internal", CARD_UUID_RAW),
            )
            assertEquals(
                MigrationErrors.PACKAGE_NOT_ELIGIBLE,
                MigrationPolicy.validateAppMove(factory, CARD_UUID_RAW, CARD_UUID_RAW),
            )
        }

        val factoryOnInternal = app(isPreinstalled = true)
        assertFalse(MigrationPolicy.eligibleForSd(factoryOnInternal, CARD_UUID_RAW))
        assertEquals(
            MigrationErrors.PACKAGE_NOT_ELIGIBLE,
            MigrationPolicy.validateAppMove(factoryOnInternal, CARD_UUID_RAW, CARD_UUID_RAW),
        )
    }

    @Test
    fun ordinaryDataAppsAbsentFromTheCatalogStayNormal() {
        val quark = app(packageName = "com.quark.browser")
        val bili = app(packageName = "tv.danmaku.bili")
        for (normal in listOf(quark, bili)) {
            assertFalse(MigrationPolicy.isFactoryApp(normal))
            assertTrue(MigrationPolicy.visibleInInventory(normal, CARD_UUID_RAW))
            assertTrue(MigrationPolicy.eligibleForSd(normal, CARD_UUID_RAW))
            assertNull(MigrationPolicy.validateAppMove(normal, CARD_UUID_RAW, CARD_UUID_RAW))
        }
    }

    @Test
    fun deviceAdminAppsAreExcludedAndUnknownAdminStateDefersToThePlatform() {
        assertFalse(
            MigrationPolicy.eligibleForSd(app(isDeviceAdmin = true), CARD_UUID_RAW),
        )
        assertEquals(
            MigrationErrors.PACKAGE_NOT_ELIGIBLE,
            MigrationPolicy.validateAppMove(
                app(isDeviceAdmin = true),
                CARD_UUID_RAW,
                CARD_UUID_RAW,
            ),
        )

        // The optional admin read is denied when the privileged caller lacks
        // MANAGE_USERS, so `null` is "unknown", not "not an admin". Such an app
        // stays offered; the platform's own move rejects an actual admin with
        // MOVE_FAILED_DEVICE_ADMIN.
        assertTrue(MigrationPolicy.eligibleForSd(app(isDeviceAdmin = null), CARD_UUID_RAW))
        assertNull(
            MigrationPolicy.validateAppMove(
                app(isDeviceAdmin = null),
                CARD_UUID_RAW,
                CARD_UUID_RAW,
            ),
        )
    }

    @Test
    fun unknownAdminStateDoesNotBypassTheOtherEligibilityRules() {
        assertFalse(MigrationPolicy.eligibleForSd(app(isDeviceAdmin = null), null))
        assertFalse(
            MigrationPolicy.eligibleForSd(
                app(isDeviceAdmin = null, isSystem = true),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.eligibleForSd(
                app(isDeviceAdmin = null, isUpdatedSystem = true),
                CARD_UUID_RAW,
            ),
        )
        // The install location no longer blocks an adopted-private move, so an
        // unknown admin verdict must not be conflated with it either.
        assertTrue(
            MigrationPolicy.eligibleForSd(
                app(
                    isDeviceAdmin = null,
                    installLocation = MigrationPolicy.INSTALL_LOCATION_INTERNAL_ONLY,
                ),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.eligibleForSd(
                app(isDeviceAdmin = null, sourceUuid = OTHER_UUID_RAW),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.eligibleForSd(
                app(
                    packageName = MigrationPolicy.SHIZUKU_PACKAGE,
                    isDeviceAdmin = null,
                ),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.eligibleForSd(
                app(isDeviceAdmin = null, sourceUuid = CARD_UUID_RAW),
                CARD_UUID_RAW,
            ),
        )
    }

    @Test
    fun allocatorAndShizukuAreNeverMoved() {
        assertFalse(
            MigrationPolicy.eligibleForSd(
                app(packageName = MigrationPolicy.ALLOCATOR_PACKAGE),
                CARD_UUID_RAW,
            ),
        )
        assertFalse(
            MigrationPolicy.eligibleForSd(
                app(packageName = MigrationPolicy.SHIZUKU_PACKAGE),
                CARD_UUID_RAW,
            ),
        )
        assertTrue(MigrationPolicy.isProtectedPackage(MigrationPolicy.ALLOCATOR_PACKAGE))
        assertTrue(MigrationPolicy.isProtectedPackage(MigrationPolicy.SHIZUKU_PACKAGE))
        assertFalse(MigrationPolicy.isProtectedPackage("com.example.app"))
    }

    @Test
    fun anAppAlreadyOnTheCardIsNotAValidCardDestination() {
        val onCard = app(sourceUuid = CARD_UUID_RAW)
        assertFalse(MigrationPolicy.eligibleForSd(onCard, CARD_UUID_RAW))
        assertTrue(MigrationPolicy.eligibleForInternal(onCard, CARD_UUID_RAW))
    }

    @Test
    fun onlyInternalAppsMayBeOfferedTheCard() {
        val otherExternal = app(sourceUuid = OTHER_UUID_RAW)
        assertFalse(MigrationPolicy.eligibleForSd(otherExternal, CARD_UUID_RAW))
        assertFalse(MigrationPolicy.eligibleForInternal(otherExternal, CARD_UUID_RAW))
    }

    @Test
    fun cardEligibilityRequiresTheSelectedPrivateVolume() {
        assertFalse(MigrationPolicy.eligibleForSd(app(), null))
        assertFalse(MigrationPolicy.eligibleForInternal(app(sourceUuid = CARD_UUID_RAW), null))
    }

    // ------------------------------------------------------------------
    // Application request validation
    // ------------------------------------------------------------------

    @Test
    fun missingPackageIsRejected() {
        assertEquals(
            MigrationErrors.PACKAGE_MISSING,
            MigrationPolicy.validateAppMove(null, CARD_UUID_RAW, CARD_UUID_RAW),
        )
    }

    @Test
    fun protectedPackageIsRejectedEvenWhenItLivesOnTheCard() {
        val protectedApp = app(
            packageName = MigrationPolicy.ALLOCATOR_PACKAGE,
            sourceUuid = CARD_UUID_RAW,
        )
        assertEquals(
            MigrationErrors.PROTECTED_PACKAGE,
            MigrationPolicy.validateAppMove(protectedApp, "internal", CARD_UUID_RAW),
        )
    }

    @Test
    fun internalAppToSelectedCardIsAllowed() {
        assertNull(MigrationPolicy.validateAppMove(app(), CARD_UUID_RAW, CARD_UUID_RAW))
    }

    @Test
    fun internalAppToCanonicalFormOfTheSameCardIsAllowed() {
        assertNull(MigrationPolicy.validateAppMove(app(), CARD_UUID, CARD_UUID_RAW))
    }

    @Test
    fun alreadyInstalledOnTheCardIsReportedAsAlreadyAtTarget() {
        val onCard = app(sourceUuid = CARD_UUID_RAW)
        assertEquals(
            MigrationErrors.ALREADY_AT_TARGET,
            MigrationPolicy.validateAppMove(onCard, CARD_UUID_RAW, CARD_UUID_RAW),
        )
    }

    @Test
    fun appOnAnotherExternalVolumeCannotMoveToTheCard() {
        val otherExternal = app(sourceUuid = OTHER_UUID_RAW)
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validateAppMove(otherExternal, CARD_UUID_RAW, CARD_UUID_RAW),
        )
    }

    @Test
    fun appCannotTargetADifferentCard() {
        assertEquals(
            MigrationErrors.INVALID_TARGET,
            MigrationPolicy.validateAppMove(app(), OTHER_UUID_RAW, CARD_UUID_RAW),
        )
        assertEquals(
            MigrationErrors.INVALID_TARGET,
            MigrationPolicy.validateAppMove(app(), CARD_UUID_RAW, null),
        )
    }

    @Test
    fun cardAppCanMoveBackToInternal() {
        val onCard = app(sourceUuid = CARD_UUID_RAW)
        assertNull(MigrationPolicy.validateAppMove(onCard, "internal", CARD_UUID_RAW))
    }

    @Test
    fun moveBackAllowsAnAppOnTheCardWhateverItsInstallLocation() {
        val onCard = app(
            installLocation = MigrationPolicy.INSTALL_LOCATION_INTERNAL_ONLY,
            sourceUuid = CARD_UUID_RAW,
        )
        assertTrue(MigrationPolicy.eligibleForInternal(onCard, CARD_UUID_RAW))
        assertNull(MigrationPolicy.validateAppMove(onCard, "internal", CARD_UUID_RAW))
    }

    @Test
    fun internalAppCannotMoveToInternal() {
        assertEquals(
            MigrationErrors.ALREADY_AT_TARGET,
            MigrationPolicy.validateAppMove(app(), "internal", CARD_UUID_RAW),
        )
    }

    @Test
    fun externalAppCannotMoveToInternal() {
        val otherExternal = app(sourceUuid = OTHER_UUID_RAW)
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validateAppMove(otherExternal, "internal", CARD_UUID_RAW),
        )
    }

    @Test
    fun internalTargetWithoutASelectedCardIsRejected() {
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validateAppMove(app(sourceUuid = CARD_UUID_RAW), "internal", null),
        )
    }

    @Test
    fun nonEligibleAppOnTheCardCannotMoveBack() {
        val adminOnCard = app(sourceUuid = CARD_UUID_RAW, isDeviceAdmin = true)
        assertEquals(
            MigrationErrors.PACKAGE_NOT_ELIGIBLE,
            MigrationPolicy.validateAppMove(adminOnCard, "internal", CARD_UUID_RAW),
        )
    }

    @Test
    fun unspecifiedInstallLocationIsAcceptedForTheCard() {
        val unspecified = app(installLocation = MigrationPolicy.INSTALL_LOCATION_UNSPECIFIED)
        assertNull(MigrationPolicy.validateAppMove(unspecified, CARD_UUID_RAW, CARD_UUID_RAW))
    }

    // ------------------------------------------------------------------
    // Primary storage request validation
    // ------------------------------------------------------------------

    @Test
    fun primaryInternalToSelectedCardIsAllowed() {
        assertNull(MigrationPolicy.validatePrimaryMove(null, CARD_UUID_RAW, CARD_UUID_RAW))
        assertNull(MigrationPolicy.validatePrimaryMove("internal", CARD_UUID, CARD_UUID_RAW))
    }

    @Test
    fun primaryOnCardToInternalIsAllowed() {
        assertNull(MigrationPolicy.validatePrimaryMove(CARD_UUID_RAW, "internal", CARD_UUID_RAW))
    }

    @Test
    fun primaryAlreadyAtTheTargetIsReported() {
        assertEquals(
            MigrationErrors.ALREADY_AT_TARGET,
            MigrationPolicy.validatePrimaryMove(null, "internal", CARD_UUID_RAW),
        )
        assertEquals(
            MigrationErrors.ALREADY_AT_TARGET,
            MigrationPolicy.validatePrimaryMove(CARD_UUID_RAW, CARD_UUID, CARD_UUID_RAW),
        )
    }

    @Test
    fun primaryOnAnotherExternalVolumeIsRejectedInBothDirections() {
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validatePrimaryMove(OTHER_UUID_RAW, CARD_UUID_RAW, CARD_UUID_RAW),
        )
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validatePrimaryMove(OTHER_UUID_RAW, "internal", CARD_UUID_RAW),
        )
    }

    @Test
    fun primaryCannotTargetADifferentCard() {
        assertEquals(
            MigrationErrors.INVALID_TARGET,
            MigrationPolicy.validatePrimaryMove(null, OTHER_UUID_RAW, CARD_UUID_RAW),
        )
        assertEquals(
            MigrationErrors.INVALID_TARGET,
            MigrationPolicy.validatePrimaryMove(null, CARD_UUID_RAW, null),
        )
    }

    @Test
    fun primaryCannotMoveToInternalWithoutTheSelectedCard() {
        assertEquals(
            MigrationErrors.INVALID_SOURCE,
            MigrationPolicy.validatePrimaryMove(CARD_UUID_RAW, "internal", null),
        )
    }

    // ------------------------------------------------------------------
    // Destination verification after the platform reports -100
    // ------------------------------------------------------------------

    @Test
    fun destinationMatchesNormalisesInternalAndCanonicalForms() {
        assertTrue(MigrationPolicy.destinationMatches("internal", null))
        assertTrue(MigrationPolicy.destinationMatches("internal", "private"))
        assertTrue(MigrationPolicy.destinationMatches(CARD_UUID, CARD_UUID_RAW))
        assertTrue(MigrationPolicy.destinationMatches(CARD_UUID_RAW, CARD_UUID))
    }

    @Test
    fun destinationMismatchIsDetected() {
        assertFalse(MigrationPolicy.destinationMatches(CARD_UUID_RAW, null))
        assertFalse(MigrationPolicy.destinationMatches("internal", CARD_UUID_RAW))
        assertFalse(MigrationPolicy.destinationMatches(CARD_UUID_RAW, OTHER_UUID_RAW))
    }

    // ------------------------------------------------------------------
    // Physical card identity
    // ------------------------------------------------------------------

    @Test
    fun cardIdentityAcceptsTheSameCard() {
        val source = disk()
        assertTrue(CardIdentity.of(source).matches(source))
    }

    @Test
    fun cardIdentityRejectsMissingSwappedOrResizedCards() {
        val identity = CardIdentity.of(disk())
        assertFalse(identity.matches(disk(isSd = false)))
        assertFalse(identity.matches(disk(isAdoptable = false)))
        assertFalse(identity.matches(disk(id = "disk:179,1")))
        assertFalse(identity.matches(disk(sizeBytes = 32L * 1024L * 1024L * 1024L)))
        assertFalse(identity.matches(disk(cid = "0123456789abcdef0123456789abcdef")))
        assertFalse(identity.matches(disk(platformPath = MMC_PATH)))
    }

    @Test
    fun cardIdentityFallsBackToTheMmcPathWhenTheCidIsHidden() {
        val identity = CardIdentity.of(disk(cid = "", platformPath = MMC_PATH))
        assertTrue(identity.matches(disk(cid = "", platformPath = MMC_PATH)))
        assertFalse(identity.matches(disk(cid = "", platformPath = MMC_PATH_OTHER)))
        assertFalse(identity.matches(disk(cid = "", platformPath = null)))
    }
}
