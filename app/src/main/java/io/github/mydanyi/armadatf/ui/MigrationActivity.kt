package io.github.mydanyi.armadatf.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.mydanyi.armadatf.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Migration management for the adopted TF card: primary shared storage and
 * eligible applications, with an explicit prepare -> confirm -> start flow.
 *
 * This Activity is only a second listener on the process-wide
 * [ShizukuManager]. It never owns or tears down the manager: on finish only its
 * own callback is removed, so the retained binding stays with MainActivity.
 * Every start is persisted before the IPC call and every reconnect queries the
 * saved move id; a start is never repeated.
 */
class MigrationActivity : Activity(), ShizukuManager.Callback {

    private lateinit var manager: ShizukuManager

    private lateinit var root: View
    private lateinit var textCard: TextView
    private lateinit var textState: TextView
    private lateinit var progressMigration: ProgressBar
    private lateinit var textPrimaryLocation: TextView
    private lateinit var buttonPrimary: Button
    private lateinit var textPrimaryNote: TextView
    private lateinit var textAppsEmpty: TextView
    private lateinit var appList: LinearLayout
    private lateinit var buttonRefresh: Button
    private lateinit var switchLogs: Switch
    private lateinit var logPanel: ScrollView
    private lateinit var textLog: TextView

    private var diskId: String? = null
    private var overview: ShizukuManager.MigrationOverview? = null
    private var handle: MigrationHandle? = null
    private var currentMoveId: Int? = null
    private var lastProgress: Int? = null
    private var loading = false
    private var loadFailed = false
    private var preparing = false
    private var starting = false
    private var overviewGeneration = 0
    private var pendingPrepared: ShizukuManager.PreparedMigration? = null
    private var lastStartedToken: String? = null
    private var activeDialog: AlertDialog? = null
    private var terminalText: String? = null
    private var terminalColor = R.color.color_ok

    private val localLogs = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // --- lifecycle -----------------------------------------------------------

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ShizukuManager.get(this)
        diskId = intent?.getStringExtra(EXTRA_DISK_ID)
            ?.takeIf { it.isNotBlank() }
            ?: AppSettings.migrationHandle(this)?.diskId
        setContentView(R.layout.activity_migration)
        bindViews()
        applyInsets()
        setupLogs()
        handle = AppSettings.migrationHandle(this)
        currentMoveId = handle?.moveId
        render()
    }

    override fun onStart() {
        super.onStart()
        manager.addCallback(this)
        manager.start()
    }

    override fun onStop() {
        // Only this presentation callback goes away. Polling is app-scoped and
        // must keep observing a running move; the manager itself stays owned by
        // MainActivity, so onActivityDestroyed() is deliberately never called.
        manager.removeCallback(this)
        super.onStop()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        super.onDestroy()
    }

    private fun bindViews() {
        root = findViewById(R.id.migration_root)
        textCard = findViewById(R.id.text_migration_card)
        textState = findViewById(R.id.text_migration_state)
        progressMigration = findViewById(R.id.progress_migration)
        textPrimaryLocation = findViewById(R.id.text_migration_primary_location)
        buttonPrimary = findViewById(R.id.button_migration_primary)
        textPrimaryNote = findViewById(R.id.text_migration_primary_note)
        textAppsEmpty = findViewById(R.id.text_migration_apps_empty)
        appList = findViewById(R.id.migration_app_list)
        buttonRefresh = findViewById(R.id.button_migration_refresh)
        switchLogs = findViewById(R.id.switch_migration_logs)
        logPanel = findViewById(R.id.log_panel_migration)
        textLog = findViewById(R.id.text_migration_log)

        buttonRefresh.setOnClickListener { requestOverview() }
        buttonPrimary.setOnClickListener { preparePrimary() }
        textPrimaryNote.setText(R.string.migration_primary_note)
    }

    /**
     * Keeps the title and every control clear of system bars / cutouts on
     * edge-to-edge, matching the main screen. The root's own padding is
     * captured once, so a repeated inset dispatch or a configuration change
     * re-applies the bar sizes instead of accumulating them.
     */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.setDecorFitsSystemWindows(false)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            insets
        }
    }

    private fun setupLogs() {
        switchLogs.text = getString(R.string.migration_logs_label)
        switchLogs.isChecked = AppSettings.showLogs(this)
        applyLogVisibility(switchLogs.isChecked)
        switchLogs.setOnCheckedChangeListener { _, checked ->
            AppSettings.setShowLogs(this, checked)
            applyLogVisibility(checked)
        }
        renderLogs()
    }

    private fun applyLogVisibility(show: Boolean) {
        logPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    // --- Shizuku callbacks ---------------------------------------------------

    override fun onState(state: ShizukuManager.State, identity: String?) {
        when (state) {
            ShizukuManager.State.READY -> {
                requestOverview()
                resumeSavedPolling()
            }
            ShizukuManager.State.BINDER_LOST -> {
                setStateText(R.string.migration_state_not_ready, R.color.color_warning)
                render()
            }
            else -> render()
        }
    }

    override fun onStatus(status: ShizukuManager.OperationStatus) = Unit

    override fun onMigrationStatus(
        status: ShizukuManager.MigrationStatus?,
        error: ShizukuManager.IpcError?,
    ) {
        if (status != null) {
            val expected = currentMoveId ?: handle?.moveId
            if (expected != null && status.moveId != expected) return
            when (status.state) {
                ShizukuManager.MIGRATION_RUNNING -> adoptRunning(status)
                ShizukuManager.MIGRATION_COMPLETED -> verifyCompletedMove()
                ShizukuManager.MIGRATION_FAILED -> markFailed(status.error ?: "failed_native")
            }
            return
        }
        if (error == null) return
        val h = handle ?: return
        if (h.state != STATE_STARTING && h.state != ShizukuManager.MIGRATION_RUNNING) return
        when {
            error.code == ERR_DESTINATION_UNVERIFIED -> verifySavedDestination()
            error.code == ERR_NOT_READY -> {
                setStateText(R.string.migration_state_not_ready, R.color.color_warning)
                render()
            }
            // A transport/permission/read failure says nothing about the native
            // move: keep the saved handle and present it as unverified instead
            // of claiming a native failure or dropping the busy state.
            error.code in TRANSIENT_ERRORS -> {
                pushLog(LEVEL_WARN, error.code, error.detail)
                markUnverified(cardChanged = false)
            }
            else -> markFailed(error.code)
        }
    }

    // --- overview ------------------------------------------------------------

    private fun requestOverview() {
        if (manager.currentState() != ShizukuManager.State.READY) {
            setStateText(R.string.migration_state_not_ready, R.color.color_warning)
            render()
            return
        }
        val id = diskId ?: run {
            loadFailed = true
            render()
            return
        }
        loading = true
        loadFailed = false
        render()
        val generation = ++overviewGeneration
        manager.requestMigrationOverview(id) { info, error ->
            if (generation != overviewGeneration) return@requestMigrationOverview
            loading = false
            if (error != null || info == null) {
                loadFailed = true
                error?.let { pushLog(LEVEL_ERROR, it.code, it.detail) }
                render()
                return@requestMigrationOverview
            }
            applyOverview(info)
        }
    }

    private fun applyOverview(info: ShizukuManager.MigrationOverview) {
        overview = info
        val saved = handle
        if (saved != null && saved.diskId == info.diskId && isRecoverable(saved)) {
            // Only a retained record that describes this exact request may
            // decide the outcome; a fast completion before the relaunch is
            // verified from the destination, never restarted.
            val active = info.activeMigration?.takeIf { it.moveId > 0 && activeMatches(saved, it) }
            when {
                active == null -> keepUnresolved(saved)
                !cardMatches(saved, info) -> if (saved.state != STATE_UNVERIFIED) {
                    markUnverified(cardChanged = true)
                }
                active.state == ShizukuManager.MIGRATION_COMPLETED -> {
                    adoptHandle(saved.copy(moveId = active.moveId))
                    verifySavedDestination()
                }
                active.state == ShizukuManager.MIGRATION_FAILED -> {
                    adoptHandle(saved.copy(moveId = active.moveId))
                    markFailed(active.error ?: "failed_native")
                }
                else -> {
                    adoptHandle(
                        saved.copy(moveId = active.moveId, state = ShizukuManager.MIGRATION_RUNNING),
                    )
                    manager.startMigrationPolling(active.moveId)
                }
            }
        } else if (saved == null) {
            val active = info.activeMigration
            if (active != null && active.moveId > 0 &&
                active.state == ShizukuManager.MIGRATION_RUNNING
            ) {
                // A retained native move after a relaunch: adopt, never restart.
                val restored = MigrationHandle(
                    moveId = active.moveId,
                    diskId = info.diskId,
                    kind = active.kind ?: ShizukuManager.KIND_PRIMARY,
                    targetUuid = active.targetUuid ?: UUID_INTERNAL,
                    packageName = active.packageName,
                    sourceUuid = "",
                    privateUuid = info.selectedPrivateUuid,
                    state = ShizukuManager.MIGRATION_RUNNING,
                )
                adoptHandle(restored)
                manager.startMigrationPolling(active.moveId)
            }
        }
        resumeSavedPolling()
        render()
    }

    /** States whose outcome a fresh overview may still resolve. */
    private fun isRecoverable(handle: MigrationHandle): Boolean =
        handle.state == STATE_STARTING ||
            handle.state == ShizukuManager.MIGRATION_RUNNING ||
            handle.state == STATE_UNVERIFIED

    /**
     * No retained record describes this request: never repeat the start and
     * never invent an outcome. An already unverified handle is left untouched
     * so a refresh does not rewrite it on every read.
     */
    private fun keepUnresolved(saved: MigrationHandle) {
        when (saved.state) {
            STATE_STARTING -> {
                handle = saved.copy(state = STATE_UNVERIFIED)
                AppSettings.setMigrationHandle(this, handle)
                terminalText = null
                setStateText(R.string.migration_status_pending, R.color.color_warning)
                pushLog(LEVEL_WARN, "migration_start_unverified", null)
            }
            ShizukuManager.MIGRATION_RUNNING -> markUnverified(cardChanged = false)
            else -> Unit
        }
    }

    private fun resumeSavedPolling() {
        val h = handle ?: return
        if (h.diskId != diskId) return
        if (h.moveId != null &&
            (h.state == ShizukuManager.MIGRATION_RUNNING || h.state == STATE_UNVERIFIED)
        ) {
            currentMoveId = h.moveId
            manager.startMigrationPolling(h.moveId)
        }
    }

    private fun adoptHandle(next: MigrationHandle) {
        handle = next
        currentMoveId = next.moveId
        AppSettings.setMigrationHandle(this, next)
    }

    private fun adoptRunning(status: ShizukuManager.MigrationStatus) {
        val h = handle
        if (h == null) {
            val info = overview ?: return
            val restored = MigrationHandle(
                moveId = status.moveId,
                diskId = info.diskId,
                kind = status.kind ?: ShizukuManager.KIND_PRIMARY,
                targetUuid = status.targetUuid ?: UUID_INTERNAL,
                packageName = status.packageName,
                sourceUuid = "",
                privateUuid = info.selectedPrivateUuid,
                state = ShizukuManager.MIGRATION_RUNNING,
            )
            adoptHandle(restored)
        } else if (h.moveId != status.moveId || h.state != ShizukuManager.MIGRATION_RUNNING) {
            adoptHandle(h.copy(moveId = status.moveId, state = ShizukuManager.MIGRATION_RUNNING))
        }
        starting = false
        lastProgress = status.progress
        terminalText = null
        setKeepScreenOn(true)
        render()
    }

    private fun markFailed(code: String) {
        starting = false
        val h = handle ?: return
        handle = h.copy(state = ShizukuManager.MIGRATION_FAILED, errorCode = code)
        AppSettings.setMigrationHandle(this, handle)
        // Only a native terminal failure reaches this path, so the busy state
        // may be released here.
        manager.markMigrationIdle()
        lastProgress = null
        terminalText = null
        setKeepScreenOn(false)
        pushLog(LEVEL_ERROR, code, null)
        setStateText(getString(R.string.migration_status_failed, migrationErrorText(code)), R.color.color_danger)
        render()
    }

    private fun verifyCompletedMove() {
        val h = handle ?: return
        val info = overview
        if (info != null && !cardMatches(h, info)) {
            markUnverified(cardChanged = true)
            return
        }
        // A fresh read protects a swapped card from inheriting an old success.
        manager.requestMigrationOverview(h.diskId) { fresh, error ->
            if (fresh == null || error != null) {
                markUnverified(cardChanged = false)
                return@requestMigrationOverview
            }
            if (!cardMatches(h, fresh)) {
                markUnverified(cardChanged = true)
                return@requestMigrationOverview
            }
            overview = fresh
            markCompletedVerified()
        }
    }

    private fun verifySavedDestination() {
        val h = handle ?: return
        setStateText(R.string.migration_status_checking, R.color.color_muted)
        manager.requestMigrationOverview(h.diskId) { info, error ->
            if (info == null || error != null) {
                markUnverified(cardChanged = false)
                return@requestMigrationOverview
            }
            overview = info
            if (!cardMatches(h, info)) {
                markUnverified(cardChanged = true)
                return@requestMigrationOverview
            }
            val verified = if (h.kind == ShizukuManager.KIND_PRIMARY) {
                destinationMatches(info.primaryUuid, h.targetUuid)
            } else {
                val pkg = h.packageName
                val storage = pkg?.let { appStorageUuid(it) }
                storage != null && destinationMatches(storage, h.targetUuid)
            }
            if (verified) {
                markCompletedVerified()
            } else {
                markUnverified(cardChanged = false)
            }
        }
    }

    private fun markCompletedVerified() {
        handle = null
        currentMoveId = null
        lastProgress = null
        starting = false
        pendingPrepared = null
        AppSettings.clearMigrationHandle(this)
        manager.markMigrationIdle()
        terminalText = getString(R.string.migration_status_completed)
        terminalColor = R.color.color_ok
        setKeepScreenOn(false)
        pushLog(LEVEL_INFO, "migration_completed", null)
        render()
        requestOverview()
    }

    private fun markUnverified(cardChanged: Boolean) {
        val h = handle ?: return
        handle = h.copy(state = STATE_UNVERIFIED)
        AppSettings.setMigrationHandle(this, handle)
        lastProgress = null
        starting = false
        terminalText = null
        setKeepScreenOn(false)
        val message = if (cardChanged) {
            R.string.migration_status_card_changed
        } else {
            R.string.migration_status_unverified
        }
        pushLog(LEVEL_WARN, if (cardChanged) "card_changed" else "destination_unverified", null)
        setStateText(message, R.color.color_warning)
        render()
    }

    // --- prepare / start -----------------------------------------------------

    private fun preparePrimary() {
        val info = overview ?: return
        if (isBusy() || preparing) return
        val target = if (info.primaryOnThisDisk) {
            UUID_INTERNAL
        } else {
            info.selectedPrivateUuid ?: return
        }
        prepare(
            ShizukuManager.MigrationRequest(
                kind = ShizukuManager.KIND_PRIMARY,
                diskId = info.diskId,
                targetUuid = target,
            ),
        )
    }

    private fun prepareApp(app: ShizukuManager.MigrationApp) {
        val info = overview ?: return
        if (isBusy() || preparing) return
        val target = if (app.onThisCard) {
            UUID_INTERNAL
        } else {
            info.selectedPrivateUuid ?: return
        }
        prepare(
            ShizukuManager.MigrationRequest(
                kind = ShizukuManager.KIND_APP,
                diskId = info.diskId,
                targetUuid = target,
                packageName = app.packageName,
            ),
        )
    }

    private fun prepare(request: ShizukuManager.MigrationRequest) {
        preparing = true
        terminalText = null
        render()
        manager.prepareMigration(request) { prepared, error ->
            preparing = false
            if (error != null || prepared == null) {
                if (error != null) {
                    pushLog(LEVEL_ERROR, error.code, error.detail)
                    showErrorDialog(error.code)
                }
                render()
                return@prepareMigration
            }
            pendingPrepared = prepared
            render()
            showConfirm(prepared)
        }
    }

    private fun showConfirm(prepared: ShizukuManager.PreparedMigration) {
        val source = locationLabel(prepared.sourceUuid)
        val target = locationLabel(prepared.targetUuid)
        val message = if (prepared.kind == ShizukuManager.KIND_PRIMARY) {
            getString(R.string.migration_confirm_primary, source, target)
        } else {
            val pkg = prepared.packageName.orEmpty()
            getString(R.string.migration_confirm_app, appLabel(pkg), pkg, source, target)
        }
        showDialog(
            title = getString(R.string.migration_confirm_title),
            message = message,
            positive = getString(R.string.migration_confirm_start),
            negative = getString(R.string.action_cancel),
            onPositive = { startConfirmed(prepared) },
            onNegative = {
                pendingPrepared = null
                pushLog(LEVEL_INFO, "migration_cancelled", null)
                render()
            },
        )
    }

    private fun startConfirmed(prepared: ShizukuManager.PreparedMigration) {
        if (starting || lastStartedToken == prepared.token) return
        lastStartedToken = prepared.token
        pendingPrepared = null
        starting = true
        terminalText = null
        lastProgress = null
        val saved = MigrationHandle(
            moveId = null,
            diskId = prepared.diskId.ifBlank { diskId.orEmpty() },
            kind = prepared.kind,
            targetUuid = prepared.targetUuid,
            packageName = prepared.packageName,
            sourceUuid = prepared.sourceUuid,
            privateUuid = prepared.selectedPrivateUuid ?: overview?.selectedPrivateUuid,
            state = STATE_STARTING,
        )
        handle = saved
        currentMoveId = null
        // Persist before the IPC call: a rotation/relaunch can then find the
        // request, and the token is never replayed.
        AppSettings.setMigrationHandle(this, saved)
        render()
        manager.startMigration(prepared.token) { status, error ->
            starting = false
            if (error != null) {
                pushLog(LEVEL_ERROR, error.code, error.detail)
                if (error.code == ERR_NOT_READY || isPreStartError(error.code)) {
                    handle = null
                    currentMoveId = null
                    AppSettings.clearMigrationHandle(this)
                    // Definitive refusal before any native move: release the
                    // manager's busy state too.
                    manager.markMigrationIdle()
                    lastStartedToken = null
                    lastProgress = null
                    terminalText = migrationErrorText(error.code)
                    terminalColor = R.color.color_danger
                    showErrorDialog(error.code)
                    render()
                } else {
                    // The start answer is not a clear pre-start refusal: keep
                    // the request and recover a retained active move instead of
                    // guessing success or repeating the start.
                    terminalText = getString(R.string.migration_status_pending)
                    terminalColor = R.color.color_warning
                    showErrorDialog(error.code)
                    render()
                    requestOverview()
                }
                return@startMigration
            }
            if (status == null) {
                terminalText = getString(R.string.migration_status_pending)
                terminalColor = R.color.color_warning
                render()
                requestOverview()
                return@startMigration
            }
            val running = saved.copy(moveId = status.moveId, state = status.state)
            adoptHandle(running)
            lastProgress = status.progress
            setKeepScreenOn(true)
            manager.startMigrationPolling(status.moveId)
            render()
        }
    }

    // --- rendering -----------------------------------------------------------

    private fun render() {
        if (isFinishing || isDestroyed) return
        val info = overview
        val busy = isBusy()
        val ready = manager.currentState() == ShizukuManager.State.READY

        textCard.text = cardLine(info)
        renderStatus(info)
        renderPrimary(info, busy)
        renderApps(info, busy)
        // Refresh is read-only, so an unresolved (busy) move keeps it usable as
        // the retry path; only a local request in flight disables it.
        buttonRefresh.isEnabled = ready && !loading && !preparing && !starting
    }

    private fun renderStatus(info: ShizukuManager.MigrationOverview?) {
        val h = handle?.takeIf { it.diskId == diskId }
        when {
            terminalText != null -> {
                textState.text = terminalText
                textState.setTextColor(getColor(terminalColor))
            }
            manager.currentState() != ShizukuManager.State.READY -> {
                textState.setText(R.string.migration_state_not_ready)
                textState.setTextColor(getColor(R.color.color_warning))
            }
            h?.state == STATE_STARTING -> {
                textState.setText(R.string.migration_status_starting)
                textState.setTextColor(getColor(R.color.color_accent))
            }
            h?.state == ShizukuManager.MIGRATION_RUNNING -> {
                val progress = lastProgress
                textState.text = if (progress != null) {
                    getString(R.string.migration_status_progress, progress)
                } else {
                    getString(R.string.migration_status_progress_unknown)
                }
                textState.setTextColor(getColor(R.color.color_accent))
            }
            h?.state == ShizukuManager.MIGRATION_FAILED -> {
                textState.text = getString(
                    R.string.migration_status_failed,
                    migrationErrorText(h.errorCode),
                )
                textState.setTextColor(getColor(R.color.color_danger))
            }
            h?.state == STATE_UNVERIFIED -> {
                textState.setText(R.string.migration_status_unverified)
                textState.setTextColor(getColor(R.color.color_warning))
            }
            loading -> {
                textState.setText(R.string.migration_state_loading)
                textState.setTextColor(getColor(R.color.color_muted))
            }
            loadFailed -> {
                textState.setText(R.string.migration_state_load_failed)
                textState.setTextColor(getColor(R.color.color_danger))
            }
            info != null && !hasPrivateVolume(info) -> {
                textState.setText(R.string.migration_state_unavailable)
                textState.setTextColor(getColor(R.color.color_warning))
            }
            handle != null && handle?.diskId != diskId -> {
                textState.setText(R.string.migration_status_card_changed)
                textState.setTextColor(getColor(R.color.color_warning))
            }
            else -> textState.text = ""
        }
        setStateTextVisible(textState.text?.isNotEmpty() == true)

        val running = h?.state == ShizukuManager.MIGRATION_RUNNING &&
            manager.currentState() == ShizukuManager.State.READY
        val startingNow = h?.state == STATE_STARTING &&
            manager.currentState() == ShizukuManager.State.READY
        when {
            running && lastProgress != null -> {
                progressMigration.visibility = View.VISIBLE
                progressMigration.isIndeterminate = false
                progressMigration.progress = lastProgress!!.coerceIn(0, 100)
            }
            running || startingNow -> {
                progressMigration.visibility = View.VISIBLE
                progressMigration.isIndeterminate = true
            }
            else -> progressMigration.visibility = View.GONE
        }
    }

    private fun renderPrimary(info: ShizukuManager.MigrationOverview?, busy: Boolean) {
        if (info == null) {
            textPrimaryLocation.text = ""
            buttonPrimary.isEnabled = false
            buttonPrimary.setText(R.string.migration_primary_to_card)
            return
        }
        val internalPrimary = isInternalUuid(info.primaryUuid)
        val location = when {
            internalPrimary -> R.string.migration_app_location_internal
            info.primaryOnThisDisk -> R.string.migration_app_location_card
            else -> R.string.migration_app_location_other
        }
        textPrimaryLocation.setText(location)
        buttonPrimary.setText(
            if (info.primaryOnThisDisk) {
                R.string.migration_primary_to_internal
            } else {
                R.string.migration_primary_to_card
            },
        )
        val canMoveToCard = !info.primaryOnThisDisk && internalPrimary &&
            info.destinationAvailable && info.selectedPrivateUuid != null
        val canMove = !busy && !preparing && hasPrivateVolume(info) &&
            info.destinationAvailable && (info.primaryOnThisDisk || canMoveToCard)
        buttonPrimary.isEnabled = canMove
        textPrimaryNote.setText(
            if (info.destinationAvailable) {
                R.string.migration_primary_note
            } else {
                R.string.migration_primary_unavailable
            },
        )
    }

    private fun renderApps(info: ShizukuManager.MigrationOverview?, busy: Boolean) {
        appList.removeAllViews()
        if (info == null) {
            textAppsEmpty.visibility = View.GONE
            return
        }
        if (info.appsError != null) {
            textAppsEmpty.visibility = View.VISIBLE
            textAppsEmpty.setText(R.string.migration_apps_error)
            return
        }
        if (info.apps.isEmpty()) {
            textAppsEmpty.visibility = View.VISIBLE
            textAppsEmpty.setText(R.string.migration_apps_empty)
            return
        }
        textAppsEmpty.visibility = View.GONE
        val canUseCard = info.destinationAvailable && info.selectedPrivateUuid != null
        for (app in info.apps) {
            appList.addView(appRow(app, busy, canUseCard))
        }
        if (info.appsTruncated) {
            val note = TextView(this).apply {
                text = getString(R.string.migration_apps_truncated, info.apps.size)
                setTextColor(getColor(R.color.color_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, 0)
            }
            appList.addView(note)
        }
    }

    private fun appRow(
        app: ShizukuManager.MigrationApp,
        busy: Boolean,
        canUseCard: Boolean,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val title = TextView(this).apply {
            text = appLabel(app.packageName)
            setTextColor(getColor(R.color.color_on_bg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val location = when {
            app.onThisCard -> R.string.migration_app_location_card
            isInternalUuid(app.sourceUuid) -> R.string.migration_app_location_internal
            else -> R.string.migration_app_location_other
        }
        val detail = TextView(this).apply {
            text = app.packageName + " · " + getString(location)
            setTextColor(getColor(R.color.color_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        }
        val toCard = !app.onThisCard && app.eligibleForSd && canUseCard
        val toInternal = app.onThisCard && app.eligibleForInternal
        val button = Button(this, null, 0, R.style.ArmadaButtonSecondary).apply {
            isAllCaps = false
            minHeight = dp(48)
            setText(
                when {
                    toCard -> R.string.migration_app_destination_card
                    toInternal -> R.string.migration_app_destination_internal
                    else -> R.string.migration_app_not_movable
                },
            )
            isEnabled = !busy && !preparing && (toCard || toInternal)
            setOnClickListener {
                if (toCard || toInternal) prepareApp(app)
            }
        }
        row.addView(title)
        row.addView(detail)
        if (!toCard && !toInternal) {
            val reason = TextView(this).apply {
                text = notMovableReason(app)
                setTextColor(getColor(R.color.color_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(reason)
        }
        row.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        row.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.color_outline)) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ),
        )
        return row
    }

    private fun notMovableReason(app: ShizukuManager.MigrationApp): String = when {
        app.system || app.updatedSystem -> getString(R.string.migration_app_system)
        app.deviceAdmin == true -> getString(R.string.migration_app_device_admin)
        else -> getString(R.string.migration_app_not_movable)
    }

    private fun cardLine(info: ShizukuManager.MigrationOverview?): String {
        if (info == null) return ""
        val volume = info.volumes.firstOrNull { it.type == VOLUME_PRIVATE }
            ?: info.volumes.firstOrNull()
        val capacity = volume?.capacityBytes
        val available = volume?.availableBytes
        return when {
            capacity == null -> getString(R.string.migration_card_line_unknown)
            available == null -> getString(
                R.string.migration_card_line,
                formatBytes(capacity),
                getString(R.string.migration_free_unknown),
            )
            else -> getString(
                R.string.migration_card_line,
                formatBytes(capacity),
                formatBytes(available),
            )
        }
    }

    private fun isBusy(): Boolean = manager.currentState() != ShizukuManager.State.READY ||
        starting || preparing || handle?.state == STATE_STARTING ||
        handle?.state == ShizukuManager.MIGRATION_RUNNING || manager.migrationActive

    private fun setStateText(resId: Int, colorRes: Int) {
        setStateText(getString(resId), colorRes)
    }

    private fun setStateText(value: String, colorRes: Int) {
        if (isFinishing || isDestroyed) return
        textState.text = value
        textState.setTextColor(getColor(colorRes))
        setStateTextVisible(value.isNotEmpty())
    }

    private fun setStateTextVisible(visible: Boolean) {
        textState.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun hasPrivateVolume(info: ShizukuManager.MigrationOverview): Boolean =
        info.selectedPrivateUuid != null &&
            (info.layout == LiveCardInfo.LAYOUT_ANDROID_ONLY || info.layout == LiveCardInfo.LAYOUT_MIXED)

    // --- confirmation / errors ----------------------------------------------

    private fun isPreStartError(code: String): Boolean = code in PRE_START_ERRORS

    private fun migrationErrorText(code: String?): String = when (code) {
        "insufficient_storage" -> getString(R.string.migration_err_insufficient_storage)
        "package_missing", "unknown_package" -> getString(R.string.migration_err_package_missing)
        "system_package" -> getString(R.string.migration_err_system_package)
        "invalid_location" -> getString(R.string.migration_err_invalid_location)
        "operation_pending", "move_in_progress", "copy_finished" ->
            getString(R.string.migration_err_operation_pending)
        "device_admin" -> getString(R.string.migration_err_device_admin)
        "third_party_not_allowed_internal" -> getString(R.string.migration_err_not_allowed)
        "locked_user" -> getString(R.string.migration_err_locked_user)
        "failed_native" -> getString(R.string.migration_err_failed_native)
        "destination_mismatch" -> getString(R.string.migration_err_destination_mismatch)
        "destination_unverified" -> getString(R.string.migration_status_unverified)
        "source_changed" -> getString(R.string.migration_err_source_changed)
        "target_not_mounted", "target_missing" -> getString(R.string.migration_err_target_not_mounted)
        "card_ineligible", "card_layout_unsupported", "card_changed", "card_missing" ->
            getString(R.string.migration_err_card)
        "invalid_target", "invalid_source", "already_at_target" ->
            getString(R.string.migration_err_invalid_direction)
        "package_not_eligible", "protected_package" ->
            getString(R.string.migration_err_package_not_eligible)
        "app_query_unavailable" -> getString(R.string.migration_err_app_query)
        "no_migration_plan", "move_start_failed" -> getString(R.string.migration_err_start_failed)
        "native_migration_unavailable", "permission_denied" ->
            getString(R.string.migration_err_unavailable)
        "service_not_ready" -> getString(R.string.migration_state_not_ready)
        "busy" -> getString(R.string.migration_err_busy)
        else -> getString(R.string.migration_err_generic)
    }

    private fun showErrorDialog(code: String?) {
        showDialog(
            title = getString(R.string.error_title),
            message = migrationErrorText(code),
            positive = getString(R.string.action_close),
        )
    }

    private fun showDialog(
        title: String,
        message: String,
        positive: String,
        negative: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(positive) { dialog, _ ->
                dialog.dismiss()
                onPositive?.invoke()
            }
        if (negative != null) {
            builder.setNegativeButton(negative) { dialog, _ ->
                dialog.dismiss()
                onNegative?.invoke()
            }
        }
        builder.setOnCancelListener {
            if (negative != null) {
                pendingPrepared = null
                pushLog(LEVEL_INFO, "migration_cancelled", null)
                render()
            }
        }
        val dialog = builder.create()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    // --- labels / verification ----------------------------------------------

    private fun locationLabel(uuid: String?): String = when {
        uuid.isNullOrBlank() -> getString(R.string.migration_target_unknown)
        isInternalUuid(uuid) -> getString(R.string.migration_target_internal)
        overview?.selectedPrivateUuid?.let { uuidEquals(it, uuid) } == true ->
            getString(R.string.migration_target_card)
        else -> getString(R.string.migration_target_other)
    }

    /**
     * Binds the saved request to the live card in both directions: the disk id
     * and the saved private-volume identity must both match. An internal
     * destination does not skip the private-UUID check, because the card can
     * still be swapped or re-allocated underneath the saved handle.
     */
    private fun cardMatches(
        handle: MigrationHandle,
        info: ShizukuManager.MigrationOverview,
    ): Boolean {
        if (handle.diskId != info.diskId) return false
        val expected = handle.privateUuid ?: return false
        val live = info.selectedPrivateUuid ?: return false
        return uuidEquals(expected, live)
    }

    private fun activeMatches(
        handle: MigrationHandle,
        active: ShizukuManager.MigrationStatus,
    ): Boolean {
        if (handle.kind != (active.kind ?: handle.kind)) return false
        if (handle.packageName != active.packageName) return false
        val target = active.targetUuid ?: return false
        return uuidEquals(handle.targetUuid, target) || destinationMatches(target, handle.targetUuid)
    }

    private fun destinationMatches(actual: String?, expected: String): Boolean {
        if (isInternalUuid(expected)) return isInternalUuid(actual)
        if (actual.isNullOrBlank()) return false
        return uuidEquals(actual, expected)
    }

    /**
     * Actual destination identity of [packageName] for a saved app move: the
     * default (all-zero) storage UUID and a missing value both mean internal
     * storage, so an internal target verifies either way; only a real
     * per-volume UUID is treated as an external identity. An unavailable
     * package has no verifiable destination.
     */
    private fun appStorageUuid(packageName: String): String? = try {
        val info: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
        val uuid: UUID? = info.storageUuid
        when {
            uuid == null -> UUID_INTERNAL
            uuid == StorageManager.UUID_DEFAULT -> UUID_INTERNAL
            else -> uuid.toString()
        }
    } catch (t: Exception) {
        null
    }

    private fun appLabel(packageName: String): String {
        if (packageName.isBlank()) return getString(R.string.migration_target_unknown)
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString().takeIf {
                !TextUtils.isEmpty(it)
            } ?: packageName
        } catch (t: Exception) {
            packageName
        }
    }

    private fun isInternalUuid(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        return value.equals("internal", ignoreCase = true) ||
            value.equals("primary", ignoreCase = true) ||
            value.equals("private", ignoreCase = true)
    }

    private fun uuidEquals(left: String, right: String): Boolean =
        uuidKey(left) == uuidKey(right)

    private fun uuidKey(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace("-", "")

    // --- logs / helpers ------------------------------------------------------

    private fun pushLog(level: String, code: String, detail: String?) {
        val builder = StringBuilder()
            .append(timeFormat.format(Date()))
            .append(' ')
            .append(level)
            .append(' ')
            .append(code)
        detail?.let { builder.append(" — ").append(it) }
        localLogs.addLast(builder.toString())
        while (localLogs.size > MAX_LOG_LINES) localLogs.removeFirst()
        renderLogs()
    }

    private fun renderLogs() {
        if (localLogs.isEmpty()) {
            textLog.setText(R.string.migration_logs_empty)
            return
        }
        textLog.text = localLogs.joinToString("\n")
        logPanel.post { logPanel.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.size - 1) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "$bytes ${units[0]}"
        } else {
            String.format(Locale.getDefault(), "%.1f %s", value, units[index])
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val EXTRA_DISK_ID = "disk_id"
        const val UUID_INTERNAL = "internal"
        const val VOLUME_PRIVATE = "private"
        const val STATE_STARTING = "starting"
        const val STATE_UNVERIFIED = "unverified"
        const val ERR_NOT_READY = "service_not_ready"
        const val ERR_DESTINATION_UNVERIFIED = "destination_unverified"
        const val LEVEL_INFO = "INFO"
        const val LEVEL_WARN = "WARN"
        const val LEVEL_ERROR = "ERROR"
        const val MAX_LOG_LINES = 200

        /** Errors that only report a failed call, not a failed native move. */
        val TRANSIENT_ERRORS = setOf(
            "internal_error",
            "permission_denied",
        )

        /** Errors known to have stopped before any native move was issued. */
        val PRE_START_ERRORS = setOf(
            "busy",
            "invalid_request",
            "not_confirmed",
            "invalid_token",
            "no_migration_plan",
            "move_start_failed",
            "card_missing",
            "card_changed",
            "card_ineligible",
            "card_layout_unsupported",
            "permission_denied",
            "native_migration_unavailable",
            "target_missing",
            "target_not_mounted",
            "already_at_target",
            "invalid_target",
            "invalid_source",
            "source_changed",
            "package_missing",
            "package_not_eligible",
            "protected_package",
            "move_back_required",
            "app_query_unavailable",
        )
    }
}
