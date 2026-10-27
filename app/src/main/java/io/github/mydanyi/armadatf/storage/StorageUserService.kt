package io.github.mydanyi.armadatf.storage

import android.os.Binder
import android.os.Process
import io.github.mydanyi.armadatf.IStorageService
import io.github.mydanyi.armadatf.domain.AllocationMode
import io.github.mydanyi.armadatf.domain.AllocationPlan
import io.github.mydanyi.armadatf.domain.AllocationPolicy
import io.github.mydanyi.armadatf.domain.CardIdentity
import io.github.mydanyi.armadatf.domain.CardLayout
import io.github.mydanyi.armadatf.domain.CardLayoutPolicy
import io.github.mydanyi.armadatf.domain.DiskSnapshot
import io.github.mydanyi.armadatf.domain.Identifiers
import io.github.mydanyi.armadatf.domain.InstalledApp
import io.github.mydanyi.armadatf.domain.MigrationErrors
import io.github.mydanyi.armadatf.domain.MigrationKind
import io.github.mydanyi.armadatf.domain.MigrationPolicy
import io.github.mydanyi.armadatf.domain.MigrationRequest
import io.github.mydanyi.armadatf.domain.MoveStatus
import io.github.mydanyi.armadatf.domain.MoveStatusDecoder
import io.github.mydanyi.armadatf.domain.PlatformPaths
import io.github.mydanyi.armadatf.domain.VolumeUuids
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thrown during verification when the freshly created volume pair never became
 * observable before the deadline.
 */
private class VerificationTimeoutException(message: String) : IllegalStateException(message)

/**
 * A migration request was rejected by the pure policy before any native call.
 * [code] is the stable JSON error code.
 */
private class MigrationRejectedException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

/**
 * Binder implementation of [IStorageService] served from the Shizuku-started
 * privileged process.
 *
 * Every AIDL method returns a JSON string of the shape
 * `{"ok":true,"data":{...}}` or `{"ok":false,"error":"<code>","detail":"..."}`
 * so the binder surface stays stable. The service never uses a Context,
 * ContentResolver, broadcast receiver or any UI; the only platform access is
 * through [NativeStorage].
 *
 * Lifecycle: [prepare] captures an immutable [AllocationPlan] plus a one-use
 * token; [start] consumes that token, re-checks the plan against the live disk
 * and hands the actual partition + verification work to a single background
 * worker. State is only reported as `partitioned` after the freshly created
 * layout is verified on the very same card: the mixed public/private pair, the
 * single adopted private volume for all-Android, or the single public volume
 * for all-Linux.
 */
class StorageUserService : IStorageService.Stub() {

    private val nativeStorage = NativeStorage()
    private val nativeMigration = NativeMigration()
    private val lock = Any()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "armada-tf-partition").apply { isDaemon = true }
    }

    /** Bounded, timestamped operation log (oldest entry dropped past the cap). */
    private val logs = ArrayDeque<JSONObject>()

    private var state = STATE_IDLE
    private var phase: String? = null
    private var detail: String? = null
    private var handoff: JSONObject? = null

    private var pendingPlan: AllocationPlan? = null
    private var pendingToken: String? = null
    private var knownPrivateGuids: Set<String> = emptySet()
    private var knownPublicFsUuids: Set<String> = emptySet()

    /**
     * Immutable capture of this disk's public/private volumes taken at prepare
     * time. Only the volume-identity fallback compares it, immediately before
     * the native partition call.
     */
    private var expectedVolumes: List<NativeStorage.VolumeSnapshot> = emptyList()

    private var workerActive = false
    private var destroyPending = false

    /**
     * One prepared, not yet started native migration. The token is single-use:
     * [startMigration] clears this field before any native call.
     */
    private var pendingMigration: PendingMigration? = null

    /**
     * Retained move handles keyed by the platform move id. Keeping them lets the
     * UI re-query after a rotation/reconnect without ever repeating `start`.
     */
    private val migrationRecords = LinkedHashMap<Int, MigrationRecord>()

    /** Move id of the migration that is still running, if any. */
    private var migrationActiveMoveId: Int? = null

    // --- AIDL entry points ---------------------------------------------------

    override fun inspect(): String = try {
        val uid = Process.myUid()
        val data = JSONObject()
            .put("uid", uid)
            .put("identity", identityFor(uid))
        val disks = JSONArray()
        for (disk in nativeStorage.disks()) {
            if (!disk.isSd) continue
            disks.put(diskJson(disk))
        }
        data.put("disks", disks)
        ok(data)
    } catch (e: Exception) {
        fail(classify(e), e)
    }

    override fun prepare(diskId: String, linuxPercent: Int): String {
        val userId = userIdForCaller(Binder.getCallingUid())
        synchronized(lock) {
            if (state == STATE_RUNNING) {
                return failString(ERR_BUSY, "a partition operation is already running")
            }
            if (migrationActiveMoveId != null) {
                return failString(ERR_BUSY, "a migration is already running")
            }
            if (!AllocationPolicy.isValidPercent(linuxPercent)) {
                return failString(
                    ERR_INVALID_REQUEST,
                    "linuxPercent must be 0, ${AllocationPolicy.MIXED_MIN_PERCENT}.." +
                        "${AllocationPolicy.MIXED_MAX_PERCENT} or 100",
                )
            }
            val disks = try {
                nativeStorage.disks()
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
            val disk = disks.firstOrNull { it.id == diskId }
                ?: return failString(ERR_CARD_MISSING, "disk '$diskId' is not present")
            val plan = try {
                AllocationPolicy.plan(disk, linuxPercent)
            } catch (e: IllegalArgumentException) {
                return failString(ERR_CARD_MISSING, e.message)
            }
            // Only a side that is actually part of the layout must be non-empty:
            // the 0% endpoint has no Linux share and the 100% one has no Android
            // share, so only the allocated side is checked in each case.
            val (androidBytes, linuxBytes) = AllocationPolicy.capacity(plan)
            val mode = plan.mode
            if ((mode != AllocationMode.LINUX_ONLY && androidBytes <= 0L) ||
                (mode != AllocationMode.ANDROID_ONLY && linuxBytes <= 0L)
            ) {
                return failString(
                    ERR_ALLOCATION_FAILED,
                    "card is too small for a $linuxPercent% allocation",
                )
            }
            val volumes = try {
                nativeStorage.volumes()
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
            val blocked = try {
                moveBackBlocked(plan.diskId, volumes, userId)
            } catch (e: MigrationRejectedException) {
                return failString(e.code, e.message)
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
            if (blocked != null) {
                return failString(
                    blocked,
                    "move primary storage and installed apps off the card before repartitioning",
                )
            }
            val guids = privateGuidsOn(plan.diskId, volumes)
            val publicUuids = publicFsUuidsOn(plan.diskId, volumes)
            val capturedVolumes = volumesOn(plan.diskId, volumes)

            val token = UUID.randomUUID().toString()
            pendingPlan = plan
            pendingToken = token
            knownPrivateGuids = guids
            knownPublicFsUuids = publicUuids
            expectedVolumes = capturedVolumes
            state = STATE_IDLE
            phase = PHASE_PREPARED
            detail = null
            handoff = null
            log(
                LEVEL_INFO,
                "prepare",
                "disk=${plan.diskId} mode=${mode.handoffName} linux_ratio=$linuxPercent",
            )

            val data = JSONObject()
                .put("token", token)
                .put("disk_id", plan.diskId)
                .put("card_cid", plan.cardCid)
                .put("disk_bytes", plan.diskBytes)
                .put("linux_ratio", plan.linuxPercent)
                .put("android_bytes", androidBytes)
                .put("linux_bytes", linuxBytes)
                .put("erase_scope", ERASE_SCOPE)
            return ok(data)
        }
    }

    override fun start(token: String, confirmed: Boolean): String {
        val userId = userIdForCaller(Binder.getCallingUid())
        synchronized(lock) {
            if (!confirmed) {
                return failString(ERR_NOT_CONFIRMED, "start requires confirmed=true")
            }
            if (state == STATE_RUNNING) {
                return failString(ERR_BUSY, "a partition operation is already running")
            }
            if (migrationActiveMoveId != null) {
                return failString(ERR_BUSY, "a migration is already running")
            }
            val plan = pendingPlan
                ?: return failString(ERR_NO_PLAN, "prepare must succeed before start")
            val expected = pendingToken
                ?: return failString(ERR_NO_PLAN, "prepare must succeed before start")
            if (token != expected) {
                return failString(ERR_INVALID_TOKEN, "token does not match the prepared plan")
            }

            // A card that still holds primary storage or installed apps must not
            // be formatted. The refusal keeps the prepared token usable so the
            // caller can move everything back and retry the same confirmation.
            val guardVolumes = try {
                nativeStorage.volumes()
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
            val blocked = try {
                moveBackBlocked(plan.diskId, guardVolumes, userId)
            } catch (e: MigrationRejectedException) {
                return failString(e.code, e.message)
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
            if (blocked != null) {
                return failString(
                    blocked,
                    "move primary storage and installed apps off the card before repartitioning",
                )
            }

            // The token is single-use: consume it before doing anything else.
            pendingToken = null
            pendingPlan = null
            val guids = knownPrivateGuids
            val publicUuids = knownPublicFsUuids
            val capturedVolumes = expectedVolumes
            expectedVolumes = emptyList()

            state = STATE_RUNNING
            phase = PHASE_RECHECKING
            detail = null
            handoff = null
            log(LEVEL_INFO, "start", "disk=${plan.diskId}")

            val disk = try {
                nativeStorage.disks().firstOrNull { it.id == plan.diskId }
            } catch (e: Exception) {
                val code = classify(e)
                state = STATE_FAILED
                phase = PHASE_FAILED
                detail = code
                log(LEVEL_WARN, "start", code)
                return fail(code, e)
            }
            if (disk == null || !AllocationPolicy.matches(plan, disk)) {
                state = STATE_FAILED
                phase = PHASE_FAILED
                detail = ERR_CARD_CHANGED
                log(LEVEL_WARN, "start", "card changed before partition")
                return failString(ERR_CARD_CHANGED, "card no longer matches the prepared plan")
            }

            workerActive = true
            worker.execute { runPartition(plan, guids, publicUuids, capturedVolumes) }

            val data = JSONObject()
                .put("status", state)
                .put("phase", phase)
            return ok(data)
        }
    }

    override fun status(): String {
        synchronized(lock) {
            val data = JSONObject()
                .put("state", state)
                .put("phase", phase ?: JSONObject.NULL)
                .put("detail", detail ?: JSONObject.NULL)
            val logsArray = JSONArray()
            for (entry in logs) logsArray.put(JSONObject(entry.toString()))
            data.put("logs", logsArray)
            val current = handoff
            if (state == STATE_PARTITIONED && current != null) {
                data.put("handoff", JSONObject(current.toString()))
            }
            return ok(data)
        }
    }

    override fun migrationInfo(diskId: String): String {
        val userId = userIdForCaller(Binder.getCallingUid())
        synchronized(lock) {
            try {
                val disk = nativeStorage.disks().firstOrNull { it.id == diskId }
                    ?: return failString(
                        MigrationErrors.CARD_MISSING,
                        "disk '$diskId' is not present",
                    )
                if (!disk.isSd) {
                    return failString(
                        MigrationErrors.CARD_INELIGIBLE,
                        "disk '$diskId' is not an SD card",
                    )
                }
                val volumes = nativeStorage.volumes()
                val card = cardVolumes(diskId, volumes)
                val selected = selectedPrivateUuid(card)
                val primaryRaw = nativeMigration.primaryStorageUuid(userId)
                val primary = VolumeUuids.normalize(primaryRaw)
                val privateCanonical = card.privateVolume?.let {
                    Identifiers.normalizeUuid(it.fsUuid)
                }
                val primaryOnThisDisk =
                    !VolumeUuids.isInternal(primary) && primary == privateCanonical

                val volumesArray = JSONArray()
                for (volume in volumes.filter {
                    it.diskId == diskId && (it.type == TYPE_PUBLIC || it.type == TYPE_PRIVATE)
                }) {
                    volumesArray.put(volumeJson(volume))
                }

                val appsArray = JSONArray()
                var appsError: String? = null
                var appsTruncated = false
                var anyAppOnCard = false
                val cardReady = isUsablePrivateTarget(card.privateVolume)
                if (selected != null) {
                    try {
                        val installed = nativeMigration.installedApps(userId)
                            .sortedBy { it.packageName }
                        // Visibility is decided by the pure policy alone: an
                        // installed user app stays listed on whatever volume it
                        // occupies, so a completed move never hides it. The
                        // admin read stays per row below; it is not needed to
                        // decide visibility.
                        val visible = installed.filter {
                            MigrationPolicy.visibleInInventory(
                                it.toInstalledApp(isDeviceAdmin = null),
                                selected,
                            )
                        }
                        appsTruncated = visible.size > MAX_MIGRATION_APPS
                        // Full detection over every installed app, so a card
                        // that still holds any app is never reported as empty.
                        anyAppOnCard = installed.any { onSelectedCard(it, selected) }
                        for (snapshot in visible.take(MAX_MIGRATION_APPS)) {
                            val app = snapshot.toInstalledApp(
                                nativeMigration.isDeviceAdmin(snapshot.packageName, userId),
                            )
                            val onCard = MigrationPolicy.isOnSelectedCard(app, selected)
                            appsArray.put(appJson(app, selected, onCard, cardReady))
                        }
                    } catch (e: Exception) {
                        appsError = classify(e)
                        log(LEVEL_WARN, "migration_info_apps", appsError)
                    }
                }

                val data = JSONObject()
                    .put("user_id", userId)
                    .put("disk_id", disk.id)
                    .put("disk_bytes", disk.sizeBytes)
                    .put("card_cid", disk.cid)
                    .put("is_sd", disk.isSd)
                    .put("is_adoptable", disk.isAdoptable)
                    .put("eligible_card", isEligible(disk))
                    .put("layout", card.layout.jsonName)
                    .put("primary_uuid", primary)
                    .put("primary_on_this_disk", primaryOnThisDisk)
                    .put("selected_private_uuid", selected ?: JSONObject.NULL)
                    .put("destination_available", isUsablePrivateTarget(card.privateVolume))
                    .put(
                        "move_back_required",
                        if (appsError != null) {
                            JSONObject.NULL
                        } else {
                            primaryOnThisDisk || anyAppOnCard
                        },
                    )
                    .put("volumes", volumesArray)
                    .put("apps", appsArray)
                    .put("apps_truncated", appsTruncated)
                if (appsError != null) data.put("apps_error", appsError)
                val active = migrationRecords.values.lastOrNull()
                if (active != null) data.put("active_migration", recordJson(active))
                return ok(data)
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
        }
    }

    override fun prepareMigration(requestJson: String): String {
        val userId = userIdForCaller(Binder.getCallingUid())
        synchronized(lock) {
            if (workerActive || state == STATE_RUNNING) {
                return failString(ERR_BUSY, "a partition operation is already running")
            }
            if (migrationActiveMoveId != null) {
                return failString(ERR_BUSY, "a migration is already running")
            }
            val request = try {
                parseMigrationRequest(requestJson)
            } catch (e: Exception) {
                return failString(
                    MigrationErrors.INVALID_REQUEST,
                    e.message ?: "invalid migration request",
                )
            }
            try {
                val disk = nativeStorage.disks().firstOrNull { it.id == request.diskId }
                    ?: throw MigrationRejectedException(
                        MigrationErrors.CARD_MISSING,
                        "disk '${request.diskId}' is not present",
                    )
                if (!disk.isSd || !isEligible(disk)) {
                    throw MigrationRejectedException(
                        MigrationErrors.CARD_INELIGIBLE,
                        "disk '${disk.id}' is not an eligible SD card",
                    )
                }
                val volumes = nativeStorage.volumes()
                val card = cardVolumes(request.diskId, volumes)
                if (card.layout == CardLayout.UNSUPPORTED) {
                    throw MigrationRejectedException(
                        MigrationErrors.CARD_LAYOUT_UNSUPPORTED,
                        "disk '${disk.id}' reports more than one public/private volume",
                    )
                }
                val selected = selectedPrivateUuid(card)
                val selectedVolume = card.privateVolume
                val targetInternal = VolumeUuids.isInternal(request.targetUuid)

                val source = when (request.kind) {
                    MigrationKind.PRIMARY -> {
                        val primaryRaw = nativeMigration.primaryStorageUuid(userId)
                        val code = MigrationPolicy.validatePrimaryMove(
                            primaryRaw,
                            request.targetUuid,
                            selected,
                        )
                        if (code != null) {
                            throw MigrationRejectedException(code, rejectionDetail(code))
                        }
                        VolumeUuids.normalize(primaryRaw)
                    }
                    MigrationKind.APP -> {
                        val packageName = requireNotNull(request.packageName)
                        val app = installedApp(packageName, userId)
                            ?: throw MigrationRejectedException(
                                MigrationErrors.PACKAGE_MISSING,
                                "package '$packageName' is not installed for user $userId",
                            )
                        val code = MigrationPolicy.validateAppMove(
                            app,
                            request.targetUuid,
                            selected,
                        )
                        if (code != null) {
                            throw MigrationRejectedException(code, rejectionDetail(code))
                        }
                        VolumeUuids.normalize(app.sourceUuid)
                    }
                }

                if (targetInternal) {
                    if (!isUsablePrivateTarget(selectedVolume)) {
                        throw MigrationRejectedException(
                            MigrationErrors.INVALID_SOURCE,
                            "the selected card's private volume is not mounted and writable",
                        )
                    }
                } else {
                    if (selectedVolume == null) {
                        throw MigrationRejectedException(
                            MigrationErrors.TARGET_MISSING,
                            "disk '${disk.id}' has no adopted private volume",
                        )
                    }
                    if (!isUsablePrivateTarget(selectedVolume)) {
                        throw MigrationRejectedException(
                            MigrationErrors.TARGET_NOT_MOUNTED,
                            "the selected private volume is not mounted and writable",
                        )
                    }
                }

                // The native APIs want the volume UUID exactly as the platform
                // reports it; the JSON keeps that same string so a round-trip
                // through the UI cannot change the argument.
                val targetJson = if (targetInternal) {
                    VolumeUuids.INTERNAL
                } else {
                    requireNotNull(selectedVolume?.fsUuid).trim()
                }
                val token = UUID.randomUUID().toString()
                pendingMigration = PendingMigration(
                    request = request.copy(targetUuid = targetJson),
                    token = token,
                    userId = userId,
                    sourceUuid = source,
                    selectedPrivateUuid = selected,
                    targetNativeUuid = VolumeUuids.toNative(targetJson),
                    cardIdentity = CardIdentity.of(disk),
                    expectedVolumes = volumesOn(request.diskId, volumes),
                )
                log(
                    LEVEL_INFO,
                    "prepare_migration",
                    "kind=${request.kind.jsonName} disk=${disk.id} target=$targetJson " +
                        "package=${request.packageName ?: "-"}",
                )
                return ok(
                    JSONObject()
                        .put("token", token)
                        .put("kind", request.kind.jsonName)
                        .put("disk_id", disk.id)
                        .put("target_uuid", targetJson)
                        .put("package_name", request.packageName ?: JSONObject.NULL)
                        .put("source_uuid", source)
                        .put("selected_private_uuid", selected ?: JSONObject.NULL)
                        .put("layout", card.layout.jsonName)
                        .put("user_id", userId),
                )
            } catch (e: MigrationRejectedException) {
                log(LEVEL_WARN, "prepare_migration", e.code)
                return failString(e.code, e.message)
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
        }
    }

    override fun startMigration(token: String, confirmed: Boolean): String {
        val userId = userIdForCaller(Binder.getCallingUid())
        synchronized(lock) {
            if (!confirmed) {
                return failString(ERR_NOT_CONFIRMED, "start requires confirmed=true")
            }
            if (workerActive || state == STATE_RUNNING) {
                return failString(ERR_BUSY, "a partition operation is already running")
            }
            if (migrationActiveMoveId != null) {
                return failString(ERR_BUSY, "a migration is already running")
            }
            val pending = pendingMigration
                ?: return failString(
                    ERR_NO_MIGRATION_PLAN,
                    "prepareMigration must succeed before startMigration",
                )
            if (token != pending.token) {
                return failString(ERR_INVALID_TOKEN, "token does not match the prepared migration")
            }
            if (userId != pending.userId) {
                return failString(
                    MigrationErrors.INVALID_REQUEST,
                    "the migration was prepared by a different user",
                )
            }

            // Single use: consume before any native call so a retry can never
            // start the same move twice.
            pendingMigration = null
            try {
                val disk = nativeStorage.disks().firstOrNull { it.id == pending.request.diskId }
                    ?: throw MigrationRejectedException(
                        MigrationErrors.CARD_MISSING,
                        "disk '${pending.request.diskId}' is no longer present",
                    )
                if (!disk.isSd || !isEligible(disk)) {
                    throw MigrationRejectedException(
                        MigrationErrors.CARD_INELIGIBLE,
                        "disk '${disk.id}' is no longer an eligible SD card",
                    )
                }
                if (!pending.cardIdentity.matches(disk)) {
                    throw MigrationRejectedException(
                        MigrationErrors.CARD_CHANGED,
                        "the physical card no longer matches the prepared request",
                    )
                }
                val volumes = nativeStorage.volumes()
                if (!VolumeIdentity.matches(
                        pending.expectedVolumes,
                        volumes,
                        pending.request.diskId,
                    )
                ) {
                    throw MigrationRejectedException(
                        MigrationErrors.CARD_CHANGED,
                        "the card's volume identifiers changed since prepare",
                    )
                }
                val card = cardVolumes(pending.request.diskId, volumes)
                val selected = selectedPrivateUuid(card)
                if (selected == null ||
                    !VolumeUuids.same(selected, pending.selectedPrivateUuid)
                ) {
                    throw MigrationRejectedException(
                        MigrationErrors.CARD_CHANGED,
                        "the card's adopted private volume changed since prepare",
                    )
                }
                val targetInternal = VolumeUuids.isInternal(pending.request.targetUuid)
                if (!isUsablePrivateTarget(card.privateVolume)) {
                    throw MigrationRejectedException(
                        if (targetInternal) {
                            MigrationErrors.INVALID_SOURCE
                        } else {
                            MigrationErrors.TARGET_NOT_MOUNTED
                        },
                        if (targetInternal) {
                            "the card's private volume is no longer mounted"
                        } else {
                            "the selected private volume is no longer mounted"
                        },
                    )
                }
                if (!targetInternal && pending.targetNativeUuid == null) {
                    throw MigrationRejectedException(
                        MigrationErrors.TARGET_MISSING,
                        "the prepared request has no SD target",
                    )
                }

                when (pending.request.kind) {
                    MigrationKind.PRIMARY -> {
                        val current = nativeMigration.primaryStorageUuid(userId)
                        if (VolumeUuids.normalize(current) != pending.sourceUuid) {
                            throw MigrationRejectedException(
                                MigrationErrors.SOURCE_CHANGED,
                                "primary storage changed since prepare",
                            )
                        }
                    }
                    MigrationKind.APP -> {
                        val packageName = requireNotNull(pending.request.packageName)
                        val app = installedApp(packageName, userId)
                            ?: throw MigrationRejectedException(
                                MigrationErrors.PACKAGE_MISSING,
                                "package '$packageName' is no longer installed",
                            )
                        if (VolumeUuids.normalize(app.sourceUuid) != pending.sourceUuid) {
                            throw MigrationRejectedException(
                                MigrationErrors.SOURCE_CHANGED,
                                "the application moved since prepare",
                            )
                        }
                        val code = MigrationPolicy.validateAppMove(
                            app,
                            pending.request.targetUuid,
                            pending.selectedPrivateUuid,
                        )
                        if (code != null) {
                            throw MigrationRejectedException(code, rejectionDetail(code))
                        }
                    }
                }

                val moveId = when (pending.request.kind) {
                    MigrationKind.PRIMARY ->
                        nativeMigration.movePrimaryStorage(pending.targetNativeUuid)
                    MigrationKind.APP -> nativeMigration.movePackage(
                        requireNotNull(pending.request.packageName),
                        pending.targetNativeUuid,
                    )
                }
                if (moveId < 0) {
                    throw MigrationRejectedException(
                        ERR_MOVE_START_FAILED,
                        "the platform rejected the move (status $moveId)",
                    )
                }

                val record = MigrationRecord(
                    moveId = moveId,
                    request = pending.request,
                    userId = pending.userId,
                    state = STATE_MIGRATION_RUNNING,
                    progress = 0,
                    nativeStatus = null,
                    error = null,
                    detail = null,
                )
                migrationRecords[moveId] = record
                migrationActiveMoveId = moveId
                trimMigrationRecords()
                log(
                    LEVEL_INFO,
                    "start_migration",
                    "kind=${pending.request.kind.jsonName} disk=${pending.request.diskId} " +
                        "target=${pending.request.targetUuid} move=$moveId",
                )
                return ok(recordJson(record))
            } catch (e: MigrationRejectedException) {
                log(LEVEL_WARN, "start_migration", e.code)
                return failString(e.code, e.message)
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
        }
    }

    override fun migrationStatus(moveId: Int): String {
        synchronized(lock) {
            val nativeStatus = try {
                nativeMigration.moveStatus(moveId)
            } catch (e: Exception) {
                return fail(classify(e), e)
            }
            val record = migrationRecords[moveId]
            val decoded = MoveStatusDecoder.decode(nativeStatus)
            if (record == null) {
                return when (decoded) {
                    is MoveStatus.InProgress -> {
                        // A resumed move has no retained request in this process,
                        // but it is still running natively: keep its id so a new
                        // startMigration stays blocked instead of reallocating
                        // under the active move.
                        if (migrationActiveMoveId == null) migrationActiveMoveId = moveId
                        ok(
                            statusJson(
                                moveId = moveId,
                                state = STATE_MIGRATION_RUNNING,
                                progress = decoded.percent,
                                nativeStatus = nativeStatus,
                                kind = null,
                                targetUuid = null,
                                packageName = null,
                                error = null,
                                detail = null,
                            ),
                        )
                    }
                    is MoveStatus.Complete -> failString(
                        ERR_DESTINATION_UNVERIFIED,
                        "no retained migration request for move $moveId",
                    )
                    is MoveStatus.Failed -> ok(
                        statusJson(
                            moveId = moveId,
                            state = STATE_MIGRATION_FAILED,
                            progress = null,
                            nativeStatus = nativeStatus,
                            kind = null,
                            targetUuid = null,
                            packageName = null,
                            error = decoded.code,
                            detail = "platform move failed with status ${decoded.nativeCode}",
                        ),
                    )
                }
            }
            return when (decoded) {
                is MoveStatus.InProgress -> {
                    val updated = record.copy(
                        state = STATE_MIGRATION_RUNNING,
                        progress = decoded.percent,
                        nativeStatus = nativeStatus,
                        error = null,
                        detail = null,
                    )
                    migrationRecords[moveId] = updated
                    if (migrationActiveMoveId == null || migrationActiveMoveId == moveId) {
                        migrationActiveMoveId = moveId
                    }
                    ok(recordJson(updated))
                }
                is MoveStatus.Complete -> completeMigration(record, nativeStatus)
                is MoveStatus.Failed -> {
                    val updated = record.copy(
                        state = STATE_MIGRATION_FAILED,
                        progress = null,
                        nativeStatus = nativeStatus,
                        error = decoded.code,
                        detail = "platform move failed with status ${decoded.nativeCode}",
                    )
                    migrationRecords[moveId] = updated
                    if (migrationActiveMoveId == moveId) migrationActiveMoveId = null
                    ok(recordJson(updated))
                }
            }
        }
    }


    override fun destroy() {
        synchronized(lock) {
            log(LEVEL_INFO, "destroy", "worker_active=$workerActive")
            if (workerActive) {
                // Never kill a partition midway; exit once the worker finishes.
                destroyPending = true
                return
            }
            pendingPlan = null
            pendingToken = null
            knownPrivateGuids = emptySet()
            knownPublicFsUuids = emptySet()
            expectedVolumes = emptyList()
            pendingMigration = null
            migrationRecords.clear()
            migrationActiveMoveId = null
            handoff = null
            state = STATE_IDLE
            phase = null
            detail = null
            worker.shutdown()
        }
    }

    // --- worker --------------------------------------------------------------

    private fun runPartition(
        plan: AllocationPlan,
        knownGuids: Set<String>,
        knownPublicUuids: Set<String>,
        capturedVolumes: List<NativeStorage.VolumeSnapshot>,
    ) {
        try {
            synchronized(lock) { phase = PHASE_PARTITIONING }
            log(
                LEVEL_INFO,
                "partition",
                "disk=${plan.diskId} mode=${plan.mode.handoffName} " +
                    "linux_ratio=${plan.linuxPercent}",
            )
            nativeStorage.partition(plan, capturedVolumes)

            synchronized(lock) { phase = PHASE_VERIFYING }
            val layout = verifyNewLayout(plan, knownGuids, knownPublicUuids)

            synchronized(lock) {
                handoff = buildHandoff(plan, layout)
                state = STATE_PARTITIONED
                phase = if (plan.mode == AllocationMode.ANDROID_ONLY) {
                    PHASE_ANDROID_READY
                } else {
                    PHASE_PARTITIONED
                }
                detail = null
                log(
                    LEVEL_INFO,
                    "partitioned",
                    "verified ${plan.mode.handoffName} layout on disk=${plan.diskId}",
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(lock) {
                state = STATE_FAILED
                phase = PHASE_FAILED
                detail = ERR_INTERRUPTED
                handoff = null
                log(LEVEL_WARN, "failed", ERR_INTERRUPTED)
            }
        } catch (e: Exception) {
            val code = classify(e)
            synchronized(lock) {
                state = STATE_FAILED
                phase = PHASE_FAILED
                detail = code
                handoff = null
                log(LEVEL_WARN, "failed", code)
            }
        } finally {
            synchronized(lock) {
                workerActive = false
                if (destroyPending) {
                    destroyPending = false
                    worker.shutdown()
                }
            }
        }
    }

    /**
     * Polls the platform for up to [VERIFY_TIMEOUT_MS], about once per second,
     * until [VolumeLayoutVerifier] accepts the freshly created layout on the
     * very same card.
     *
     * The predicate is mode-specific: mixed needs exactly one mounted public
     * plus one mounted private volume and a brand-new canonical private GUID/FS
     * UUID; all-Android needs exactly one private volume, no public volume and a
     * brand-new GUID; all-Linux needs exactly one public volume, no private
     * volume and a brand-new non-empty public FS UUID.
     *
     * The card identity (id/size/CID/flags) is re-checked on every poll via
     * [AllocationPolicy.matches] so a swap during verification is fatal.
     */
    private fun verifyNewLayout(
        plan: AllocationPlan,
        knownGuids: Set<String>,
        knownPublicUuids: Set<String>,
    ): VolumeLayoutCheck.Verified {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        var lastReason = "volume_layout_not_ready"
        while (true) {
            val disk = nativeStorage.disks().firstOrNull { it.id == plan.diskId }
            if (disk == null || !AllocationPolicy.matches(plan, disk)) {
                throw CardChangedException("card changed during verification")
            }
            val volumes = nativeStorage.volumes().filter { it.diskId == plan.diskId }
            when (
                val check = VolumeLayoutVerifier.check(
                    plan.mode,
                    volumes,
                    knownGuids,
                    knownPublicUuids,
                )
            ) {
                is VolumeLayoutCheck.Verified -> return check
                is VolumeLayoutCheck.Waiting -> lastReason = check.reason
            }

            if (System.currentTimeMillis() >= deadline) {
                throw VerificationTimeoutException(lastReason)
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Immutable copy of the public/private volumes currently on one disk. Taken
     * at prepare time and compared only by the volume-identity fallback.
     */
    private fun volumesOn(
        diskId: String,
        volumes: List<NativeStorage.VolumeSnapshot>,
    ): List<NativeStorage.VolumeSnapshot> = volumes
        .filter { it.diskId == diskId && (it.type == TYPE_PUBLIC || it.type == TYPE_PRIVATE) }
        .toList()

    private fun privateGuidsOn(
        diskId: String,
        volumes: List<NativeStorage.VolumeSnapshot>,
    ): Set<String> = volumes
        .filter { it.diskId == diskId && it.type == TYPE_PRIVATE }
        .mapNotNull { it.partGuid }
        .toSet()

    private fun publicFsUuidsOn(
        diskId: String,
        volumes: List<NativeStorage.VolumeSnapshot>,
    ): Set<String> = volumes
        .filter { it.diskId == diskId && it.type == TYPE_PUBLIC }
        .mapNotNull { it.fsUuid?.trim()?.lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun diskJson(disk: DiskSnapshot): JSONObject {
        val eligible = isEligible(disk)
        return JSONObject()
            .put("id", disk.id)
            .put("size_bytes", disk.sizeBytes)
            // Empty string when the firmware hides the CID; the UI shows no CID
            // then, while the plan uses the volume identity fallback.
            .put("card_cid", disk.cid)
            .put("is_sd", disk.isSd)
            .put("is_adoptable", disk.isAdoptable)
            .put("eligible", eligible)
            .put("reason", eligibleReason(disk, eligible))
    }

    /**
     * A card is eligible with either a valid hardware CID or the validated MMC
     * platform path that replaces it when the firmware hides the CID.
     */
    private fun isEligible(disk: DiskSnapshot): Boolean =
        disk.isAdoptable &&
            disk.sizeBytes > 0L &&
            (Identifiers.isValidCid(disk.cid) || PlatformPaths.isSdMmcPath(disk.platformPath))

    private fun eligibleReason(disk: DiskSnapshot, eligible: Boolean): Any = when {
        eligible -> JSONObject.NULL
        !disk.isAdoptable -> "not_adoptable"
        disk.sizeBytes <= 0L -> "zero_size"
        !Identifiers.isValidCid(disk.cid) &&
            !PlatformPaths.isSdMmcPath(disk.platformPath) -> "cid_unreadable"
        else -> "unknown"
    }

    /**
     * Builds the payload for the verified layout.
     *
     * Android-only never produces a Linux handoff, so the card stays
     * `partitioned` with `phase = "android_ready"` and `handoff = null`.
     *
     * A readable CID keeps the legacy payloads untouched: schema 2 for
     * Linux-only with explicit null private identifiers, schema 1 for mixed
     * with exactly its existing field set.
     *
     * Without a CID the payload is schema 3: `identity_mode = "volume"`,
     * `allocation_mode` of the real mode, and `card_cid` explicitly null. The
     * other fields (public identifiers, capacity, ratio and the mode-specific
     * private fields) stay exactly as in the legacy schemas. A CID is never
     * fabricated and no legacy payload ever carries a null CID.
     */
    private fun buildHandoff(
        plan: AllocationPlan,
        layout: VolumeLayoutCheck.Verified,
    ): JSONObject? {
        val mode = plan.mode
        if (mode == AllocationMode.ANDROID_ONLY) return null
        val volumeIdentity = plan.cardCid.isEmpty()
        val publicVolume = requireNotNull(layout.publicVolume) {
            "verified ${mode.handoffName} layout has no public volume"
        }
        val schema = when {
            volumeIdentity -> HANDOFF_SCHEMA_VERSION_VOLUME
            mode == AllocationMode.LINUX_ONLY -> HANDOFF_SCHEMA_VERSION_LINUX_ONLY
            else -> HANDOFF_SCHEMA_VERSION
        }
        val handoff = JSONObject()
            .put("magic", HANDOFF_MAGIC)
            .put("schema_version", schema)
        if (volumeIdentity) {
            handoff
                .put("identity_mode", IDENTITY_MODE_VOLUME)
                .put("allocation_mode", mode.handoffName)
        } else if (mode == AllocationMode.LINUX_ONLY) {
            handoff.put("allocation_mode", ALLOCATION_MODE_LINUX_ONLY)
        }
        handoff
            .put("card_cid", if (volumeIdentity) JSONObject.NULL else plan.cardCid)
            .put("disk_bytes", plan.diskBytes)
            .put("linux_ratio", plan.linuxPercent)
            .put("public_fs_uuid", publicVolume.fsUuid ?: JSONObject.NULL)
            .put("public_partition_guid", publicVolume.partGuid ?: JSONObject.NULL)
        if (mode == AllocationMode.LINUX_ONLY) {
            handoff
                .put("private_partition_guid", JSONObject.NULL)
                .put("private_fs_uuid", JSONObject.NULL)
        } else {
            val privateVolume = requireNotNull(layout.privateVolume) {
                "verified mixed layout has no private volume"
            }
            handoff
                .put("private_partition_guid", privateVolume.partGuid ?: JSONObject.NULL)
                .put(
                    "private_fs_uuid",
                    Identifiers.normalizeUuid(privateVolume.fsUuid) ?: JSONObject.NULL,
                )
        }
        return handoff
    }

    // --- migration helpers ---------------------------------------------------

    /** Immutable request captured by [prepareMigration] for the one-use token. */
    private data class PendingMigration(
        val request: MigrationRequest,
        val token: String,
        val userId: Int,
        /** Normalised source UUID (internal sentinel or canonical volume UUID). */
        val sourceUuid: String,
        /** Platform fsUuid of the selected card's private volume, if any. */
        val selectedPrivateUuid: String?,
        /** Exact platform argument for the native move; `null` for internal. */
        val targetNativeUuid: String?,
        val cardIdentity: CardIdentity,
        val expectedVolumes: List<NativeStorage.VolumeSnapshot>,
    )

    /** Retained platform move handle and the request needed to verify it. */
    private data class MigrationRecord(
        val moveId: Int,
        val request: MigrationRequest,
        val userId: Int,
        val state: String,
        val progress: Int?,
        val nativeStatus: Int?,
        val error: String?,
        val detail: String?,
    )

    /** The public/private volumes of one disk and their inferred layout. */
    private data class CardVolumes(
        val privateVolume: NativeStorage.VolumeSnapshot?,
        val layout: CardLayout,
    )

    private fun cardVolumes(
        diskId: String,
        volumes: List<NativeStorage.VolumeSnapshot>,
    ): CardVolumes {
        val onDisk = volumes.filter {
            it.diskId == diskId && (it.type == TYPE_PUBLIC || it.type == TYPE_PRIVATE)
        }
        val publics = onDisk.filter { it.type == TYPE_PUBLIC }
        val privates = onDisk.filter { it.type == TYPE_PRIVATE }
        return CardVolumes(
            privateVolume = privates.singleOrNull(),
            layout = CardLayoutPolicy.infer(publics.size, privates.size),
        )
    }

    /** Platform fsUuid of the single adopted private volume, when usable. */
    private fun selectedPrivateUuid(card: CardVolumes): String? =
        card.privateVolume?.fsUuid?.trim()?.takeIf { Identifiers.normalizeUuid(it) != null }

    /** A private volume is a migration target only while mounted/writable. */
    private fun isUsablePrivateTarget(volume: NativeStorage.VolumeSnapshot?): Boolean =
        volume != null &&
            volume.type == TYPE_PRIVATE &&
            volume.state == STATE_MOUNTED &&
            !volume.path.isNullOrEmpty() &&
            Identifiers.normalizeUuid(volume.fsUuid) != null

    private fun volumeJson(volume: NativeStorage.VolumeSnapshot): JSONObject {
        val capacity = nativeMigration.capacity(volume.path)
        return JSONObject()
            .put("id", volume.id)
            .put("disk_id", volume.diskId ?: JSONObject.NULL)
            .put("type", if (volume.type == TYPE_PRIVATE) "private" else "public")
            .put("state", stateName(volume.state))
            .put("mounted", volume.state == STATE_MOUNTED)
            .put("mounted_writable", volume.state == STATE_MOUNTED)
            .put("fs_uuid", volume.fsUuid ?: JSONObject.NULL)
            .put("part_guid", volume.partGuid ?: JSONObject.NULL)
            .put("capacity_bytes", capacity.totalBytes ?: JSONObject.NULL)
            .put("available_bytes", capacity.availableBytes ?: JSONObject.NULL)
            .put("path", volume.path ?: JSONObject.NULL)
    }

    private fun stateName(state: Int): String = when (state) {
        STATE_UNMOUNTED -> "unmounted"
        STATE_CHECKING -> "checking"
        STATE_MOUNTED -> "mounted"
        STATE_MOUNTED_READ_ONLY -> "mounted_read_only"
        STATE_FORMATTING -> "formatting"
        STATE_EJECTING -> "ejecting"
        STATE_UNMOUNTING -> "unmounting"
        STATE_BAD_REMOVAL -> "bad_removal"
        STATE_REMOVED -> "removed"
        else -> "unknown"
    }

    private fun appJson(
        app: InstalledApp,
        selectedPrivateUuid: String?,
        onCard: Boolean,
        cardReady: Boolean,
    ): JSONObject = JSONObject()
        .put("package_name", app.packageName)
        .put("source_uuid", VolumeUuids.normalize(app.sourceUuid))
        .put("install_location", app.installLocation)
        .put("install_location_name", MigrationPolicy.installLocationName(app.installLocation))
        .put("system", app.isSystem)
        .put("updated_system", app.isUpdatedSystem)
        .put("preinstalled", app.isPreinstalled)
        .put("device_admin", app.isDeviceAdmin ?: JSONObject.NULL)
        // Package size is not queried: the only platform API is an asynchronous
        // hidden observer, so an unknown size stays null instead of a guess.
        .put("size_bytes", app.sizeBytes ?: JSONObject.NULL)
        .put("on_this_card", onCard)
        .put(
            "eligible_for_sd",
            cardReady && MigrationPolicy.eligibleForSd(app, selectedPrivateUuid),
        )
        .put(
            "eligible_for_internal",
            cardReady && MigrationPolicy.eligibleForInternal(app, selectedPrivateUuid),
        )

    private fun recordJson(record: MigrationRecord): JSONObject = statusJson(
        moveId = record.moveId,
        state = record.state,
        progress = record.progress,
        nativeStatus = record.nativeStatus,
        kind = record.request.kind,
        targetUuid = record.request.targetUuid,
        packageName = record.request.packageName,
        error = record.error,
        detail = record.detail,
    )

    private fun statusJson(
        moveId: Int,
        state: String,
        progress: Int?,
        nativeStatus: Int?,
        kind: MigrationKind?,
        targetUuid: String?,
        packageName: String?,
        error: String?,
        detail: String?,
    ): JSONObject = JSONObject()
        .put("move_id", moveId)
        .put("state", state)
        .put("progress", progress ?: JSONObject.NULL)
        .put("native_status", nativeStatus ?: JSONObject.NULL)
        .put("kind", kind?.jsonName ?: JSONObject.NULL)
        .put("target_uuid", targetUuid ?: JSONObject.NULL)
        .put("package_name", packageName ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
        .put("detail", detail ?: JSONObject.NULL)

    /**
     * Handles the platform's `-100`: completion is reported only when the live
     * destination really is the requested one. A mismatch is a failure, never a
     * success.
     */
    private fun completeMigration(record: MigrationRecord, nativeStatus: Int): String {
        val observed = try {
            when (record.request.kind) {
                MigrationKind.PRIMARY -> nativeMigration.primaryStorageUuid(record.userId)
                MigrationKind.APP -> {
                    val packageName = requireNotNull(record.request.packageName)
                    val snapshot = nativeMigration.packageSnapshot(packageName, record.userId)
                        ?: return failMigration(
                            record = record,
                            nativeStatus = nativeStatus,
                            error = MigrationErrors.PACKAGE_MISSING,
                            detail = "the package disappeared before completion could be verified",
                        )
                    snapshot.sourceUuid
                }
            }
        } catch (e: Exception) {
            // Keep the record running: the platform already finished, but the
            // destination could not be read, so success must not be claimed.
            return fail(classify(e), e)
        }
        if (!MigrationPolicy.destinationMatches(record.request.targetUuid, observed)) {
            return failMigration(
                record = record,
                nativeStatus = nativeStatus,
                error = ERR_DESTINATION_MISMATCH,
                detail = "the platform reported completion but the destination did not change",
            )
        }
        val done = record.copy(
            state = STATE_MIGRATION_COMPLETED,
            progress = 100,
            nativeStatus = nativeStatus,
            error = null,
            detail = null,
        )
        migrationRecords[record.moveId] = done
        if (migrationActiveMoveId == record.moveId) migrationActiveMoveId = null
        log(LEVEL_INFO, "migration_completed", "move=${record.moveId}")
        return ok(recordJson(done))
    }

    private fun failMigration(
        record: MigrationRecord,
        nativeStatus: Int,
        error: String,
        detail: String,
    ): String {
        val failed = record.copy(
            state = STATE_MIGRATION_FAILED,
            progress = null,
            nativeStatus = nativeStatus,
            error = error,
            detail = detail,
        )
        migrationRecords[record.moveId] = failed
        if (migrationActiveMoveId == record.moveId) migrationActiveMoveId = null
        log(LEVEL_WARN, "migration_failed", error)
        return ok(recordJson(failed))
    }

    private fun trimMigrationRecords() {
        while (migrationRecords.size > MAX_RETAINED_MOVES) {
            val oldest = migrationRecords.keys.firstOrNull { it != migrationActiveMoveId }
                ?: break
            migrationRecords.remove(oldest)
        }
    }

    private fun parseMigrationRequest(raw: String): MigrationRequest {
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("request is not a JSON object")
        }
        val kind = MigrationKind.fromJson(json.optString("kind"))
            ?: throw IllegalArgumentException("kind must be 'primary' or 'app'")
        val diskId = json.optString("disk_id").trim()
        val targetUuid = json.optString("target_uuid").trim()
        val packageName = when {
            !json.has("package_name") || json.isNull("package_name") -> null
            else -> json.optString("package_name").trim().ifEmpty { null }
        }
        return MigrationRequest(
            kind = kind,
            diskId = diskId,
            targetUuid = targetUuid,
            packageName = packageName,
        )
    }

    private fun installedApp(packageName: String, userId: Int): InstalledApp? {
        val snapshot = try {
            nativeMigration.packageSnapshot(packageName, userId)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw MigrationRejectedException(
                MigrationErrors.APP_QUERY_UNAVAILABLE,
                "installed application state could not be read",
            )
        } ?: return null
        return snapshot.toInstalledApp(nativeMigration.isDeviceAdmin(packageName, userId))
    }

    private fun NativeMigration.PackageSnapshot.toInstalledApp(
        isDeviceAdmin: Boolean?,
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

    private fun onSelectedCard(
        snapshot: NativeMigration.PackageSnapshot,
        selectedPrivateUuid: String,
    ): Boolean = VolumeUuids.same(snapshot.sourceUuid, selectedPrivateUuid)

    /**
     * Refuses to format a card that still carries the device's primary storage
     * or any installed application. Returns [ERR_MOVE_BACK_REQUIRED] when the
     * card is still in use, `null` when it is safe.
     */
    private fun moveBackBlocked(
        diskId: String,
        volumes: List<NativeStorage.VolumeSnapshot>,
        userId: Int,
    ): String? {
        val privateVolumes = volumes.filter { it.diskId == diskId && it.type == TYPE_PRIVATE }
        if (privateVolumes.isEmpty()) return null
        val privateUuids = privateVolumes.mapNotNull { Identifiers.normalizeUuid(it.fsUuid) }.toSet()
        if (privateUuids.isEmpty()) return null
        val primary = Identifiers.normalizeUuid(nativeMigration.primaryStorageUuid(userId))
        if (primary != null && primary in privateUuids) return ERR_MOVE_BACK_REQUIRED
        val apps = try {
            nativeMigration.installedApps(userId)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw MigrationRejectedException(
                MigrationErrors.APP_QUERY_UNAVAILABLE,
                "installed applications could not be read",
            )
        }
        val onCard = apps.any { snapshot ->
            val source = Identifiers.normalizeUuid(snapshot.sourceUuid)
            source != null && source in privateUuids
        }
        return if (onCard) ERR_MOVE_BACK_REQUIRED else null
    }

    /**
     * Resolves the Android user for a binder caller from the per-user UID range
     * ([uid] / 100000), without the SDK-hidden UserHandle helper; the service is
     * single-user, so the derived user is only ever 0 in practice.
     */
    private fun userIdForCaller(uid: Int): Int = uid / 100_000

    private fun rejectionDetail(code: String): String = when (code) {
        MigrationErrors.ALREADY_AT_TARGET -> "the source is already at the requested destination"
        MigrationErrors.INVALID_TARGET ->
            "target_uuid is not the selected card's adopted private volume"
        MigrationErrors.INVALID_SOURCE ->
            "the source must be internal storage or the selected card's private volume"
        MigrationErrors.PACKAGE_NOT_ELIGIBLE ->
            "the application is not eligible for the selected destination"
        MigrationErrors.PROTECTED_PACKAGE -> "the allocator and Shizuku are never moved"
        else -> code
    }

    private fun identityFor(uid: Int): String = when (uid) {
        Process.ROOT_UID -> "root"
        Process.SHELL_UID -> "shell"
        else -> "uid:$uid"
    }

    /**
     * Maps a thrown failure to a stable error code, unwrapping the cause chain.
     * A [SecurityException] surfaced from the unwrapped binder call becomes
     * `permission_denied` rather than a generic error.
     */
    private fun classify(throwable: Throwable): String {
        var current: Throwable? = throwable
        while (current != null) {
            when (current) {
                is MigrationRejectedException -> return current.code
                is SecurityException -> return ERR_PERMISSION_DENIED
                is VerificationTimeoutException -> return ERR_VERIFICATION_TIMEOUT
                is CardChangedException -> return ERR_CARD_CHANGED
                is StorageUnavailableException -> return ERR_NATIVE_STORAGE_UNAVAILABLE
                is NativeMigrationException -> return ERR_NATIVE_MIGRATION_UNAVAILABLE
                is IllegalArgumentException -> return ERR_ALLOCATION_FAILED
            }
            current = current.cause
        }
        return ERR_INTERNAL
    }

    private fun ok(data: JSONObject): String =
        JSONObject().put("ok", true).put("data", data).toString()

    private fun fail(code: String, throwable: Throwable?): String {
        val message = throwable?.message
        log(LEVEL_ERROR, code, message)
        return JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("detail", message ?: JSONObject.NULL)
            .toString()
    }

    private fun failString(code: String, message: String?): String {
        log(LEVEL_ERROR, code, message)
        return JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("detail", message ?: JSONObject.NULL)
            .toString()
    }

    /** Appends a bounded, timestamped log entry. Never records key material. */
    private fun log(level: String, code: String, message: String?) {
        synchronized(lock) {
            logs.addLast(
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("level", level)
                    .put("code", code)
                    .put("detail", message ?: JSONObject.NULL),
            )
            while (logs.size > MAX_LOG_ENTRIES) logs.removeFirst()
        }
    }

    private companion object {
        const val STATE_IDLE = "idle"
        const val STATE_RUNNING = "running"
        const val STATE_PARTITIONED = "partitioned"
        const val STATE_FAILED = "failed"

        const val PHASE_PREPARED = "prepared"
        const val PHASE_RECHECKING = "rechecking"
        const val PHASE_PARTITIONING = "partitioning"
        const val PHASE_VERIFYING = "verifying"
        const val PHASE_PARTITIONED = "partitioned"
        const val PHASE_ANDROID_READY = "android_ready"
        const val PHASE_FAILED = "failed"

        const val LEVEL_INFO = "info"
        const val LEVEL_WARN = "warn"
        const val LEVEL_ERROR = "error"

        const val ERR_BUSY = "busy"
        const val ERR_INVALID_REQUEST = "invalid_request"
        const val ERR_NOT_CONFIRMED = "not_confirmed"
        const val ERR_INVALID_TOKEN = "invalid_token"
        const val ERR_NO_PLAN = "no_plan"
        const val ERR_CARD_MISSING = "card_missing"
        const val ERR_CARD_CHANGED = "card_changed"
        const val ERR_PERMISSION_DENIED = "permission_denied"
        const val ERR_NATIVE_STORAGE_UNAVAILABLE = "native_storage_unavailable"
        const val ERR_ALLOCATION_FAILED = "allocation_failed"
        const val ERR_VERIFICATION_TIMEOUT = "verification_timeout"
        const val ERR_INTERRUPTED = "verification_interrupted"
        const val ERR_INTERNAL = "internal_error"

        /** Partition refused because the card still holds primary storage/apps. */
        const val ERR_MOVE_BACK_REQUIRED = "move_back_required"
        const val ERR_NATIVE_MIGRATION_UNAVAILABLE = "native_migration_unavailable"
        const val ERR_NO_MIGRATION_PLAN = "no_migration_plan"
        const val ERR_MOVE_START_FAILED = "move_start_failed"
        const val ERR_DESTINATION_MISMATCH = "destination_mismatch"
        const val ERR_DESTINATION_UNVERIFIED = "destination_unverified"

        const val STATE_MIGRATION_RUNNING = "running"
        const val STATE_MIGRATION_COMPLETED = "completed"
        const val STATE_MIGRATION_FAILED = "failed"

        /** `VolumeInfo.TYPE_PUBLIC`. */
        const val TYPE_PUBLIC = 0

        /** `VolumeInfo.TYPE_PRIVATE`. */
        const val TYPE_PRIVATE = 1

        /** `VolumeInfo.STATE_*`. */
        const val STATE_UNMOUNTED = 0
        const val STATE_CHECKING = 1
        const val STATE_MOUNTED = 2
        const val STATE_MOUNTED_READ_ONLY = 3
        const val STATE_FORMATTING = 4
        const val STATE_EJECTING = 5
        const val STATE_UNMOUNTING = 6
        const val STATE_BAD_REMOVAL = 7
        const val STATE_REMOVED = 8

        const val MAX_MIGRATION_APPS = 300
        const val MAX_RETAINED_MOVES = 8

        const val ERASE_SCOPE = "entire_tf_card"
        const val HANDOFF_MAGIC = "armada-tf-handoff"

        /** Mixed handoff schema, unchanged. */
        const val HANDOFF_SCHEMA_VERSION = 1

        /** Linux-only handoff schema with explicit null private identifiers. */
        const val HANDOFF_SCHEMA_VERSION_LINUX_ONLY = 2

        /** Volume-identity fallback schema: no CID, volumes identify the card. */
        const val HANDOFF_SCHEMA_VERSION_VOLUME = 3
        const val IDENTITY_MODE_VOLUME = "volume"
        const val ALLOCATION_MODE_LINUX_ONLY = "linux_only"

        const val MAX_LOG_ENTRIES = 200
        const val VERIFY_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 1_000L
    }
}
