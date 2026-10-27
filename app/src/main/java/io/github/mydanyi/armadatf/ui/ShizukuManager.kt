package io.github.mydanyi.armadatf.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mydanyi.armadatf.BuildConfig
import io.github.mydanyi.armadatf.IStorageService
import io.github.mydanyi.armadatf.storage.StorageUserService
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Application-scoped owner of the Shizuku binder and the privileged storage
 * user-service connection.
 *
 * The manager is a process-wide singleton so a configuration change or an
 * Activity relaunch never opens a second user-service binding: the binding is
 * created once and reused, and the Activity only attaches/detaches a
 * [Callback]. Every Shizuku and binder call is guarded by [Shizuku.pingBinder]
 * and a granted-permission check and runs off the main thread; all callbacks
 * are delivered on the main thread.
 */
class ShizukuManager private constructor(private val appContext: Context) {

    enum class State {
        NOT_INSTALLED,
        NOT_RUNNING,
        UNSUPPORTED,
        PERMISSION_NEEDED,
        PERMISSION_DENIED,
        CONNECTING,
        READY,
        BINDER_LOST,
    }

    data class CardInfo(
        val id: String,
        val sizeBytes: Long,
        val cid: String,
        val eligible: Boolean,
        val reason: String?,
    )

    data class Prepared(
        val token: String,
        val diskId: String,
        val cardCid: String,
        val diskBytes: Long,
        val androidBytes: Long,
        val linuxBytes: Long,
        val eraseScope: String?,
    )

    data class LogEntry(val ts: Long, val level: String, val code: String, val detail: String?)

    data class OperationStatus(
        val state: String,
        val phase: String?,
        val detail: String?,
        val logs: List<LogEntry>,
        val handoff: JSONObject?,
    )

    data class IpcError(val code: String, val detail: String?)

    /** Stable request captured by a prepared migration token. */
    data class MigrationRequest(
        val kind: String,
        val diskId: String,
        val targetUuid: String,
        val packageName: String? = null,
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
                .put("kind", kind)
                .put("disk_id", diskId)
                .put("target_uuid", targetUuid)
            if (kind == ShizukuManager.KIND_APP) json.put("package_name", packageName)
            return json
        }
    }

    /** One-use prepared migration plus the source/target snapshot to confirm. */
    data class PreparedMigration(
        val token: String,
        val kind: String,
        val diskId: String,
        val targetUuid: String,
        val packageName: String?,
        val sourceUuid: String,
        val selectedPrivateUuid: String?,
        val layout: String?,
    )

    /** Parsed `migrationStatus` payload; progress is the actual native value. */
    data class MigrationStatus(
        val moveId: Int,
        val state: String,
        val progress: Int?,
        val nativeStatus: Int?,
        val kind: String?,
        val targetUuid: String?,
        val packageName: String?,
        val error: String?,
        val detail: String?,
    )

    /** One application row of the read-only migration inventory. */
    data class MigrationApp(
        val packageName: String,
        val sourceUuid: String,
        val installLocationName: String,
        val system: Boolean,
        val updatedSystem: Boolean,
        val deviceAdmin: Boolean?,
        val onThisCard: Boolean,
        val eligibleForSd: Boolean,
        val eligibleForInternal: Boolean,
    )

    /** Read-only view of the selected card's migration options. */
    data class MigrationOverview(
        val diskId: String,
        val isSd: Boolean,
        val layout: String,
        val primaryUuid: String,
        val primaryOnThisDisk: Boolean,
        val selectedPrivateUuid: String?,
        val destinationAvailable: Boolean,
        val moveBackRequired: Boolean?,
        val volumes: List<MigrationVolume>,
        val apps: List<MigrationApp>,
        val appsTruncated: Boolean,
        val appsError: String?,
        val activeMigration: MigrationStatus?,
    )

    data class MigrationVolume(
        val type: String,
        val capacityBytes: Long?,
        val availableBytes: Long?,
    )

    interface Callback {
        fun onState(state: State, identity: String?)
        fun onStatus(status: OperationStatus)

        /** App-scoped migration progress; a no-op keeps older listeners compatible. */
        fun onMigrationStatus(status: MigrationStatus?, error: IpcError?) {}
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "armada-tf-ipc").apply { isDaemon = true }
    }

    private val callbacks = LinkedHashSet<Callback>()

    private var state: State = State.NOT_INSTALLED

    @Volatile
    private var identity: String? = null

    private var binder: IStorageService? = null
    private var bound = false
    private var requestDenied = false
    private var connected = false

    /** Exact args used for the active binding, reused verbatim on unbind. */
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    /** True while the privileged service reports an in-flight partition run. */
    @Volatile
    private var partitionActive: Boolean = false

    /** True from the moment a migration start is requested until it is terminal. */
    @Volatile
    var migrationActive: Boolean = false
        private set

    /**
     * True while any privileged operation (partition or migration) is active,
     * including the gap between a confirmed migration start and its answer.
     */
    val operationActive: Boolean
        get() = partitionActive || migrationActive

    private var listening = false
    private var polling = false

    private val pollRunnable = Runnable { pollOnce() }

    private var migrationPolling = false
    private var migrationMoveId: Int? = null

    private val migrationPollRunnable = Runnable { pollMigrationOnce() }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { onBinderReceived() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { onBinderDead() }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult -> onPermissionResult(grantResult) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val iface = service?.let { IStorageService.Stub.asInterface(it) }
            synchronized(lock) {
                connected = true
                binder = iface
            }
            if (iface == null) {
                setState(State.BINDER_LOST)
                return
            }
            setState(State.READY)
            requestStatus { _, _ -> }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                connected = false
                binder = null
                bound = false
                userServiceArgs = null
            }
            partitionActive = false
            stopPolling()
            stopMigrationPolling()
            setState(State.BINDER_LOST)
        }
    }

    // --- lifecycle -----------------------------------------------------------

    fun addCallback(callback: Callback) {
        synchronized(lock) { callbacks.add(callback) }
        callback.onState(state, identity)
    }

    fun removeCallback(callback: Callback) {
        synchronized(lock) { callbacks.remove(callback) }
    }

    fun currentState(): State = synchronized(lock) { state }

    fun currentIdentity(): String? = identity

    /** Registers the Shizuku listeners and evaluates the current binder state. */
    fun start() {
        ensureListening()
        refreshState()
    }

    /**
     * Called when an Activity goes away. Idle managers tear down their listeners
     * and binding; a manager with an in-flight operation stays bound so the run
     * survives a rotation or relaunch and can be recovered on reconnect.
     */
    fun onActivityDestroyed(finishing: Boolean) {
        if (!finishing) return
        if (operationActive) return
        shutdown()
    }

    private fun ensureListening() {
        synchronized(lock) {
            if (listening) return
            listening = true
        }
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    private fun shutdown() {
        if (polling) stopPolling()
        stopMigrationPolling()
        synchronized(lock) {
            if (listening) {
                listening = false
                runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
                runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
                runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
            }
            if (bound) {
                bound = false
                connected = false
                binder = null
                val args = userServiceArgs
                userServiceArgs = null
                if (args != null) {
                    // remove = false: keep the daemon and its handoff alive so a
                    // relaunch can reconnect; never kill an in-flight allocation.
                    runCatching { Shizuku.unbindUserService(args, connection, false) }
                }
            }
            callbacks.clear()
        }
        executor.shutdown()
        synchronized(INSTANCE_LOCK) { if (instance === this) instance = null }
    }

    // --- state machine -------------------------------------------------------

    private fun refreshState() {
        if (!isShizukuInstalled()) {
            setState(State.NOT_INSTALLED)
            return
        }
        if (!pingBinder()) {
            val previous = state
            setState(if (previous == State.READY || previous == State.CONNECTING) State.BINDER_LOST else State.NOT_RUNNING)
            return
        }
        if (Shizuku.getVersion() < SHIZUKU_MIN_VERSION) {
            setState(State.UNSUPPORTED)
            return
        }
        if (!hasPermission()) {
            setState(if (requestDenied) State.PERMISSION_DENIED else State.PERMISSION_NEEDED)
            return
        }
        if (connected && binder != null) {
            setState(State.READY)
            return
        }
        setState(State.CONNECTING)
        bindService()
    }

    /** Requests Shizuku permission. Only ever called from an explicit user action. */
    fun authorize() {
        if (!isShizukuInstalled() || !pingBinder() || Shizuku.getVersion() < SHIZUKU_MIN_VERSION) {
            refreshState()
            return
        }
        if (hasPermission()) {
            refreshState()
            return
        }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            setState(State.PERMISSION_NEEDED)
        }
    }

    fun openShizukuManager(): Boolean {
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return try {
            appContext.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun onBinderReceived() {
        refreshState()
    }

    private fun onBinderDead() {
        synchronized(lock) {
            connected = false
            binder = null
            bound = false
            userServiceArgs = null
        }
        partitionActive = false
        stopPolling()
        stopMigrationPolling()
        setState(State.BINDER_LOST)
    }

    private fun onPermissionResult(grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            requestDenied = false
            refreshState()
        } else {
            requestDenied = true
            setState(State.PERMISSION_DENIED)
        }
    }

    private fun bindService() {
        synchronized(lock) {
            if (bound) return
            bound = true
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, StorageUserService::class.java.name),
        )
            .daemon(true)
            .processNameSuffix(SERVICE_PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag(SERVICE_TAG)
        synchronized(lock) { userServiceArgs = args }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            synchronized(lock) {
                bound = false
                userServiceArgs = null
            }
            setState(State.BINDER_LOST)
        }
    }

    private fun setState(newState: State) {
        val changed = synchronized(lock) {
            if (state == newState) false else { state = newState; true }
        }
        val snapshot = synchronized(lock) { state }
        val identitySnapshot = identity
        if (changed) {
            postMain { dispatchState(snapshot, identitySnapshot) }
        }
    }

    private fun dispatchState(snapshot: State, identitySnapshot: String?) {
        val copy = synchronized(lock) { callbacks.toList() }
        for (callback in copy) callback.onState(snapshot, identitySnapshot)
    }

    // --- IPC -----------------------------------------------------------------

    fun refreshDisks(callback: (List<CardInfo>?, IpcError?) -> Unit) {
        withService(callback) { service ->
            val json = JSONObject(service.inspect())
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            val data = json.getJSONObject("data")
            identity = data.optString("identity").takeIf { it.isNotEmpty() }
            parseDisks(data.optJSONArray("disks") ?: JSONArray()) to null
        }
    }

    /**
     * Read-only live card state for [diskId]; never prepares, starts or mutates
     * anything. The parsed model is the pure UI presentation input.
     */
    fun requestMigrationInfo(
        diskId: String,
        callback: (LiveCardInfo?, IpcError?) -> Unit,
    ) {
        withService(callback) { service ->
            val json = JSONObject(service.migrationInfo(diskId))
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            parseLiveCardInfo(json.getJSONObject("data")) to null
        }
    }

    fun prepare(diskId: String, androidPercent: Int, callback: (Prepared?, IpcError?) -> Unit) {
        val linuxPercent = 100 - androidPercent
        withService(callback) { service ->
            val json = JSONObject(service.prepare(diskId, linuxPercent))
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            val data = json.getJSONObject("data")
            val prepared = Prepared(
                token = data.getString("token"),
                diskId = data.optString("disk_id"),
                cardCid = data.optString("card_cid"),
                diskBytes = data.optLong("disk_bytes"),
                androidBytes = data.optLong("android_bytes"),
                linuxBytes = data.optLong("linux_bytes"),
                eraseScope = data.optString("erase_scope").takeIf { it.isNotEmpty() },
            )
            prepared to null
        }
    }

    fun start(token: String, callback: (OperationStatus?, IpcError?) -> Unit) {
        withService(callback) { service ->
            val json = JSONObject(service.start(token, true))
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            val data = json.getJSONObject("data")
            val status = OperationStatus(
                state = data.optString("status", "running"),
                phase = data.optString("phase").takeIf { it.isNotEmpty() },
                detail = null,
                logs = emptyList(),
                handoff = null,
            )
            partitionActive = status.state == STATE_RUNNING
            status to null
        }
    }

    fun requestStatus(callback: (OperationStatus?, IpcError?) -> Unit) {
        withService(callback) { service ->
            val json = JSONObject(service.status())
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            val status = parseStatus(json.getJSONObject("data"))
            partitionActive = status.state == STATE_RUNNING
            status to null
        }
    }

    // --- migration -----------------------------------------------------------

    /**
     * Read-only migration inventory (primary location, app candidates, retained
     * active move) for the selected disk. Never prepares or starts anything.
     */
    fun requestMigrationOverview(
        diskId: String,
        callback: (MigrationOverview?, IpcError?) -> Unit,
    ) {
        withService(callback) { service ->
            val json = JSONObject(service.migrationInfo(diskId))
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            parseMigrationOverview(json.getJSONObject("data")) to null
        }
    }

    /** Prepares a one-use migration token; no card or package is changed. */
    fun prepareMigration(
        request: MigrationRequest,
        callback: (PreparedMigration?, IpcError?) -> Unit,
    ) {
        withService(callback) { service ->
            val json = JSONObject(service.prepareMigration(request.toJson().toString()))
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            val data = json.getJSONObject("data")
            val prepared = PreparedMigration(
                token = data.getString("token"),
                kind = data.optString("kind"),
                diskId = data.optString("disk_id"),
                targetUuid = data.optString("target_uuid"),
                packageName = nullableString(data, "package_name"),
                sourceUuid = data.optString("source_uuid"),
                selectedPrivateUuid = nullableString(data, "selected_private_uuid"),
                layout = nullableString(data, "layout"),
            )
            prepared to null
        }
    }

    /**
     * Starts a confirmed one-use migration. [migrationActive] is raised before
     * the IPC call so the binding survives an Activity close during the gap and
     * no destructive action can slip in.
     */
    fun startMigration(token: String, callback: (MigrationStatus?, IpcError?) -> Unit) {
        setMigrationActive(true)
        if (currentService() == null) {
            setMigrationActive(false)
            postMain { callback(null, IpcError(ERR_NOT_READY, null)) }
            return
        }
        withService({ status: MigrationStatus?, error: IpcError? ->
            // A definitive refusal means this call issued no native move, so the
            // busy state may drop. An IPC/read failure leaves the outcome
            // unknown: stay busy and let the Activity recover the retained move
            // from migrationInfo instead of repeating the start.
            if (error != null && !isTransportFailure(error.code)) setMigrationActive(false)
            callback(status, error)
        }) { service ->
            val json = JSONObject(service.startMigration(token, true))
            if (!json.optBoolean("ok", false)) {
                // The start call returned without a running record; the token is
                // spent, so a repeat is impossible. Any retained active move is
                // recovered from migrationInfo.active_migration, never restarted.
                return@withService null to errorOf(json)
            }
            val status = parseMigrationStatus(json.getJSONObject("data"))
            setMigrationActive(status.state == MIGRATION_RUNNING)
            status to null
        }
    }

    /** One read-only status query; the caller owns any polling loop. */
    fun requestMigrationStatus(
        moveId: Int,
        callback: (MigrationStatus?, IpcError?) -> Unit,
    ) {
        withService(callback) { service ->
            val json = JSONObject(service.migrationStatus(moveId))
            if (!json.optBoolean("ok", false)) {
                return@withService null to errorOf(json)
            }
            val status = parseMigrationStatus(json.getJSONObject("data"))
            // Only a parsed native answer decides the busy state. An unreadable
            // status says nothing about the move and must not look like a
            // native failure.
            setMigrationActive(status.state == MIGRATION_RUNNING)
            status to null
        }
    }

    /**
     * Activity-side release after a verified completion or a definitive
     * pre-start refusal. Never called for an unreadable in-flight move.
     */
    fun markMigrationIdle() {
        stopMigrationPolling()
        setMigrationActive(false)
    }

    /** App-scoped migration polling; terminal/unavailable answers stop it. */
    fun startMigrationPolling(moveId: Int) {
        synchronized(lock) {
            if (migrationPolling && migrationMoveId == moveId) return
            migrationPolling = true
            migrationMoveId = moveId
        }
        mainHandler.removeCallbacks(migrationPollRunnable)
        // A restored known move is busy immediately, before the first poll.
        setMigrationActive(true)
        mainHandler.post(migrationPollRunnable)
    }

    fun stopMigrationPolling() {
        synchronized(lock) {
            migrationPolling = false
            migrationMoveId = null
        }
        mainHandler.removeCallbacks(migrationPollRunnable)
    }

    private fun pollMigrationOnce() {
        val moveId = synchronized(lock) {
            if (migrationPolling) migrationMoveId else null
        }
        if (moveId == null) {
            stopMigrationPolling()
            return
        }
        requestMigrationStatus(moveId) { status, error ->
            dispatchMigration(status, error)
            val running = status != null && status.state == MIGRATION_RUNNING
            // A transport/read failure says nothing about the native move, so
            // keep polling the same id instead of dropping the tracking.
            val retry = status == null && error != null && isTransportFailure(error.code)
            if ((running || retry) && synchronized(lock) { migrationPolling }) {
                mainHandler.postDelayed(migrationPollRunnable, POLL_INTERVAL_MS)
            } else {
                stopMigrationPolling()
            }
        }
    }

    private fun dispatchMigration(status: MigrationStatus?, error: IpcError?) {
        val copy = synchronized(lock) { callbacks.toList() }
        for (callback in copy) callback.onMigrationStatus(status, error)
    }

    /**
     * Raises or lowers [migrationActive] and tells the listeners on the main
     * thread. The notification carries no status, so listeners only re-evaluate
     * their controls and can never re-enter a start or a poll.
     */
    private fun setMigrationActive(active: Boolean) {
        val changed = synchronized(lock) {
            if (migrationActive == active) {
                false
            } else {
                migrationActive = active
                true
            }
        }
        if (changed) postMain { dispatchMigration(null, null) }
    }

    /** IPC/read failure: the call may have reached the service, outcome unknown. */
    private fun isTransportFailure(code: String): Boolean = code == ERR_INTERNAL

    fun startPolling() {
        synchronized(lock) {
            if (polling) return
            polling = true
        }
        mainHandler.post(pollRunnable)
    }

    fun stopPolling() {
        synchronized(lock) { polling = false }
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun pollOnce() {
        if (!synchronized(lock) { polling }) return
        requestStatus { status, _ ->
            if (status != null) {
                dispatchStatus(status)
                if (status.state != STATE_RUNNING) {
                    stopPolling()
                    return@requestStatus
                }
            }
            if (synchronized(lock) { polling }) {
                mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            }
        }
    }

    private fun dispatchStatus(status: OperationStatus) {
        val copy = synchronized(lock) { callbacks.toList() }
        for (callback in copy) callback.onStatus(status)
    }

    /**
     * Runs [block] against the bound service on the IPC executor, delivering the
     * pair result on the main thread. When the service is not ready the callback
     * receives `service_not_ready` without touching any Shizuku method.
     */
    private fun <T> withService(
        callback: (T?, IpcError?) -> Unit,
        block: (IStorageService) -> Pair<T?, IpcError?>,
    ) {
        val service = currentService()
        if (service == null) {
            postMain { callback(null, IpcError(ERR_NOT_READY, null)) }
            return
        }
        executor.execute {
            val result = try {
                block(service)
            } catch (t: Throwable) {
                null to IpcError(ERR_INTERNAL, t.message)
            }
            postMain { callback(result.first, result.second) }
        }
    }

    private fun currentService(): IStorageService? = synchronized(lock) {
        if (!connected) null else binder
    }

    private fun parseDisks(array: JSONArray): List<CardInfo> {
        val disks = ArrayList<CardInfo>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            disks.add(
                CardInfo(
                    id = item.optString("id"),
                    sizeBytes = item.optLong("size_bytes"),
                    cid = item.optString("card_cid"),
                    eligible = item.optBoolean("eligible", false),
                    reason = item.optString("reason").takeIf { it.isNotEmpty() && it != "null" },
                ),
            )
        }
        return disks
    }

    private fun parseLiveCardInfo(data: JSONObject): LiveCardInfo {
        val volumes = ArrayList<LiveVolume>()
        val array = data.optJSONArray("volumes") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            volumes.add(
                LiveVolume(
                    type = item.optString("type"),
                    fsUuid = nullableString(item, "fs_uuid"),
                    partGuid = nullableString(item, "part_guid"),
                    capacityBytes = nullableLong(item, "capacity_bytes")?.takeIf { it > 0L },
                ),
            )
        }
        return LiveCardInfo(
            diskId = data.optString("disk_id"),
            diskBytes = data.optLong("disk_bytes"),
            cardCid = data.optString("card_cid").takeUnless { it == "null" }.orEmpty(),
            isSd = data.optBoolean("is_sd", false),
            layout = data.optString("layout", "none"),
            selectedPrivateUuid = nullableString(data, "selected_private_uuid"),
            moveBackRequired = if (!data.has("move_back_required") ||
                data.isNull("move_back_required")
            ) {
                null
            } else {
                data.optBoolean("move_back_required")
            },
            volumes = volumes,
        )
    }

    private fun nullableString(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) {
            null
        } else {
            json.optString(key).takeIf { it.isNotEmpty() && it != "null" }
        }

    private fun nullableLong(json: JSONObject, key: String): Long? =
        if (!json.has(key) || json.isNull(key)) null else json.optLong(key)

    private fun nullableInt(json: JSONObject, key: String): Int? =
        if (!json.has(key) || json.isNull(key)) null else json.optInt(key)

    private fun nullableBoolean(json: JSONObject, key: String): Boolean? =
        if (!json.has(key) || json.isNull(key)) null else json.optBoolean(key)

    private fun parseMigrationStatus(data: JSONObject): MigrationStatus = MigrationStatus(
        moveId = data.optInt("move_id", -1),
        state = data.optString("state", MIGRATION_FAILED),
        progress = nullableInt(data, "progress"),
        nativeStatus = nullableInt(data, "native_status"),
        kind = nullableString(data, "kind"),
        targetUuid = nullableString(data, "target_uuid"),
        packageName = nullableString(data, "package_name"),
        error = nullableString(data, "error"),
        detail = nullableString(data, "detail"),
    )

    private fun parseMigrationApp(data: JSONObject): MigrationApp = MigrationApp(
        packageName = data.optString("package_name"),
        sourceUuid = data.optString("source_uuid"),
        installLocationName = data.optString("install_location_name", "unknown"),
        system = data.optBoolean("system", false),
        updatedSystem = data.optBoolean("updated_system", false),
        deviceAdmin = nullableBoolean(data, "device_admin"),
        onThisCard = data.optBoolean("on_this_card", false),
        eligibleForSd = data.optBoolean("eligible_for_sd", false),
        eligibleForInternal = data.optBoolean("eligible_for_internal", false),
    )

    private fun parseMigrationOverview(data: JSONObject): MigrationOverview {
        val volumes = ArrayList<MigrationVolume>()
        val volumeArray = data.optJSONArray("volumes") ?: JSONArray()
        for (index in 0 until volumeArray.length()) {
            val item = volumeArray.optJSONObject(index) ?: continue
            volumes.add(
                MigrationVolume(
                    type = item.optString("type"),
                    capacityBytes = nullableLong(item, "capacity_bytes")?.takeIf { it > 0L },
                    availableBytes = nullableLong(item, "available_bytes")?.takeIf { it >= 0L },
                ),
            )
        }
        val apps = ArrayList<MigrationApp>()
        val appArray = data.optJSONArray("apps") ?: JSONArray()
        for (index in 0 until appArray.length()) {
            val item = appArray.optJSONObject(index) ?: continue
            apps.add(parseMigrationApp(item))
        }
        val active = if (data.has("active_migration") && !data.isNull("active_migration")) {
            data.optJSONObject("active_migration")?.let { parseMigrationStatus(it) }
        } else {
            null
        }
        return MigrationOverview(
            diskId = data.optString("disk_id"),
            isSd = data.optBoolean("is_sd", false),
            layout = data.optString("layout", "none"),
            primaryUuid = data.optString("primary_uuid", "internal"),
            primaryOnThisDisk = data.optBoolean("primary_on_this_disk", false),
            selectedPrivateUuid = nullableString(data, "selected_private_uuid"),
            destinationAvailable = data.optBoolean("destination_available", false),
            moveBackRequired = nullableBoolean(data, "move_back_required"),
            volumes = volumes,
            apps = apps,
            appsTruncated = data.optBoolean("apps_truncated", false),
            appsError = nullableString(data, "apps_error"),
            activeMigration = active,
        )
    }

    private fun parseStatus(data: JSONObject): OperationStatus {
        val logs = ArrayList<LogEntry>()
        val array = data.optJSONArray("logs") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            logs.add(
                LogEntry(
                    ts = item.optLong("ts"),
                    level = item.optString("level", "info"),
                    code = item.optString("code"),
                    detail = item.optString("detail").takeIf { it.isNotEmpty() && it != "null" },
                ),
            )
        }
        val handoff = if (data.has("handoff") && !data.isNull("handoff")) data.optJSONObject("handoff") else null
        return OperationStatus(
            state = data.optString("state", "idle"),
            phase = data.optString("phase").takeIf { it.isNotEmpty() && it != "null" },
            detail = data.optString("detail").takeIf { it.isNotEmpty() && it != "null" },
            logs = logs,
            handoff = handoff,
        )
    }

    private fun errorOf(json: JSONObject): IpcError =
        IpcError(
            code = json.optString("error", ERR_INTERNAL),
            detail = json.optString("detail").takeIf { it.isNotEmpty() && it != "null" },
        )

    // --- platform guards -----------------------------------------------------

    private fun isShizukuInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (t: Exception) {
        false
    }

    private fun pingBinder(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private fun hasPermission(): Boolean = try {
        if (!Shizuku.pingBinder()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun postMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    companion object {
        private val INSTANCE_LOCK = Any()

        @Volatile
        private var instance: ShizukuManager? = null

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4271

        const val KIND_PRIMARY = "primary"
        const val KIND_APP = "app"
        const val MIGRATION_RUNNING = "running"
        const val MIGRATION_COMPLETED = "completed"
        const val MIGRATION_FAILED = "failed"

        private const val SHIZUKU_MIN_VERSION = 11
        private const val SERVICE_TAG = "stable"
        private const val SERVICE_PROCESS_SUFFIX = "storage"
        private const val STATE_RUNNING = "running"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val ERR_NOT_READY = "service_not_ready"
        private const val ERR_INTERNAL = "internal_error"

        fun get(context: Context): ShizukuManager {
            val existing = instance
            if (existing != null) return existing
            synchronized(INSTANCE_LOCK) {
                val current = instance
                if (current != null) return current
                val created = ShizukuManager(context.applicationContext)
                instance = created
                return created
            }
        }
    }
}
