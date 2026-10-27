package io.github.mydanyi.armadatf.domain

/**
 * Stable error codes produced by the pure migration policy and returned by the
 * privileged service. The strings are part of the JSON contract, so they must
 * not be renamed without updating the UI.
 */
object MigrationErrors {
    const val INVALID_REQUEST = "invalid_request"
    const val ALREADY_AT_TARGET = "already_at_target"
    const val CARD_MISSING = "card_missing"
    const val CARD_INELIGIBLE = "card_ineligible"
    const val CARD_CHANGED = "card_changed"
    const val CARD_LAYOUT_UNSUPPORTED = "card_layout_unsupported"
    const val TARGET_MISSING = "target_missing"
    const val TARGET_NOT_MOUNTED = "target_not_mounted"
    const val INVALID_TARGET = "invalid_target"
    const val INVALID_SOURCE = "invalid_source"
    const val SOURCE_CHANGED = "source_changed"
    const val PACKAGE_MISSING = "package_missing"
    const val PACKAGE_NOT_ELIGIBLE = "package_not_eligible"
    const val PROTECTED_PACKAGE = "protected_package"
    const val APP_QUERY_UNAVAILABLE = "app_query_unavailable"
}

/**
 * Normalisation of platform volume UUIDs.
 *
 * At the JSON boundary the device's internal storage is always the literal
 * [INTERNAL] sentinel. The native package/storage APIs instead expect `null` for
 * internal storage, so [toNative] converts back at the last moment.
 *
 * Adopted private partitions are usually reported without hyphens, while the
 * Linux side uses the canonical dashed form. Comparisons therefore always go
 * through [normalize], which also folds the platform's native private-internal
 * name (`private`) onto the same sentinel. `primary_physical` is deliberately
 * not folded: it names a different storage target, so it is preserved verbatim
 * and later rejected by the policy as an unsupported source.
 */
object VolumeUuids {

    /** JSON sentinel for the device's internal storage. */
    const val INTERNAL = "internal"

    /** Platform name of the internal private volume (`StorageManager.UUID_PRIVATE_INTERNAL`). */
    private const val NATIVE_PRIVATE_INTERNAL = "private"

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return INTERNAL
        return when (trimmed.lowercase()) {
            INTERNAL, NATIVE_PRIVATE_INTERNAL -> INTERNAL
            else -> Identifiers.normalizeUuid(trimmed) ?: trimmed.lowercase()
        }
    }

    fun isInternal(raw: String?): Boolean = normalize(raw) == INTERNAL

    /** Native argument for [raw]: `null` for the internal sentinel, otherwise the trimmed value. */
    fun toNative(raw: String?): String? = if (isInternal(raw)) null else raw?.trim()

    /** A migration target must be the internal sentinel or a real volume UUID. */
    fun isValidTarget(raw: String?): Boolean {
        val trimmed = raw?.trim() ?: return false
        if (trimmed.isEmpty()) return false
        if (trimmed.lowercase() == INTERNAL) return true
        return Identifiers.normalizeUuid(trimmed) != null
    }

    fun same(left: String?, right: String?): Boolean = normalize(left) == normalize(right)
}

/** The two kinds of platform-native migration this module supports. */
enum class MigrationKind(val jsonName: String) {
    /** `IPackageManager.movePrimaryStorage(String)`. */
    PRIMARY("primary"),

    /** `IPackageManager.movePackage(String, String)`. */
    APP("app");

    companion object {
        fun fromJson(raw: String?): MigrationKind? =
            entries.firstOrNull { it.jsonName == raw?.trim()?.lowercase() }
    }
}

/**
 * One validated migration request.
 *
 * [targetUuid] is the JSON form: [VolumeUuids.INTERNAL] or a volume UUID as the
 * platform reports it. The service replaces it with the platform's exact string
 * before storing it in the one-use token, so the native call never depends on
 * client-side formatting.
 */
data class MigrationRequest(
    val kind: MigrationKind,
    val diskId: String,
    val targetUuid: String,
    val packageName: String?,
) {
    init {
        require(diskId.isNotBlank()) { "disk_id must not be blank" }
        require(VolumeUuids.isValidTarget(targetUuid)) {
            "target_uuid must be '${VolumeUuids.INTERNAL}' or a volume UUID"
        }
        when (kind) {
            MigrationKind.APP -> require(!packageName.isNullOrBlank()) {
                "app migration requires package_name"
            }
            MigrationKind.PRIMARY -> require(packageName == null) {
                "primary migration must not name a package"
            }
        }
    }
}

/**
 * Pure, host-testable view of one installed application.
 *
 * [sourceUuid] is the native volume UUID, or `null` when the platform reports
 * internal storage. [isDeviceAdmin] is `null` when the platform's admin check
 * could not be read — an optional preflight read that the privileged caller may
 * be denied. `null` is not a rejection: only `true` is filtered here, and the
 * platform's own move performs the authoritative device-admin check.
 *
 * [isPreinstalled] marks OEM factory code: an application whose code lives in a
 * trusted read-only system-image partition, or whose identity is in the factory
 * preload catalog, even when its `FLAG_SYSTEM`/`FLAG_UPDATED_SYSTEM_APP` flags
 * are absent after an OEM update moved the code under `/data/app`.
 */
data class InstalledApp(
    val packageName: String,
    val isSystem: Boolean = false,
    val isUpdatedSystem: Boolean = false,
    val isPreinstalled: Boolean = false,
    val installLocation: Int = MigrationPolicy.INSTALL_LOCATION_AUTO,
    val sourceUuid: String? = null,
    val isDeviceAdmin: Boolean? = false,
    val sizeBytes: Long? = null,
)

/**
 * Pure rules for source/target selection, application eligibility and the
 * final destination check.
 *
 * The only supported directions are internal storage <-> the adopted private
 * volume of the selected card. An application may be moved back to internal
 * storage only when it currently lives on that card. The allocator itself and
 * Shizuku are never moved, and platform system, updated-system and OEM
 * factory-preinstalled applications never move at all.
 */
object MigrationPolicy {

    const val INSTALL_LOCATION_UNSPECIFIED = -1
    const val INSTALL_LOCATION_AUTO = 0
    const val INSTALL_LOCATION_INTERNAL_ONLY = 1
    const val INSTALL_LOCATION_PREFER_EXTERNAL = 2

    /** `ApplicationInfo.FLAG_SYSTEM`. */
    const val FLAG_SYSTEM = 1

    /** `ApplicationInfo.FLAG_UPDATED_SYSTEM_APP`. */
    const val FLAG_UPDATED_SYSTEM_APP = 1 shl 7

    /** This application; moving it would remove the running allocator. */
    const val ALLOCATOR_PACKAGE = "io.github.mydanyi.armadatf"

    /** Shizuku's manager package; it provides the privileged process. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun isProtectedPackage(packageName: String): Boolean =
        packageName == ALLOCATOR_PACKAGE || packageName == SHIZUKU_PACKAGE

    /**
     * True for platform system, updated-system and OEM factory-preinstalled
     * applications. These are never listed and never move in either direction.
     */
    fun isFactoryApp(app: InstalledApp): Boolean =
        app.isSystem || app.isUpdatedSystem || app.isPreinstalled

    fun installLocationName(installLocation: Int): String = when (installLocation) {
        INSTALL_LOCATION_AUTO -> "auto"
        INSTALL_LOCATION_INTERNAL_ONLY -> "internal_only"
        INSTALL_LOCATION_PREFER_EXTERNAL -> "prefer_external"
        INSTALL_LOCATION_UNSPECIFIED -> "unspecified"
        else -> "unknown"
    }

    /**
     * Listing rule for the read-only inventory.
     *
     * Every installed non-protected user application stays listed on whatever
     * volume it currently occupies, so a completed move can never make an app
     * disappear and the opposite direction stays reachable.
     *
     * Platform system, updated-system and OEM factory-preinstalled applications
     * never appear, not even when their code already sits on the selected card:
     * they are not migratable in either direction, so a move-back row would only
     * offer an operation the policy always rejects.
     *
     * [selectedPrivateUuid] mirrors the selection the inventory was built for;
     * visibility no longer depends on it but the parameter keeps the call sites
     * unchanged.
     */
    @Suppress("UNUSED_PARAMETER")
    fun visibleInInventory(app: InstalledApp, selectedPrivateUuid: String?): Boolean {
        if (isProtectedPackage(app.packageName)) return false
        return !isFactoryApp(app)
    }

    /** True when [app] currently lives on the selected adopted card. */
    fun isOnSelectedCard(app: InstalledApp, selectedPrivateUuid: String?): Boolean {
        val selected = selectedPrivateUuid?.trim().orEmpty()
        if (selected.isEmpty() || VolumeUuids.isInternal(selected)) return false
        return VolumeUuids.same(app.sourceUuid, selected)
    }

    /**
     * An application may be offered the selected adopted-private card when it
     * is a movable user app currently on internal storage without a known
     * device admin.
     *
     * The manifest `installLocation` is metadata only. Moving a package onto an
     * adopted private volume goes through the platform's `MovePackageHelper`,
     * which does not gate private-volume moves on `auto`/`preferExternal`;
     * requiring those values here hid a package after a successful move and
     * rejected moving it back.
     *
     * A `null` admin state means the optional preflight read was denied, not
     * that the app is an admin, so it does not block the offer. The platform's
     * move enforces the ban itself and fails a real admin with
     * `MOVE_FAILED_DEVICE_ADMIN`; known admins are still filtered here.
     */
    fun eligibleForSd(app: InstalledApp, selectedPrivateUuid: String?): Boolean {
        if (selectedPrivateUuid == null) return false
        if (isProtectedPackage(app.packageName)) return false
        if (isFactoryApp(app)) return false
        if (app.isDeviceAdmin == true) return false
        if (!VolumeUuids.isInternal(app.sourceUuid)) return false
        return !isOnSelectedCard(app, selectedPrivateUuid)
    }

    /**
     * Move-back is allowed for any user app that already lives on the selected
     * card, including one whose manifest install location never described the
     * card. Platform system and OEM factory-preinstalled applications are never
     * movable back, whatever volume currently holds their code.
     */
    fun eligibleForInternal(app: InstalledApp, selectedPrivateUuid: String?): Boolean {
        if (isProtectedPackage(app.packageName)) return false
        if (isFactoryApp(app)) return false
        if (app.isDeviceAdmin == true) return false
        return isOnSelectedCard(app, selectedPrivateUuid)
    }

    /**
     * Full application request validation.
     *
     * @return `null` when the request is allowed, otherwise one of
     *   [MigrationErrors].
     */
    fun validateAppMove(
        app: InstalledApp?,
        targetUuid: String,
        selectedPrivateUuid: String?,
    ): String? {
        if (app == null) return MigrationErrors.PACKAGE_MISSING
        if (isProtectedPackage(app.packageName)) return MigrationErrors.PROTECTED_PACKAGE
        if (isFactoryApp(app)) return MigrationErrors.PACKAGE_NOT_ELIGIBLE
        if (selectedPrivateUuid == null) {
            return if (VolumeUuids.isInternal(targetUuid)) {
                MigrationErrors.INVALID_SOURCE
            } else {
                MigrationErrors.INVALID_TARGET
            }
        }
        if (VolumeUuids.isInternal(targetUuid)) {
            if (VolumeUuids.isInternal(app.sourceUuid)) return MigrationErrors.ALREADY_AT_TARGET
            if (!isOnSelectedCard(app, selectedPrivateUuid)) return MigrationErrors.INVALID_SOURCE
            return if (eligibleForInternal(app, selectedPrivateUuid)) {
                null
            } else {
                MigrationErrors.PACKAGE_NOT_ELIGIBLE
            }
        }
        if (!VolumeUuids.same(targetUuid, selectedPrivateUuid)) {
            return MigrationErrors.INVALID_TARGET
        }
        if (VolumeUuids.same(app.sourceUuid, targetUuid)) return MigrationErrors.ALREADY_AT_TARGET
        if (!VolumeUuids.isInternal(app.sourceUuid)) return MigrationErrors.INVALID_SOURCE
        return if (eligibleForSd(app, selectedPrivateUuid)) {
            null
        } else {
            MigrationErrors.PACKAGE_NOT_ELIGIBLE
        }
    }

    /**
     * Full primary-storage request validation. [primaryUuid] is the platform
     * value, so `null`/internal sentinels are handled by [VolumeUuids].
     *
     * @return `null` when the request is allowed, otherwise one of
     *   [MigrationErrors].
     */
    fun validatePrimaryMove(
        primaryUuid: String?,
        targetUuid: String,
        selectedPrivateUuid: String?,
    ): String? {
        if (VolumeUuids.isInternal(targetUuid)) {
            if (VolumeUuids.isInternal(primaryUuid)) return MigrationErrors.ALREADY_AT_TARGET
            if (selectedPrivateUuid == null) return MigrationErrors.INVALID_SOURCE
            if (!VolumeUuids.same(primaryUuid, selectedPrivateUuid)) {
                return MigrationErrors.INVALID_SOURCE
            }
            return null
        }
        if (selectedPrivateUuid == null ||
            !VolumeUuids.same(targetUuid, selectedPrivateUuid)
        ) {
            return MigrationErrors.INVALID_TARGET
        }
        if (VolumeUuids.same(primaryUuid, targetUuid)) return MigrationErrors.ALREADY_AT_TARGET
        if (!VolumeUuids.isInternal(primaryUuid)) return MigrationErrors.INVALID_SOURCE
        return null
    }

    /**
     * The platform reports success with `-100`; the service only accepts that
     * after the live destination really carries the requested UUID.
     */
    fun destinationMatches(targetUuid: String, observedUuid: String?): Boolean =
        VolumeUuids.same(targetUuid, observedUuid)
}

/**
 * Identity of the physical card a migration was prepared for.
 *
 * Unlike [AllocationPlan] this carries no percentage: it only proves the same
 * SD card with the same size, hardware identity and platform path is still
 * present when the migration starts.
 */
data class CardIdentity(
    val diskId: String,
    val sizeBytes: Long,
    val cid: String,
    val platformPath: String?,
) {
    fun matches(disk: DiskSnapshot): Boolean {
        if (!disk.isSd || !disk.isAdoptable) return false
        if (disk.id != diskId || disk.sizeBytes != sizeBytes) return false
        val path = PlatformPaths.normalize(disk.platformPath)
        if (platformPath != path) return false
        return if (cid.isEmpty()) {
            PlatformPaths.isSdMmcPath(path)
        } else {
            Identifiers.isValidCid(disk.cid) && cid == Identifiers.normalizeCid(disk.cid)
        }
    }

    companion object {
        /**
         * Captures the card identity. An unreadable or malformed CID is stored
         * as empty (never invented), so the platform path carries the identity
         * exactly as [AllocationPolicy] does for the partition fallback.
         */
        fun of(disk: DiskSnapshot): CardIdentity = CardIdentity(
            diskId = disk.id,
            sizeBytes = disk.sizeBytes,
            cid = if (Identifiers.isValidCid(disk.cid)) {
                Identifiers.normalizeCid(disk.cid)
            } else {
                ""
            },
            platformPath = PlatformPaths.normalize(disk.platformPath),
        )
    }
}
