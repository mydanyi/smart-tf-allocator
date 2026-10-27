package io.github.mydanyi.armadatf.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.mydanyi.armadatf.BuildConfig
import io.github.mydanyi.armadatf.R
import io.github.mydanyi.armadatf.domain.AllocationMode
import io.github.mydanyi.armadatf.domain.AllocationPolicy
import io.github.mydanyi.armadatf.storage.HandoffException
import io.github.mydanyi.armadatf.storage.HandoffWriter
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Native, handheld-friendly screen for the whole flow: pick the card, choose
 * the Android/Linux split, review, explicitly confirm the full-card erase, run
 * the privileged partition through Shizuku and finally publish the Linux
 * handoff from the main app.
 *
 * All binder work is delegated to the process-wide [ShizukuManager]; this
 * Activity only renders state and runs the pure-Java [HandoffWriter]. It never
 * starts a partition operation on its own: a run lives in the Shizuku service
 * process and is only ever recovered, never restarted, when the UI reconnects.
 */
class MainActivity : Activity(), ShizukuManager.Callback {

    private lateinit var manager: ShizukuManager

    // Wide landscape root is a fixed FrameLayout; base portrait root is a
    // ScrollView. Only findViewById/insets use it here, so ViewGroup suffices.
    private lateinit var root: ViewGroup
    private lateinit var workspacePanels: LinearLayout
    private lateinit var cardPanel: LinearLayout
    private lateinit var allocationPanel: LinearLayout
    private lateinit var allocationEditor: LinearLayout
    private lateinit var allocationSummaryGroup: LinearLayout
    private lateinit var textInspectionState: TextView
    private lateinit var headerTextGroup: LinearLayout
    private lateinit var versionText: TextView
    private lateinit var shizukuStateText: TextView
    private lateinit var buttonAuthorize: Button
    private lateinit var buttonOpenShizuku: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var cardSpinner: Spinner
    private lateinit var buttonRefresh: Button
    private lateinit var textAndroidPercent: TextView
    private lateinit var textLinuxPercent: TextView
    private lateinit var textAndroidCapacity: TextView
    private lateinit var textLinuxCapacity: TextView
    private lateinit var seekAllocation: SeekBar
    private lateinit var textCapacity: TextView
    private lateinit var buttonReview: Button
    private lateinit var textSummaryTotal: TextView
    private lateinit var textSummaryAndroid: TextView
    private lateinit var textSummaryLinux: TextView
    private lateinit var textSummaryMoveBack: TextView
    private lateinit var buttonManageMigration: Button
    private lateinit var buttonReallocate: Button
    private lateinit var buttonCancelReallocate: Button
    private lateinit var textStatus: TextView
    private lateinit var handoffGroup: LinearLayout
    private lateinit var textHandoffTitle: TextView
    private lateinit var textHandoffMessage: TextView
    private lateinit var buttonRetryHandoff: Button
    private lateinit var switchShowLogs: Switch
    private lateinit var logPanel: ScrollView
    private lateinit var textLog: TextView
    private lateinit var buttonCopyLogs: Button
    private var logDrawer: View? = null
    private var buttonCloseLogs: Button? = null
    private var shizukuStatusDot: View? = null
    // Landscape-only scroll container for the allocation content; null in the
    // base portrait layout, where the whole page scrolls instead.
    private var allocationScroll: ScrollView? = null
    private lateinit var textFooterPrefix: TextView
    private lateinit var textFooterName: TextView
    private lateinit var textFooterSuffix: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val handoffExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "armada-tf-handoff").apply { isDaemon = true }
    }

    private var cards: List<ShizukuManager.CardInfo> = emptyList()
    private var spinnerCards: List<ShizukuManager.CardInfo> = emptyList()
    private var selectedCard: ShizukuManager.CardInfo? = null
    private var liveCardInfo: LiveCardInfo? = null
    private var allocationSummary: AllocationSummary? = null
    // True while the user explicitly chose the editor for an already allocated
    // card. Only an explicit action (Reallocate), Cancel or a freshly verified
    // partition changes it, so repeated old status callbacks can never close it.
    private var editorChosenByUser = false
    private var summaryInfoGeneration = 0
    private var applyingCardSelection = false
    private var pendingRestoreCardId: String? = null
    private var pendingRestoreSeek: Int? = null
    // A successful live inspection of the selected card; reallocation must not
    // rely on an older snapshot after a failed refresh.
    private var inspectionFresh = false
    private var inspectionFailed = false
    private var awaitingPartitionResult = false
    private var operationCardId: String? = null
    private var operationMode: AllocationMode? = null
    private var lastPartitionCompletion = AllocationCompletion.NONE
    private var lastPartitionLinuxAllocated = false
    private var lastLogs: List<ShizukuManager.LogEntry> = emptyList()
    private val localLogs = ArrayDeque<ShizukuManager.LogEntry>()

    private var operationBusy = false
    private var handoffWriting = false
    private var handoffReady = false
    private var savedHandoff: JSONObject? = null
    // Kept across a status reconnect: the adopted card may no longer be listed
    // as an eligible disk once it holds private storage.
    private var androidReadyCapacity: String? = null
    private var refreshingDisks = false
    private var languageInitialized = false
    private var activeDialog: AlertDialog? = null
    private var lastGamepadConfirmMs = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // --- lifecycle -----------------------------------------------------------

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ShizukuManager.get(this)
        // A configuration/language recreate restores the same selection, editor
        // choice and slider position. A real relaunch has no saved state and
        // derives the current card state from the live native inspection.
        pendingRestoreCardId = savedInstanceState?.getString(STATE_CARD_ID)
        pendingRestoreSeek = savedInstanceState?.getInt(STATE_SEEK_PROGRESS, -1)
            ?.takeIf { it >= 0 }
        editorChosenByUser = savedInstanceState?.getBoolean(STATE_EDITOR_OPEN, false) ?: false
        awaitingPartitionResult =
            savedInstanceState?.getBoolean(STATE_AWAITING_PARTITION, false) ?: false
        inflateContentViews()
        pendingRestoreSeek?.let { progress ->
            seekAllocation.progress = AllocationPolicy.snapPercent(progress)
            updateAllocationLabels()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CARD_ID, selectedCard?.id)
        outState.putInt(STATE_SEEK_PROGRESS, seekAllocation.progress)
        outState.putBoolean(STATE_EDITOR_OPEN, editorChosenByUser)
        outState.putBoolean(STATE_AWAITING_PARTITION, awaitingPartitionResult)
    }

    override fun onStart() {
        super.onStart()
        manager.addCallback(this)
        manager.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val desiredWide = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            newConfig.screenWidthDp >= 760
        val currentWide = root is FrameLayout && root !is ScrollView
        if (desiredWide == currentWide) {
            // Same qualifier layout: only panel weights/orientations change.
            applyResponsiveLayout()
            return
        }
        // The layout resource switches between the wide FrameLayout root and
        // the base ScrollView root. The manifest keeps this Activity alive, so
        // the existing view tree must be rebuilt in place — no recreate(), no
        // lifecycle restart, all manager/state fields stay untouched.
        val seekProgress = seekAllocation.progress
        val selectedCardId = selectedCard?.id
        val statusText = textStatus.text?.toString().orEmpty()
        val statusColor = textStatus.currentTextColor
        val handoffVisibility = handoffGroup.visibility
        val handoffTitle = textHandoffTitle.text?.toString().orEmpty()
        val handoffTitleColor = textHandoffTitle.currentTextColor
        val handoffMessage = textHandoffMessage.text?.toString().orEmpty()
        val retryVisibility = buttonRetryHandoff.visibility

        inflateContentViews()

        // Rebind presentation only: no partition/prepare/status-recovery/
        // handoff write/scan/polling is ever triggered here.
        populateCardSpinner()
        val index = spinnerCards.indexOfFirst { it.id == selectedCardId }
        if (index >= 0) {
            selectedCard = spinnerCards[index]
            cardSpinner.setSelection(index, false)
        } else if (selectedCardId == null) {
            selectedCard = null
        }
        seekAllocation.progress = seekProgress
        updateAllocationLabels()
        textStatus.setTextColor(statusColor)
        textStatus.text = statusText
        handoffGroup.visibility = handoffVisibility
        textHandoffTitle.setTextColor(handoffTitleColor)
        textHandoffTitle.text = handoffTitle
        textHandoffMessage.text = handoffMessage
        buttonRetryHandoff.visibility = retryVisibility
        // Rebuilt tree: show a restored visible result again without touching state.
        revealHandoff()
        updateShizukuText(manager.currentState(), manager.currentIdentity())
        updateControls()
        renderLogs()
    }

    override fun onStop() {
        manager.removeCallback(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            activeDialog?.dismiss()
            handoffExecutor.shutdown()
        }
        manager.onActivityDestroyed(isFinishing)
        super.onDestroy()
    }

    // --- view wiring ---------------------------------------------------------

    /**
     * Inflates the layout matching the current configuration and wires every
     * view. Called from [onCreate] and re-run on qualifier switches in
     * [onConfigurationChanged]; never touches manager start/stop or any
     * operation callback, so it is safe to call repeatedly on one instance.
     */
    private fun inflateContentViews() {
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        applyResponsiveLayout()
        setupLanguage()
        setupSeekBar()
        setupCardSpinner()
        setupButtons()
        setupFooter()
        setupLogs()
        versionText.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        workspacePanels = findViewById(R.id.workspace_panels)
        cardPanel = findViewById(R.id.card_panel)
        allocationPanel = findViewById(R.id.allocation_panel)
        allocationEditor = findViewById(R.id.allocation_editor)
        allocationSummaryGroup = findViewById(R.id.allocation_summary_group)
        textInspectionState = findViewById(R.id.text_inspection_state)
        headerTextGroup = findViewById(R.id.header_text_group)
        versionText = findViewById(R.id.version_text)
        shizukuStateText = findViewById(R.id.shizuku_state_text)
        buttonAuthorize = findViewById(R.id.button_authorize)
        buttonOpenShizuku = findViewById(R.id.button_open_shizuku)
        languageSpinner = findViewById(R.id.language_spinner)
        cardSpinner = findViewById(R.id.card_spinner)
        buttonRefresh = findViewById(R.id.button_refresh)
        textAndroidPercent = findViewById(R.id.text_android_percent)
        textLinuxPercent = findViewById(R.id.text_linux_percent)
        textAndroidCapacity = findViewById(R.id.text_android_capacity)
        textLinuxCapacity = findViewById(R.id.text_linux_capacity)
        seekAllocation = findViewById(R.id.seek_allocation)
        textCapacity = findViewById(R.id.text_capacity)
        buttonReview = findViewById(R.id.button_review)
        textSummaryTotal = findViewById(R.id.text_summary_total)
        textSummaryAndroid = findViewById(R.id.text_summary_android)
        textSummaryLinux = findViewById(R.id.text_summary_linux)
        textSummaryMoveBack = findViewById(R.id.text_summary_move_back)
        buttonManageMigration = findViewById(R.id.button_manage_migration)
        buttonReallocate = findViewById(R.id.button_reallocate)
        buttonCancelReallocate = findViewById(R.id.button_cancel_reallocate)
        textStatus = findViewById(R.id.text_status)
        handoffGroup = findViewById(R.id.handoff_group)
        textHandoffTitle = findViewById(R.id.text_handoff_title)
        textHandoffMessage = findViewById(R.id.text_handoff_message)
        buttonRetryHandoff = findViewById(R.id.button_retry_handoff)
        switchShowLogs = findViewById(R.id.switch_show_logs)
        logPanel = findViewById(R.id.log_panel)
        textLog = findViewById(R.id.text_log)
        buttonCopyLogs = findViewById(R.id.button_copy_logs)
        textFooterPrefix = findViewById(R.id.text_footer_prefix)
        textFooterName = findViewById(R.id.text_footer_name)
        textFooterSuffix = findViewById(R.id.text_footer_suffix)
        // Landscape-only overlay drawer and close button; absent in the base layout.
        logDrawer = findViewById(R.id.log_drawer)
        buttonCloseLogs = findViewById(R.id.button_close_logs)
        shizukuStatusDot = findViewById(R.id.shizuku_status_dot)
        // Landscape-only allocation content scroller; null in the base layout.
        allocationScroll = findViewById(R.id.allocation_scroll)
        // Status line carries 8dp+ padding: keep it fully gone while blank.
        textStatus.visibility = View.GONE
        textStatus.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                textStatus.visibility = if (s?.isBlank() == true) View.GONE else View.VISIBLE
            }
        })
    }

    /** Keeps controls and footer clear of system bars / cutouts on edge-to-edge. */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun setupLanguage() {
        val labels = listOf(getString(R.string.language_chinese), getString(R.string.language_english))
        val adapter = ArrayAdapter(this, R.layout.item_spinner, labels)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        languageSpinner.adapter = adapter
        val current = if (AppSettings.effectiveLanguage(this) == AppSettings.LANG_ZH) 0 else 1
        languageSpinner.setSelection(current, false)
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!languageInitialized) return
                val language = if (position == 0) AppSettings.LANG_ZH else AppSettings.LANG_EN
                if (AppSettings.storedLanguage(this@MainActivity) == language) return
                AppSettings.setLanguage(this@MainActivity, language)
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        languageInitialized = true
    }

    private fun setupSeekBar() {
        seekAllocation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Accept the whole 0..100 range and snap onto the supported
                // sequence (0, 10..90, 100) instead of clamping to 10..90. The
                // corrected value is always supported, so the re-entrant set
                // terminates immediately.
                val snapped = AllocationPolicy.snapPercent(progress)
                if (snapped != progress) {
                    seekAllocation.progress = snapped
                    return
                }
                updateAllocationLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        // Horizontal keys step onto the adjacent supported percentage
        // (0 <-> 10 and 90 <-> 100 included) instead of the platform's 1-point
        // move, which could land on an unformattable share. Both key edges are
        // consumed here, only the press mutates, and every other key falls
        // through so focus navigation is untouched.
        seekAllocation.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                val next = AllocationPolicy.adjacentPercent(seekAllocation.progress, direction)
                if (next != seekAllocation.progress) {
                    seekAllocation.progress = next
                }
                updateAllocationLabels()
            }
            true
        }
        updateAllocationLabels()
    }

    private fun setupCardSpinner() {
        cardSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val card = spinnerCards.getOrNull(position)
                if (applyingCardSelection) {
                    selectedCard = card
                    return
                }
                if (card?.id == selectedCard?.id) {
                    selectedCard = card
                    updateAllocationLabels()
                    updateControls()
                    return
                }
                onCardSelected(card)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupButtons() {
        buttonAuthorize.setOnClickListener { manager.authorize() }
        buttonOpenShizuku.setOnClickListener {
            if (!manager.openShizukuManager()) {
                Toast.makeText(this, R.string.footer_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        buttonRefresh.setOnClickListener { refreshDisks(userInitiated = true) }
        buttonReview.setOnClickListener { performReview() }
        buttonReallocate.setOnClickListener { enterReallocationEditor() }
        buttonCancelReallocate.setOnClickListener { cancelReallocation() }
        buttonManageMigration.setOnClickListener { openMigrationManager() }
        buttonRetryHandoff.setOnClickListener { retryHandoff() }
        buttonCopyLogs.setOnClickListener { copyLogs() }
        // Landscape drawer close goes through the Switch so the settings
        // listener persists the choice and reuses one visibility path.
        buttonCloseLogs?.setOnClickListener { switchShowLogs.isChecked = false }
    }

    private fun setupFooter() {
        textFooterPrefix.text = getString(R.string.footer_prefix)
        textFooterName.text = getString(R.string.footer_name)
        textFooterSuffix.text = getString(R.string.footer_suffix)
        findViewById<TextView>(R.id.text_footer_bilibili).setOnClickListener {
            openFooterLink(MAKER_URL)
        }
        findViewById<TextView>(R.id.text_footer_github).setOnClickListener {
            openFooterLink(GITHUB_PROFILE_URL)
        }
    }

    private fun setupLogs() {
        switchShowLogs.text = getString(R.string.action_show_logs)
        val show = AppSettings.showLogs(this)
        switchShowLogs.isChecked = show
        applyLogVisibility(show)
        switchShowLogs.setOnCheckedChangeListener { _, checked ->
            AppSettings.setShowLogs(this, checked)
            applyLogVisibility(checked)
        }
        renderLogs()
    }

    override fun onBackPressed() {
        if (activeDialog == null && logDrawer?.visibility == View.VISIBLE) {
            switchShowLogs.isChecked = false
            return
        }
        super.onBackPressed()
    }

    private fun applyLogVisibility(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        val drawer = logDrawer
        if (drawer != null) {
            // Landscape: log region appears BELOW both main columns; the card
            // and allocation panels stay visible and enabled throughout.
            val wasVisible = drawer.visibility == View.VISIBLE
            drawer.visibility = visibility
            if (show) {
                buttonCloseLogs?.post { buttonCloseLogs?.requestFocus() }
            } else if (wasVisible) {
                switchShowLogs.post { switchShowLogs.requestFocus() }
            }
        }
        logPanel.visibility = visibility
        buttonCopyLogs.visibility = visibility
    }

    // --- Shizuku callbacks ---------------------------------------------------

    override fun onState(state: ShizukuManager.State, identity: String?) {
        updateShizukuText(state, identity)
        if (state != ShizukuManager.State.READY && operationBusy) {
            // The operation lives in the service; it is neither restarted nor
            // reported as successful while the connection is not verified.
            textStatus.text = getString(R.string.operation_unverified)
            operationBusy = false
            setKeepScreenOn(false)
        }
        when (state) {
            ShizukuManager.State.READY -> {
                if (cards.isEmpty() && !refreshingDisks) {
                    refreshDisks(userInitiated = false)
                } else {
                    selectedCard?.let { requestSummaryInfo(it.id) }
                }
                manager.requestStatus { status, _ -> status?.let { handleStatus(it) } }
            }
            ShizukuManager.State.BINDER_LOST -> {
                clearCards()
            }
            else -> Unit
        }
        updateControls()
    }

    override fun onStatus(status: ShizukuManager.OperationStatus) {
        handleStatus(status)
    }

    /**
     * Terminal migration results refresh the live summary; any migration state
     * change re-evaluates the destructive-action gate. No start is repeated.
     */
    override fun onMigrationStatus(
        status: ShizukuManager.MigrationStatus?,
        error: ShizukuManager.IpcError?,
    ) {
        if (status != null && status.state != STATE_RUNNING) {
            selectedCard?.let { requestSummaryInfo(it.id) }
        }
        updateControls()
    }

    private fun handleStatus(status: ShizukuManager.OperationStatus) {
        lastLogs = status.logs
        when (status.state) {
            STATE_RUNNING -> {
                operationBusy = true
                setKeepScreenOn(true)
                handoffGroup.visibility = View.GONE
                handoffReady = false
                savedHandoff = null
                androidReadyCapacity = null
                // The old live snapshot no longer describes the card being
                // rewritten; only the fresh post-success inspection may.
                liveCardInfo = null
                allocationSummary = null
                inspectionFresh = false
                inspectionFailed = false
                invalidateSummaryInfo()
                textStatus.text = phaseText(status.phase)
                manager.startPolling()
            }
            STATE_PARTITIONED -> {
                operationBusy = false
                setKeepScreenOn(false)
                manager.stopPolling()
                textStatus.text = getString(R.string.phase_partitioned)
                val newlyCompleted = awaitingPartitionResult
                awaitingPartitionResult = false
                if (status.phase == PHASE_ANDROID_READY) {
                    // Verified all-Android layout: there is no Linux partition
                    // and therefore no handoff to write or recover.
                    onAndroidReady()
                } else {
                    val handoff = status.handoff
                    if (handoff != null) maybeWriteHandoff(handoff) else recoverPendingHandoff()
                }
                if (newlyCompleted) {
                    recordVerifiedCompletion(status)
                }
            }
            STATE_FAILED -> {
                operationBusy = false
                awaitingPartitionResult = false
                setKeepScreenOn(false)
                manager.stopPolling()
                textStatus.text = getString(R.string.phase_failed) + ": " + localizedError(status.detail)
                pushLocalLog(LEVEL_ERROR, status.detail ?: "failed", null)
            }
            else -> {
                operationBusy = false
                manager.stopPolling()
            }
        }
        updateControls()
        renderLogs()
    }

    /**
     * Records the layout a partition that this Activity started has just
     * verified, then refreshes the live volumes. Only this path closes a
     * reallocation editor; a repeated old `partitioned` callback never does.
     */
    private fun recordVerifiedCompletion(status: ShizukuManager.OperationStatus) {
        val mode = operationMode
        val androidReady = status.phase == PHASE_ANDROID_READY
        // A live handoff with an explicit null private identifier is the
        // all-Linux schema; this also covers a run recovered after a restart
        // where the in-memory mode is gone.
        val handoffLinuxOnly = status.handoff?.let {
            it.has("private_fs_uuid") && it.isNull("private_fs_uuid")
        } == true
        val linuxOnly = !androidReady &&
            (mode == AllocationMode.LINUX_ONLY || (mode == null && handoffLinuxOnly))
        lastPartitionCompletion = when {
            androidReady -> AllocationCompletion.ADOPTED
            linuxOnly -> AllocationCompletion.VERIFIED_LINUX_ONLY
            else -> AllocationCompletion.ADOPTED
        }
        lastPartitionLinuxAllocated = when {
            androidReady -> false
            linuxOnly -> false
            mode == AllocationMode.MIXED -> true
            else -> status.handoff?.let { !it.isNull("private_fs_uuid") } ?: true
        }
        if (linuxOnly) persistVerifiedFromHandoff(status.handoff)

        val cardId = operationCardId ?: selectedCard?.id
        operationMode = null
        operationCardId = null
        if (cardId != null && manager.currentState() == ShizukuManager.State.READY) {
            requestSummaryInfo(cardId, afterSuccess = true)
        } else {
            applyUnknownSummaryAfterSuccess()
        }
    }

    // --- card / allocation rendering ----------------------------------------

    private fun refreshDisks(userInitiated: Boolean) {
        if (refreshingDisks) return
        refreshingDisks = true
        manager.refreshDisks { disks, error ->
            refreshingDisks = false
            if (error != null) {
                if (userInitiated || error.code != ERR_NOT_READY) showError(error)
                pushLocalLog(LEVEL_ERROR, error.code, error.detail)
            }
            cards = disks ?: emptyList()
            populateCardSpinner()
            updateShizukuText(manager.currentState(), manager.currentIdentity())
            updateControls()
            // Re-read the live volumes of the selected card so capacities and
            // the completed state always follow the card that is in the slot.
            selectedCard?.let { requestSummaryInfo(it.id) }
        }
    }

    private fun clearCards() {
        cards = emptyList()
        spinnerCards = emptyList()
        selectedCard = null
        liveCardInfo = null
        allocationSummary = null
        editorChosenByUser = false
        inspectionFresh = false
        inspectionFailed = false
        pendingRestoreCardId = null
        invalidateSummaryInfo()
        populateCardSpinner()
        updateControls()
    }

    private fun populateCardSpinner() {
        // Every inserted SD disk stays selectable: an already adopted card may
        // no longer report itself as eligible for a fresh partition, but its
        // completed summary must still be reachable after a relaunch.
        spinnerCards = cards
        val labels: List<String> = if (spinnerCards.isEmpty()) {
            listOf(getString(R.string.card_none))
        } else {
            spinnerCards.map { cardLabel(it) }
        }
        val adapter = ArrayAdapter(this, R.layout.item_spinner, labels)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        cardSpinner.adapter = adapter

        val previousId = selectedCard?.id ?: pendingRestoreCardId
        applyingCardSelection = true
        if (spinnerCards.isEmpty()) {
            cardSpinner.setSelection(0, false)
            selectedCard = null
        } else {
            val index = spinnerCards.indexOfFirst { it.id == previousId }
                .takeIf { it >= 0 } ?: 0
            cardSpinner.setSelection(index, false)
            selectedCard = spinnerCards[index]
        }
        applyingCardSelection = false
        pendingRestoreCardId = null
        if (previousId != null && selectedCard?.id != previousId) {
            // A different card is now selected: nothing from the old card may
            // survive as a stale summary.
            liveCardInfo = null
            allocationSummary = null
            editorChosenByUser = false
            inspectionFresh = false
            inspectionFailed = false
            invalidateSummaryInfo()
        }
        updateAllocationLabels()
    }

    /**
     * Size and, only when the firmware exposes a readable CID, its short form.
     * A volume-identity card shows no CID bullet at all rather than a device
     * path or a fabricated value.
     */
    private fun cardLabel(card: ShizukuManager.CardInfo): String =
        if (card.cid.isEmpty()) {
            formatBytes(card.sizeBytes)
        } else {
            formatBytes(card.sizeBytes) + " · " + shortCid(card.cid)
        }

    private fun shortCid(cid: String): String =
        if (cid.length > 10) cid.substring(0, 8) + "…" else cid

    private fun updateAllocationLabels() {
        val androidPercent = androidPercent()
        textAndroidPercent.text = getString(R.string.percent_label, androidPercent)
        textLinuxPercent.text = getString(R.string.percent_label, 100 - androidPercent)

        val card = selectedCard
        val capacity = if (card != null && card.eligible) approximateCapacity(card, androidPercent) else null
        textAndroidCapacity.text = capacity?.first?.let(::formatBytes)
            ?: getString(R.string.allocation_capacity_placeholder)
        textLinuxCapacity.text = capacity?.second?.let(::formatBytes)
            ?: getString(R.string.allocation_capacity_placeholder)

        val summary = allocationSummary
        if (summary != null && !editorChosenByUser) {
            // Completed summary: the card line follows the live disk capacity,
            // never the slider's current (possibly untouched) position.
            textCapacity.text = summary.totalBytes?.let {
                getString(R.string.usable_capacity, formatBytes(it))
            } ?: getString(R.string.allocation_card_unknown)
            return
        }

        val usable = if (card != null && card.eligible) formatBytes(card.sizeBytes) else null
        textCapacity.text = when {
            card != null && !card.eligible -> getString(
                R.string.card_not_eligible,
                localizedReason(card.reason ?: REASON_UNKNOWN),
            )
            usable == null -> getString(R.string.allocation_card_unknown)
            // The endpoints are whole-card layouts, so name the selected mode
            // in the existing capacity hint instead of adding a new view.
            androidPercent == 100 -> getString(R.string.allocation_mode_android_only, usable)
            androidPercent == 0 -> getString(R.string.allocation_mode_linux_only, usable)
            else -> getString(R.string.usable_capacity, usable)
        }
    }

    /**
     * Approximate split for the selected card. Uses the pure size/ratio maths:
     * the UI never needs to claim a physical identity, so a card whose CID is
     * hidden by the firmware still shows real capacities.
     */
    private fun approximateCapacity(
        card: ShizukuManager.CardInfo,
        androidPercent: Int,
    ): Pair<Long, Long>? = try {
        AllocationPolicy.capacity(card.sizeBytes, 100 - androidPercent)
    } catch (t: Exception) {
        null
    }

    private fun androidPercent(): Int = AllocationPolicy.snapPercent(seekAllocation.progress)

    // --- completed summary / reallocation ------------------------------------

    private fun onCardSelected(card: ShizukuManager.CardInfo?) {
        selectedCard = card
        liveCardInfo = null
        allocationSummary = null
        editorChosenByUser = false
        inspectionFresh = false
        inspectionFailed = false
        invalidateSummaryInfo()
        updateAllocationLabels()
        updateControls()
        card?.let { requestSummaryInfo(it.id) }
    }

    /** Drops every pending read-only snapshot so it cannot land on a new card. */
    private fun invalidateSummaryInfo() {
        summaryInfoGeneration++
    }

    /**
     * Read-only live card inspection for the summary. [afterSuccess] is set
     * only when a partition that this Activity started has just been verified:
     * that is the single case where the editor is closed automatically.
     */
    private fun requestSummaryInfo(cardId: String, afterSuccess: Boolean = false) {
        // A new read invalidates the freshness even when an older snapshot is
        // still on screen: reallocation waits for this answer.
        inspectionFresh = false
        inspectionFailed = false
        // Reallocation is disabled while this answer is outstanding.
        updateControls()
        if (manager.currentState() != ShizukuManager.State.READY) return
        val generation = ++summaryInfoGeneration
        manager.requestMigrationInfo(cardId) { info, error ->
            // A late answer for an older request or for a card the user has
            // already left must never be rendered.
            if (generation != summaryInfoGeneration) return@requestMigrationInfo
            if (selectedCard?.id != cardId) return@requestMigrationInfo
            if (error != null) {
                if (error.code != ERR_NOT_READY) {
                    pushLocalLog(LEVEL_WARN, error.code, error.detail)
                }
                if (liveCardInfo == null) inspectionFailed = true
                if (afterSuccess) {
                    applyUnknownSummaryAfterSuccess()
                } else {
                    updateControls()
                }
                return@requestMigrationInfo
            }
            info?.let { applyLiveCardInfo(it, afterSuccess) }
        }
    }

    private fun applyLiveCardInfo(info: LiveCardInfo, afterSuccess: Boolean) {
        liveCardInfo = info
        inspectionFresh = true
        inspectionFailed = false
        val stored = AppSettings.verifiedLinuxCard(this)
        if (stored != null && stored.isContradictedBy(info)) {
            // The live card actively disagrees with the stored proof (swapped
            // card, resize, repartition or another public volume): drop it.
            AppSettings.setVerifiedLinuxCard(this, null)
        }
        if (afterSuccess) {
            if (info.layout == LiveCardInfo.LAYOUT_PUBLIC_ONLY) {
                VerifiedLinuxCard.from(info)?.let { AppSettings.setVerifiedLinuxCard(this, it) }
            } else {
                AppSettings.setVerifiedLinuxCard(this, null)
            }
        }
        allocationSummary =
            AllocationSummaryPolicy.summarize(info, AppSettings.verifiedLinuxCard(this))
        if (afterSuccess) {
            if (allocationSummary == null) {
                // The native success is known but this read could not prove the
                // layout identity; close the editor with an explicit unknown
                // summary instead of showing the default slider again.
                applyUnknownSummaryAfterSuccess()
                return
            }
            editorChosenByUser = false
        }
        updateAllocationLabels()
        updateControls()
    }

    /**
     * Safety net for the rare case where the partition succeeded but the live
     * refresh failed: the verified mode is known, capacities are not, so the
     * editor is closed with an explicitly unknown summary.
     */
    private fun applyUnknownSummaryAfterSuccess() {
        val total = selectedCard?.sizeBytes?.takeIf { it > 0L }
            ?: liveCardInfo?.diskBytes?.takeIf { it > 0L }
        val summary = AllocationSummaryPolicy.unknownAfterCompletion(
            lastPartitionCompletion,
            total,
            lastPartitionLinuxAllocated,
        ) ?: return
        allocationSummary = summary
        editorChosenByUser = false
        updateAllocationLabels()
        updateControls()
    }

    private fun enterReallocationEditor() {
        val summary = allocationSummary ?: return
        if (!inspectionFresh || operationBusy || handoffWriting ||
            manager.migrationActive || savedMigrationUnresolved()
        ) {
            return
        }
        if (summary.reallocationBlocked) {
            showReallocationBlocked(summary)
            return
        }
        // Explicit user choice: the original editor (warning, preview and
        // destructive confirmation) is shown; nothing is formatted here.
        editorChosenByUser = true
        updateControls()
    }

    /** Returns to the summary without touching the card. */
    private fun cancelReallocation() {
        if (allocationSummary == null) return
        editorChosenByUser = false
        updateControls()
    }

    private fun showReallocationBlocked(summary: AllocationSummary) {
        val title = getString(R.string.summary_move_back_title)
        val message = if (summary.moveBackUnknown) {
            getString(R.string.summary_move_back_unknown_message)
        } else {
            getString(R.string.summary_move_back_required_message)
        }
        if (summary.migrationEntryAvailable) {
            showDialog(
                title = title,
                message = message,
                positive = getString(R.string.summary_migration_entry),
                negative = getString(R.string.action_close),
                onPositive = { openMigrationManager() },
            )
        } else {
            showDialog(
                title = title,
                message = message,
                positive = getString(R.string.action_close),
            )
        }
    }

    private fun openMigrationManager() {
        val card = selectedCard ?: return
        if (operationBusy || handoffWriting) return
        val intent = Intent()
            .setClassName(packageName, MIGRATION_ACTIVITY)
            .putExtra(EXTRA_DISK_ID, card.id)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.summary_migration_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Persists the public volume identity of a just-verified Linux-only card. */
    private fun persistVerifiedFromHandoff(handoff: JSONObject?) {
        val json = handoff ?: return
        val cardId = operationCardId ?: selectedCard?.id ?: return
        val diskBytes = json.optLong("disk_bytes").takeIf { it > 0L }
            ?: selectedCard?.sizeBytes?.takeIf { it > 0L }
            ?: return
        val cardCid = json.optString("card_cid").takeUnless { it == "null" }.orEmpty()
        val publicFsUuid = json.optString("public_fs_uuid")
            .takeUnless { it == "null" }.orEmpty().trim()
        val publicPartGuid = json.optString("public_partition_guid")
            .takeUnless { it == "null" }.orEmpty().trim()
        if (publicFsUuid.isEmpty() && publicPartGuid.isEmpty()) return
        AppSettings.setVerifiedLinuxCard(
            this,
            VerifiedLinuxCard(cardId, diskBytes, cardCid, publicFsUuid, publicPartGuid),
        )
    }

    private fun allocationScreenState(): AllocationScreenState =
        AllocationScreenPolicy.state(
            hasCard = selectedCard != null,
            liveInfo = liveCardInfo,
            summary = allocationSummary,
            inspectionFailed = inspectionFailed,
            editorChosenByUser = editorChosenByUser,
        )

    private fun renderAllocationMode() {
        val state = allocationScreenState()
        val summary = allocationSummary
        val showSummary = state == AllocationScreenState.SUMMARY && summary != null
        val showEditor = state == AllocationScreenState.NO_CARD ||
            state == AllocationScreenState.EDITOR
        allocationSummaryGroup.visibility = if (showSummary) View.VISIBLE else View.GONE
        allocationEditor.visibility = if (showEditor) View.VISIBLE else View.GONE
        buttonReview.visibility = if (showEditor) View.VISIBLE else View.GONE
        // A real, always reachable Cancel exists exactly while the editor was
        // opened for an already allocated card.
        buttonCancelReallocate.visibility =
            if (state == AllocationScreenState.EDITOR && summary != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
        textInspectionState.visibility = when (state) {
            AllocationScreenState.INSPECTING,
            AllocationScreenState.INSPECTION_FAILED -> View.VISIBLE
            else -> View.GONE
        }
        textInspectionState.setText(
            if (state == AllocationScreenState.INSPECTION_FAILED) {
                R.string.allocation_inspection_failed
            } else {
                R.string.allocation_inspecting
            },
        )
        if (!showSummary || summary == null) return

        textSummaryTotal.text = summary.totalBytes?.let {
            getString(R.string.summary_total, formatBytes(it))
        } ?: getString(R.string.summary_total_unknown)
        textSummaryAndroid.text = getString(
            R.string.summary_android_value,
            sideText(summary.androidState, summary.androidBytes),
        )
        textSummaryLinux.text = getString(
            R.string.summary_linux_value,
            sideText(summary.linuxState, summary.linuxBytes),
        )
        textSummaryMoveBack.visibility =
            if (summary.reallocationBlocked) View.VISIBLE else View.GONE
        textSummaryMoveBack.setText(
            if (summary.moveBackUnknown) {
                R.string.summary_move_back_unknown
            } else {
                R.string.summary_move_back_required
            },
        )
        buttonManageMigration.visibility =
            if (summary.migrationEntryAvailable) View.VISIBLE else View.GONE
    }

    private fun sideText(state: AllocationSideState, bytes: Long?): String = when (state) {
        AllocationSideState.ALLOCATED ->
            bytes?.let(::formatBytes) ?: getString(R.string.summary_side_unknown)
        AllocationSideState.NOT_ALLOCATED -> getString(R.string.summary_side_not_allocated)
        AllocationSideState.UNKNOWN -> getString(R.string.summary_side_unknown)
    }

    // --- responsive layout ---------------------------------------------------

    /**
     * Single layout for phones, tablets and rotations: the two main panels go
     * side by side on wide screens (about 30% / 70%) and stack vertically
     * otherwise. The header title and tagline share one row only on very wide
     * screens. The manifest handles configuration changes, so this runs without
     * recreating the Activity and no task state is lost.
     */
    private fun applyResponsiveLayout() {
        val widthDp = resources.configuration.screenWidthDp
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            widthDp >= 760

        workspacePanels.orientation =
            if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val cardParams = cardPanel.layoutParams as LinearLayout.LayoutParams
        if (wide) {
            cardParams.width = 0
            cardParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            cardParams.weight = 3f
            cardParams.topMargin = 0
        } else {
            cardParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            cardParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            cardParams.weight = 0f
            cardParams.topMargin = dp(12)
        }
        cardPanel.layoutParams = cardParams

        val allocParams = allocationPanel.layoutParams as LinearLayout.LayoutParams
        if (wide) {
            allocParams.width = 0
            allocParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            allocParams.weight = 7f
            allocParams.topMargin = 0
            // Compact wide layout ships a 10dp gap in XML; keep it exact here.
            allocParams.marginStart = dp(10)
        } else {
            allocParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            allocParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            allocParams.weight = 0f
            allocParams.topMargin = dp(12)
            allocParams.marginStart = 0
        }
        allocationPanel.layoutParams = allocParams

        headerTextGroup.orientation =
            if (widthDp >= 800) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val drawer = logDrawer
        if (wide && drawer != null) {
            val targetHeight = if (resources.configuration.screenHeightDp < 500) dp(96) else dp(144)
            val params = drawer.layoutParams
            if (params.height != targetHeight) {
                params.height = targetHeight
                drawer.layoutParams = params
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- flow ----------------------------------------------------------------

    private fun performReview() {
        val card = selectedCard ?: return
        if (!card.eligible || operationBusy || handoffWriting) return
        operationCardId = card.id
        operationMode = try {
            AllocationMode.forPercent(100 - androidPercent())
        } catch (t: IllegalArgumentException) {
            null
        }
        manager.prepare(card.id, androidPercent()) { ready, error ->
            if (error != null) {
                showError(error)
                return@prepare
            }
            val plan = ready ?: return@prepare
            showConfirmDialog(plan, card)
        }
    }

    private fun showConfirmDialog(plan: ShizukuManager.Prepared, card: ShizukuManager.CardInfo) {
        val cardId = plan.cardCid.ifEmpty { card.id }
        val message = buildString {
            append(getString(R.string.confirm_message))
            append("\n\n")
            append(getString(R.string.confirm_summary_card, formatBytes(plan.diskBytes))).append('\n')
            append(getString(R.string.confirm_summary_android, formatBytes(plan.androidBytes))).append('\n')
            append(getString(R.string.confirm_summary_linux, formatBytes(plan.linuxBytes))).append('\n')
            append(getString(R.string.confirm_summary_id, cardId))
        }
        showDialog(
            title = getString(R.string.confirm_title),
            message = message,
            positive = getString(R.string.action_start),
            negative = getString(R.string.action_cancel),
            cancelable = false,
            onPositive = { startOperation(plan.token) },
        )
    }

    private fun startOperation(token: String) {
        manager.start(token) { status, error ->
            if (error != null) {
                showError(error)
                return@start
            }
            if (status == null) return@start
            operationBusy = true
            awaitingPartitionResult = true
            setKeepScreenOn(true)
            textStatus.text = getString(R.string.operation_busy)
            manager.startPolling()
            updateControls()
        }
    }

    // --- handoff -------------------------------------------------------------

    private fun maybeWriteHandoff(handoff: JSONObject) {
        if (handoffWriting || handoffReady) return
        savedHandoff = JSONObject(handoff.toString())
        writeHandoff(useSaved = true)
    }

    private fun retryHandoff() {
        if (handoffWriting) return
        writeHandoff(useSaved = savedHandoff != null)
    }

    /**
     * Recovered on reconnect when the service reports a partitioned card but no
     * handoff payload: the private pending copy, if any, is re-published. Never
     * repartitions.
     */
    private fun recoverPendingHandoff() {
        if (handoffWriting || handoffReady) return
        handoffExecutor.execute {
            val pending = try {
                HandoffWriter(this).pending()
            } catch (t: Exception) {
                null
            }
            uiHandler.post {
                if (pending != null) {
                    savedHandoff = JSONObject(pending.toString())
                    writeHandoff(useSaved = true)
                }
            }
        }
    }

    private fun writeHandoff(useSaved: Boolean) {
        val handoff = savedHandoff
        if (useSaved && handoff == null) return
        handoffWriting = true
        textStatus.text = getString(R.string.handoff_writing)
        updateControls()
        handoffExecutor.execute {
            val result = try {
                val writer = HandoffWriter(this)
                if (useSaved && handoff != null) writer.write(JSONObject(handoff.toString())) else writer.retry()
                null
            } catch (e: HandoffException) {
                e.code
            } catch (t: Exception) {
                ERR_HANDOFF_WRITE_FAILED
            }
            uiHandler.post {
                handoffWriting = false
                if (result == null) onHandoffSuccess() else onHandoffFailure(result)
                updateControls()
            }
        }
    }

    /**
     * Presentation only: once the allocation content has been laid out, bring
     * the handoff result to the top of the landscape scroll panel so a finished
     * or failed run is never left below the fold. One-shot layout callback, no
     * timer or polling, and a no-op where the layout has no allocation_scroll
     * (base portrait).
     */
    private fun revealHandoff() {
        val scroll = allocationScroll ?: return
        if (handoffGroup.visibility != View.VISIBLE) return
        scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                view.removeOnLayoutChangeListener(this)
                if (allocationScroll === scroll) scroll.scrollTo(0, handoffGroup.top)
            }
        })
    }

    private fun onHandoffSuccess() {
        handoffReady = true
        textStatus.text = ""
        handoffGroup.visibility = View.VISIBLE
        textHandoffTitle.setText(R.string.handoff_ready_title)
        textHandoffTitle.setTextColor(getColor(R.color.color_ok))
        textHandoffMessage.text = getString(R.string.handoff_ready_message)
        buttonRetryHandoff.visibility = View.GONE
        pushLocalLog(LEVEL_INFO, "handoff_ready", null)
        revealHandoff()
    }

    private fun onHandoffFailure(code: String) {
        handoffReady = false
        handoffGroup.visibility = View.VISIBLE
        textHandoffTitle.setText(R.string.handoff_failed_title)
        textHandoffTitle.setTextColor(getColor(R.color.color_danger))
        textHandoffMessage.text = getString(R.string.handoff_failed_message, localizedError(code))
        buttonRetryHandoff.visibility = View.VISIBLE
        pushLocalLog(LEVEL_ERROR, code, null)
        revealHandoff()
    }

    /**
     * Renders the verified all-Android completion: the card is one adopted
     * private volume, so only the Android capacity is reported and no Linux
     * handoff exists.
     *
     * A pending payload left over from an earlier run is deleted after this
     * verified success and never re-published; the saved payload/retry action
     * are dropped so the Linux path cannot be presented as valid. Rendering is
     * idempotent, so a status reconnect keeps the same fresh result.
     */
    private fun onAndroidReady() {
        handoffReady = true
        savedHandoff = null
        androidOnlyCapacity()?.let { androidReadyCapacity = it }
        handoffGroup.visibility = View.VISIBLE
        textHandoffTitle.setText(R.string.handoff_android_ready_title)
        textHandoffTitle.setTextColor(getColor(R.color.color_ok))
        textHandoffMessage.text = getString(
            R.string.handoff_android_ready_message,
            androidReadyCapacity ?: getString(R.string.allocation_card_unknown),
        )
        buttonRetryHandoff.visibility = View.GONE
        textStatus.text = ""
        pushLocalLog(LEVEL_INFO, "android_ready", null)
        revealHandoff()
        clearPendingHandoff()
        updateControls()
    }

    /**
     * Deletes our own app-private pending handoff off the main thread. This only
     * ever runs after a verified all-Android success; the published file on the
     * card and any pending copy from a prepare/cancel/failure are left alone.
     */
    private fun clearPendingHandoff() {
        handoffExecutor.execute {
            val cleared = try {
                HandoffWriter(this).clearPending()
            } catch (t: Exception) {
                false
            }
            if (!cleared) {
                uiHandler.post { pushLocalLog(LEVEL_WARN, HANDOFF_CLEAR_FAILED, null) }
            }
        }
    }

    /** Android-only capacity for the finished card, or `null` when unknown. */
    private fun androidOnlyCapacity(): String? {
        val card = selectedCard ?: return null
        if (!card.eligible) return null
        val androidBytes = try {
            AllocationPolicy.capacity(card.sizeBytes, 0).first
        } catch (t: Exception) {
            null
        }
        return androidBytes?.let(::formatBytes)
    }

    // --- controls / logs -----------------------------------------------------

    private fun updateControls() {
        val ready = manager.currentState() == ShizukuManager.State.READY
        val busy = operationBusy || handoffWriting || manager.migrationActive ||
            savedMigrationUnresolved()
        val hasCard = selectedCard?.eligible == true
        val screenState = allocationScreenState()

        renderAllocationMode()
        seekAllocation.isEnabled = !busy && screenState == AllocationScreenState.EDITOR
        cardSpinner.isEnabled = ready && !busy && spinnerCards.isNotEmpty()
        buttonRefresh.isEnabled = ready && !busy
        buttonReview.isEnabled =
            ready && !busy && hasCard && screenState == AllocationScreenState.EDITOR
        buttonRetryHandoff.isEnabled = !busy && savedHandoff != null
        buttonReallocate.isEnabled =
            !busy && screenState == AllocationScreenState.SUMMARY && inspectionFresh
        // Migration management stays reachable while a migration runs so the
        // progress can be reopened; partition work still blocks everything.
        buttonManageMigration.isEnabled = !operationBusy && !handoffWriting
        buttonCancelReallocate.isEnabled =
            !busy && screenState == AllocationScreenState.EDITOR && allocationSummary != null
        languageSpinner.isEnabled = !busy

        val state = manager.currentState()
        buttonAuthorize.visibility =
            if (state == ShizukuManager.State.PERMISSION_NEEDED ||
                state == ShizukuManager.State.PERMISSION_DENIED
            ) View.VISIBLE else View.GONE
        buttonAuthorize.isEnabled = !busy &&
            (state == ShizukuManager.State.PERMISSION_NEEDED || state == ShizukuManager.State.PERMISSION_DENIED)
        buttonOpenShizuku.isEnabled = !busy
    }

    /**
     * A saved start/running handle blocks destructive actions even after the
     * process was restarted and the manager no longer has the in-memory flag.
     * Migration management stays reachable so the saved move can be resolved.
     */
    private fun savedMigrationUnresolved(): Boolean {
        val handle = AppSettings.migrationHandle(this) ?: return false
        return handle.state == MIGRATION_STATE_STARTING || handle.state == MIGRATION_STATE_RUNNING
    }

    private fun updateShizukuText(state: ShizukuManager.State, identity: String?) {
        val textRes = when (state) {
            ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
            ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_state_not_running
            ShizukuManager.State.UNSUPPORTED -> R.string.shizuku_state_unsupported
            ShizukuManager.State.PERMISSION_NEEDED -> R.string.shizuku_state_permission_needed
            ShizukuManager.State.PERMISSION_DENIED -> R.string.shizuku_state_permission_denied
            ShizukuManager.State.CONNECTING -> R.string.shizuku_state_connecting
            ShizukuManager.State.BINDER_LOST -> R.string.shizuku_state_binder_lost
            ShizukuManager.State.READY ->
                if (identity == "root") R.string.shizuku_state_ready_root else R.string.shizuku_state_ready_shell
        }
        shizukuStateText.setText(textRes)
        // Never green while unready: only a verified READY connection lights
        // the dot; denied/lost connections are danger, connecting amber, the
        // plain unavailable states muted.
        val dotColorRes = when (state) {
            ShizukuManager.State.READY -> R.color.color_ok
            ShizukuManager.State.PERMISSION_DENIED, ShizukuManager.State.BINDER_LOST ->
                R.color.color_danger
            ShizukuManager.State.CONNECTING -> R.color.color_warning
            else -> R.color.color_muted
        }
        shizukuStatusDot?.backgroundTintList = ColorStateList.valueOf(getColor(dotColorRes))
    }

    private fun phaseText(phase: String?): String = when (phase) {
        "prepared" -> getString(R.string.phase_prepared)
        "rechecking" -> getString(R.string.phase_rechecking)
        "partitioning" -> getString(R.string.phase_partitioning)
        "verifying" -> getString(R.string.phase_verifying)
        "partitioned" -> getString(R.string.phase_partitioned)
        "failed" -> getString(R.string.phase_failed)
        else -> getString(R.string.operation_busy)
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun pushLocalLog(level: String, code: String, detail: String?) {
        localLogs.addLast(ShizukuManager.LogEntry(System.currentTimeMillis(), level, code, detail))
        while (localLogs.size > MAX_LOG_ENTRIES) localLogs.removeFirst()
        renderLogs()
    }

    private fun renderLogs() {
        val combined = (lastLogs + localLogs).sortedBy { it.ts }.takeLast(MAX_LOG_ENTRIES)
        if (combined.isEmpty()) {
            textLog.text = getString(R.string.logs_empty)
            return
        }
        val builder = StringBuilder()
        for (entry in combined) {
            builder.append(timeFormat.format(Date(entry.ts))).append(' ')
            builder.append(levelLabel(entry.level)).append(' ')
            if (entry.code.isNotEmpty()) builder.append(entry.code)
            entry.detail?.let { builder.append(" — ").append(it) }
            builder.append('\n')
        }
        textLog.text = builder.toString()
        logPanel.post { logPanel.fullScroll(View.FOCUS_DOWN) }
    }

    private fun levelLabel(level: String): String = when (level) {
        LEVEL_WARN -> getString(R.string.log_level_warn)
        LEVEL_ERROR -> getString(R.string.log_level_error)
        else -> getString(R.string.log_level_info)
    }

    private fun copyLogs() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("armada-tf-logs", textLog.text?.toString().orEmpty()))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    // --- dialogs -------------------------------------------------------------

    private fun showError(error: ShizukuManager.IpcError) {
        pushLocalLog(LEVEL_ERROR, error.code, error.detail)
        val localized = localizedError(error.code)
        val message = if (AppSettings.showLogs(this) && error.detail != null) {
            localized + "\n\n" + getString(R.string.error_detail, error.detail)
        } else {
            localized
        }
        showDialog(
            title = getString(R.string.error_title),
            message = message,
            positive = getString(R.string.action_close),
        )
    }

    private fun showDialog(
        title: String,
        message: String,
        positive: String,
        negative: String? = null,
        cancelable: Boolean = true,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(cancelable)
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
        val dialog = builder.create()
        // Gamepad: BUTTON_A/B are consumed on ACTION_DOWN — including held-key
        // repeats — so no key ever bubbles to the Activity and re-triggers the
        // background Review flow. The action fires only on the first ACTION_UP,
        // once per physical press. Touch and the platform's own DPAD/Enter focus
        // traversal are left completely untouched.
        var buttonHeld = false
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BUTTON_A && keyCode != KeyEvent.KEYCODE_BUTTON_B) {
                return@setOnKeyListener false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    buttonHeld = true
                    true
                }
                KeyEvent.ACTION_UP -> {
                    if (!buttonHeld) return@setOnKeyListener true
                    buttonHeld = false
                    if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                        // B always backs out: the negative (Cancel) button when it
                        // exists, otherwise it closes a positive-only info dialog.
                        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        if (negative != null && cancel != null) cancel.performClick() else dialog.dismiss()
                    } else {
                        confirmFocusedButton(dialog, negative)
                    }
                    true
                }
                else -> false
            }
        }
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
        // Gamepad focus fix: a window left in touch mode by a previous physical
        // tap cannot give a plain Button input focus, so neither the safe
        // default nor an explicit Confirm/Cancel target could ever be focused
        // and BUTTON_A/B had nothing to act on. Marking the dialog's own action
        // buttons focusable-in-touch-mode lets the D-pad path reach them without
        // altering touch behaviour.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        if (negative != null) {
            // Safe default: a confirm dialog must never implicitly target the
            // destructive positive button, so when a Cancel exists it is focused
            // first and the initial BUTTON_A press cannot start the erase.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            }
        }
    }

    /**
     * BUTTON_A confirms whatever enabled button the platform has actually
     * focused. When focus is not on a button, the safe negative (Cancel) is used
     * if present; the destructive positive is never forced.
     */
    private fun confirmFocusedButton(dialog: AlertDialog, negative: String?) {
        val focused = dialog.window?.decorView?.findFocus()
        val button = focused as? Button
        if (button != null && button.isEnabled) {
            button.performClick()
            return
        }
        if (negative != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick()
        }
    }

    private fun localizedError(code: String?): String {
        val name = code ?: return getString(R.string.err_generic, "")
        val resId = when (name) {
            "busy" -> R.string.err_busy
            "invalid_request" -> R.string.err_invalid_request
            "not_confirmed" -> R.string.err_not_confirmed
            "invalid_token" -> R.string.err_invalid_token
            "no_plan" -> R.string.err_no_plan
            "card_missing" -> R.string.err_card_missing
            "card_changed" -> R.string.err_card_changed
            "permission_denied" -> R.string.err_permission_denied
            "native_storage_unavailable" -> R.string.err_native_storage_unavailable
            "allocation_failed" -> R.string.err_allocation_failed
            "verification_timeout" -> R.string.err_verification_timeout
            "verification_interrupted" -> R.string.err_verification_interrupted
            "internal_error" -> R.string.err_internal_error
            "public_volume_missing" -> R.string.err_public_volume_missing
            "handoff_invalid" -> R.string.err_handoff_invalid
            "handoff_write_failed" -> R.string.err_handoff_write_failed
            ERR_NOT_READY -> R.string.shizuku_state_binder_lost
            else -> 0
        }
        return if (resId != 0) getString(resId) else getString(R.string.err_generic, name)
    }

    private fun localizedReason(reason: String): String = when (reason) {
        "not_adoptable" -> getString(R.string.reason_not_adoptable)
        "zero_size" -> getString(R.string.reason_zero_size)
        "cid_unreadable" -> getString(R.string.reason_cid_unreadable)
        else -> getString(R.string.reason_unknown)
    }

    private fun openFooterLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.footer_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // --- gamepad -------------------------------------------------------------

    /**
     * Maps gamepad BUTTON_A/B to confirm/back only when the platform leaves them
     * unmapped. Key repeats and double activations are suppressed by handling
     * ACTION_DOWN with repeatCount 0 and by a short debounce.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (code == KeyEvent.KEYCODE_BUTTON_B) {
                    if (activeDialog == null) onBackPressed()
                } else {
                    onGamepadConfirm()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onGamepadConfirm() {
        if (activeDialog != null) return
        val now = System.currentTimeMillis()
        if (now - lastGamepadConfirmMs < GAMEPAD_DEBOUNCE_MS) return
        lastGamepadConfirmMs = now

        // BUTTON_A activates whatever control is actually focused: a button,
        // spinner, switch or the clickable maker label. On a SeekBar, left and
        // right are handled by Android, so A does nothing. A disabled or
        // non-action focused control must not trigger a different action.
        val focused = currentFocus
        if (focused != null && focused.isShown) {
            if (focused is SeekBar) return
            if (focused.isEnabled && focused.isClickable) {
                focused.performClick()
            }
            return
        }

        // If the log drawer is visible and no usable control has focus, skip the legacy allocation-action fallback.
        // (applyLogVisibility requests focus on Close, and isShown/debounce checks above gate this path.)
        if (logDrawer?.visibility == View.VISIBLE) return

        // Legacy fallback only when nothing usable is focused at all.
        if (buttonRetryHandoff.visibility == View.VISIBLE && buttonRetryHandoff.isEnabled) {
            buttonRetryHandoff.performClick()
        } else if (buttonReview.isEnabled) {
            buttonReview.performClick()
        }
    }

    // --- helpers -------------------------------------------------------------

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

    private companion object {
        const val MAX_LOG_ENTRIES = 200
        const val GAMEPAD_DEBOUNCE_MS = 350L
        const val MAKER_URL = "https://space.bilibili.com/258786"
        const val GITHUB_PROFILE_URL = "https://github.com/mydanyi"
        const val MIGRATION_ACTIVITY = "io.github.mydanyi.armadatf.ui.MigrationActivity"
        const val EXTRA_DISK_ID = "disk_id"
        const val STATE_CARD_ID = "selected_card_id"
        const val STATE_SEEK_PROGRESS = "seek_progress"
        const val STATE_EDITOR_OPEN = "editor_open"
        const val STATE_AWAITING_PARTITION = "awaiting_partition"
        const val REASON_UNKNOWN = "unknown"
        const val ERR_NOT_READY = "service_not_ready"
        const val ERR_HANDOFF_WRITE_FAILED = "handoff_write_failed"
        const val STATE_RUNNING = "running"
        const val STATE_PARTITIONED = "partitioned"
        const val STATE_FAILED = "failed"
        const val MIGRATION_STATE_STARTING = "starting"
        const val MIGRATION_STATE_RUNNING = "running"
        const val PHASE_ANDROID_READY = "android_ready"
        const val HANDOFF_CLEAR_FAILED = "handoff_clear_failed"
        const val LEVEL_INFO = "info"
        const val LEVEL_WARN = "warn"
        const val LEVEL_ERROR = "error"
    }
}
