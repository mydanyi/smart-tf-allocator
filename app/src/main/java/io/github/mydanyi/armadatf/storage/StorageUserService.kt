package io.github.mydanyi.armadatf.storage

import android.os.Process
import io.github.mydanyi.armadatf.IStorageService
import io.github.mydanyi.armadatf.domain.AllocationPlan
import io.github.mydanyi.armadatf.domain.AllocationPolicy
import io.github.mydanyi.armadatf.domain.DiskSnapshot
import io.github.mydanyi.armadatf.domain.Identifiers
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
 * worker. State is only reported as `partitioned` after the new public/private
 * volume pair is verified on the very same card.
 */
class StorageUserService : IStorageService.Stub() {

    private val nativeStorage = NativeStorage()
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

    private var workerActive = false
    private var destroyPending = false

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
        synchronized(lock) {
            if (state == STATE_RUNNING) {
                return failString(ERR_BUSY, "a partition operation is already running")
            }
            if (!AllocationPolicy.isValidPercent(linuxPercent)) {
                return failString(
                    ERR_INVALID_REQUEST,
                    "linuxPercent must be in ${AllocationPolicy.MIN_PERCENT}.." +
                        "${AllocationPolicy.MAX_PERCENT}",
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
            val (androidBytes, linuxBytes) = AllocationPolicy.capacity(plan)
            if (androidBytes <= 0L || linuxBytes <= 0L) {
                return failString(
                    ERR_ALLOCATION_FAILED,
                    "card is too small for a $linuxPercent% split",
                )
            }
            val guids = try {
                existingPrivateGuids(plan.diskId)
            } catch (e: Exception) {
                return fail(classify(e), e)
            }

            val token = UUID.randomUUID().toString()
            pendingPlan = plan
            pendingToken = token
            knownPrivateGuids = guids
            state = STATE_IDLE
            phase = PHASE_PREPARED
            detail = null
            handoff = null
            log(LEVEL_INFO, "prepare", "disk=${plan.diskId} linux_ratio=$linuxPercent")

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
        synchronized(lock) {
            if (!confirmed) {
                return failString(ERR_NOT_CONFIRMED, "start requires confirmed=true")
            }
            if (state == STATE_RUNNING) {
                return failString(ERR_BUSY, "a partition operation is already running")
            }
            val plan = pendingPlan
                ?: return failString(ERR_NO_PLAN, "prepare must succeed before start")
            val expected = pendingToken
                ?: return failString(ERR_NO_PLAN, "prepare must succeed before start")
            if (token != expected) {
                return failString(ERR_INVALID_TOKEN, "token does not match the prepared plan")
            }

            // The token is single-use: consume it before doing anything else.
            pendingToken = null
            pendingPlan = null
            val guids = knownPrivateGuids

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
            worker.execute { runPartition(plan, guids) }

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
            handoff = null
            state = STATE_IDLE
            phase = null
            detail = null
            worker.shutdown()
        }
    }

    // --- worker --------------------------------------------------------------

    private fun runPartition(plan: AllocationPlan, knownGuids: Set<String>) {
        try {
            synchronized(lock) { phase = PHASE_PARTITIONING }
            log(LEVEL_INFO, "partition", "disk=${plan.diskId} linux_ratio=${plan.linuxPercent}")
            nativeStorage.partitionMixed(plan)

            synchronized(lock) { phase = PHASE_VERIFYING }
            val verification = verifyNewLayout(plan, knownGuids)

            synchronized(lock) {
                handoff = buildHandoff(plan, verification)
                state = STATE_PARTITIONED
                phase = PHASE_PARTITIONED
                detail = null
                log(LEVEL_INFO, "partitioned", "verified volume pair on disk=${plan.diskId}")
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
     * until exactly one mounted public volume (type 0) and one mounted private
     * volume (type 1) exist on the same card, both with non-empty filesystem
     * ids, and the private partition GUID is canonical and brand new.
     *
     * The card identity (id/size/CID/flags) is re-checked on every poll via
     * [AllocationPolicy.matches] so a swap during verification is fatal.
     */
    private fun verifyNewLayout(plan: AllocationPlan, knownGuids: Set<String>): Verification {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        var lastReason = "volume_pair_not_ready"
        while (true) {
            val disk = nativeStorage.disks().firstOrNull { it.id == plan.diskId }
            if (disk == null || !AllocationPolicy.matches(plan, disk)) {
                throw CardChangedException("card changed during verification")
            }
            val volumes = nativeStorage.volumes().filter { it.diskId == plan.diskId }
            val publicVolumes = volumes.filter { it.type == TYPE_PUBLIC && it.state == STATE_MOUNTED }
            val privateVolumes =
                volumes.filter { it.type == TYPE_PRIVATE && it.state == STATE_MOUNTED }

            if (publicVolumes.size == 1 && privateVolumes.size == 1) {
                val publicVolume = publicVolumes.first()
                val privateVolume = privateVolumes.first()
                val publicFsUuid = publicVolume.fsUuid
                val privateFsUuid = privateVolume.fsUuid
                val privateGuid = privateVolume.partGuid
                val canonicalPrivateFsUuid = Identifiers.normalizeUuid(privateFsUuid)
                when {
                    publicFsUuid.isNullOrEmpty() -> lastReason = "public_fs_uuid_missing"
                    privateFsUuid.isNullOrEmpty() -> lastReason = "private_fs_uuid_missing"
                    canonicalPrivateFsUuid == null -> lastReason = "private_fs_uuid_not_canonical"
                    privateGuid.isNullOrEmpty() -> lastReason = "private_partition_guid_missing"
                    privateGuid in knownGuids -> lastReason = "private_partition_guid_not_new"
                    else -> return Verification(
                        publicFsUuid = publicFsUuid,
                        publicPartitionGuid = publicVolume.partGuid,
                        privatePartitionGuid = privateGuid,
                        privateFsUuid = canonicalPrivateFsUuid,
                    )
                }
            } else {
                lastReason = "volume_pair_not_ready"
            }

            if (System.currentTimeMillis() >= deadline) {
                throw VerificationTimeoutException(lastReason)
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun existingPrivateGuids(diskId: String): Set<String> =
        nativeStorage.volumes()
            .filter { it.diskId == diskId && it.type == TYPE_PRIVATE }
            .mapNotNull { it.partGuid }
            .toSet()

    private fun diskJson(disk: DiskSnapshot): JSONObject {
        val eligible = disk.isAdoptable && disk.sizeBytes > 0L && Identifiers.isValidCid(disk.cid)
        return JSONObject()
            .put("id", disk.id)
            .put("size_bytes", disk.sizeBytes)
            .put("card_cid", disk.cid)
            .put("is_sd", disk.isSd)
            .put("is_adoptable", disk.isAdoptable)
            .put("eligible", eligible)
            .put("reason", eligibleReason(disk, eligible))
    }

    private fun eligibleReason(disk: DiskSnapshot, eligible: Boolean): Any = when {
        eligible -> JSONObject.NULL
        !disk.isAdoptable -> "not_adoptable"
        disk.sizeBytes <= 0L -> "zero_size"
        !Identifiers.isValidCid(disk.cid) -> "cid_unreadable"
        else -> "unknown"
    }

    private fun buildHandoff(plan: AllocationPlan, verification: Verification): JSONObject =
        JSONObject()
            .put("magic", HANDOFF_MAGIC)
            .put("schema_version", HANDOFF_SCHEMA_VERSION)
            .put("card_cid", plan.cardCid)
            .put("disk_bytes", plan.diskBytes)
            .put("linux_ratio", plan.linuxPercent)
            .put("public_fs_uuid", verification.publicFsUuid)
            .put("public_partition_guid", verification.publicPartitionGuid ?: JSONObject.NULL)
            .put("private_partition_guid", verification.privatePartitionGuid)
            .put("private_fs_uuid", verification.privateFsUuid)

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
                is SecurityException -> return ERR_PERMISSION_DENIED
                is VerificationTimeoutException -> return ERR_VERIFICATION_TIMEOUT
                is CardChangedException -> return ERR_CARD_CHANGED
                is StorageUnavailableException -> return ERR_NATIVE_STORAGE_UNAVAILABLE
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

    private data class Verification(
        val publicFsUuid: String,
        val publicPartitionGuid: String?,
        val privatePartitionGuid: String,
        val privateFsUuid: String,
    )

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

        /** `VolumeInfo.TYPE_PUBLIC`. */
        const val TYPE_PUBLIC = 0

        /** `VolumeInfo.TYPE_PRIVATE`. */
        const val TYPE_PRIVATE = 1

        /** `VolumeInfo.STATE_MOUNTED`. */
        const val STATE_MOUNTED = 2

        const val ERASE_SCOPE = "entire_tf_card"
        const val HANDOFF_MAGIC = "armada-tf-handoff"
        const val HANDOFF_SCHEMA_VERSION = 1

        const val MAX_LOG_ENTRIES = 200
        const val VERIFY_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 1_000L
    }
}
