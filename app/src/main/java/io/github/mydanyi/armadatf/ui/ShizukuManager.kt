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

    interface Callback {
        fun onState(state: State, identity: String?)
        fun onStatus(status: OperationStatus)
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
    var operationActive: Boolean = false
        private set

    private var listening = false
    private var polling = false

    private val pollRunnable = Runnable { pollOnce() }

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
            operationActive = false
            stopPolling()
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
        operationActive = false
        stopPolling()
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
            .version(SERVICE_VERSION)
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
            operationActive = status.state == STATE_RUNNING
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
            operationActive = status.state == STATE_RUNNING
            status to null
        }
    }

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

        private const val SHIZUKU_MIN_VERSION = 11
        private const val SERVICE_TAG = "stable"
        private const val SERVICE_VERSION = 1
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
