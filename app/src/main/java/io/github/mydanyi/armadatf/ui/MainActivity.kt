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
import io.github.mydanyi.armadatf.domain.AllocationPolicy
import io.github.mydanyi.armadatf.domain.DiskSnapshot
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
    private lateinit var textFooterPrefix: TextView
    private lateinit var textFooterName: TextView
    private lateinit var textFooterSuffix: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val handoffExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "armada-tf-handoff").apply { isDaemon = true }
    }

    private var cards: List<ShizukuManager.CardInfo> = emptyList()
    private var eligibleCards: List<ShizukuManager.CardInfo> = emptyList()
    private var selectedCard: ShizukuManager.CardInfo? = null
    private var lastLogs: List<ShizukuManager.LogEntry> = emptyList()
    private val localLogs = ArrayDeque<ShizukuManager.LogEntry>()

    private var operationBusy = false
    private var handoffWriting = false
    private var handoffReady = false
    private var savedHandoff: JSONObject? = null
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
        inflateContentViews()
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
        val index = eligibleCards.indexOfFirst { it.id == selectedCardId }
        if (index >= 0) {
            selectedCard = eligibleCards[index]
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
                // Keep the real accepted range 10..90; snapping back here does
                // not recurse because the corrected value is always in range.
                if (progress < SEEK_MIN_PERCENT || progress > SEEK_MAX_PERCENT) {
                    seekAllocation.progress = progress.coerceIn(SEEK_MIN_PERCENT, SEEK_MAX_PERCENT)
                    return
                }
                updateAllocationLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateAllocationLabels()
    }

    private fun setupCardSpinner() {
        cardSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCard = eligibleCards.getOrNull(position)
                updateAllocationLabels()
                updateControls()
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
        textFooterName.setOnClickListener { openMakerLink() }
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

    private fun applyLogVisibility(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        // Landscape-only overlay drawer; the base layout keeps its inline
        // log_panel / button_copy_logs behaviour.
        logDrawer?.visibility = visibility
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
                if (cards.isEmpty() && !refreshingDisks) refreshDisks(userInitiated = false)
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

    private fun handleStatus(status: ShizukuManager.OperationStatus) {
        lastLogs = status.logs
        when (status.state) {
            STATE_RUNNING -> {
                operationBusy = true
                setKeepScreenOn(true)
                handoffGroup.visibility = View.GONE
                handoffReady = false
                savedHandoff = null
                textStatus.text = phaseText(status.phase)
                manager.startPolling()
            }
            STATE_PARTITIONED -> {
                operationBusy = false
                setKeepScreenOn(false)
                manager.stopPolling()
                textStatus.text = getString(R.string.phase_partitioned)
                val handoff = status.handoff
                if (handoff != null) maybeWriteHandoff(handoff) else recoverPendingHandoff()
            }
            STATE_FAILED -> {
                operationBusy = false
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
        }
    }

    private fun clearCards() {
        cards = emptyList()
        eligibleCards = emptyList()
        selectedCard = null
        populateCardSpinner()
    }

    private fun populateCardSpinner() {
        eligibleCards = cards.filter { it.eligible }
        val labels: List<String> = if (eligibleCards.isEmpty()) {
            listOf(fallbackCardLabel())
        } else {
            eligibleCards.map { cardLabel(it) }
        }
        val adapter = ArrayAdapter(this, R.layout.item_spinner, labels)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        cardSpinner.adapter = adapter
        if (eligibleCards.isNotEmpty()) {
            cardSpinner.setSelection(0, false)
            selectedCard = eligibleCards.first()
        } else {
            selectedCard = null
        }
        updateAllocationLabels()
    }

    private fun fallbackCardLabel(): String {
        val first = cards.firstOrNull()
        val reason = first?.reason
        return if (first != null && reason != null) {
            getString(R.string.card_not_eligible, localizedReason(reason))
        } else {
            getString(R.string.card_none)
        }
    }

    private fun cardLabel(card: ShizukuManager.CardInfo): String =
        formatBytes(card.sizeBytes) + " · " + shortCid(card.cid)

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
        textCapacity.text = if (card != null && card.eligible) {
            getString(R.string.usable_capacity, formatBytes(card.sizeBytes))
        } else {
            getString(R.string.allocation_card_unknown)
        }
    }

    private fun approximateCapacity(
        card: ShizukuManager.CardInfo,
        androidPercent: Int,
    ): Pair<Long, Long>? = try {
        val snapshot = DiskSnapshot(card.id, card.sizeBytes, card.cid, true, true)
        AllocationPolicy.capacity(AllocationPolicy.plan(snapshot, 100 - androidPercent))
    } catch (t: Exception) {
        null
    }

    private fun androidPercent(): Int = seekAllocation.progress.coerceIn(SEEK_MIN_PERCENT, SEEK_MAX_PERCENT)

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
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- flow ----------------------------------------------------------------

    private fun performReview() {
        val card = selectedCard ?: return
        if (!card.eligible || operationBusy || handoffWriting) return
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

    private fun onHandoffSuccess() {
        handoffReady = true
        textStatus.text = ""
        handoffGroup.visibility = View.VISIBLE
        textHandoffTitle.setText(R.string.handoff_ready_title)
        textHandoffTitle.setTextColor(getColor(R.color.color_ok))
        textHandoffMessage.text = getString(R.string.handoff_ready_message)
        buttonRetryHandoff.visibility = View.GONE
        pushLocalLog(LEVEL_INFO, "handoff_ready", null)
    }

    private fun onHandoffFailure(code: String) {
        handoffReady = false
        handoffGroup.visibility = View.VISIBLE
        textHandoffTitle.setText(R.string.handoff_failed_title)
        textHandoffTitle.setTextColor(getColor(R.color.color_danger))
        textHandoffMessage.text = getString(R.string.handoff_failed_message, localizedError(code))
        buttonRetryHandoff.visibility = View.VISIBLE
        pushLocalLog(LEVEL_ERROR, code, null)
    }

    // --- controls / logs -----------------------------------------------------

    private fun updateControls() {
        val ready = manager.currentState() == ShizukuManager.State.READY
        val busy = operationBusy || handoffWriting
        val hasCard = selectedCard?.eligible == true

        seekAllocation.isEnabled = !busy
        cardSpinner.isEnabled = ready && !busy && eligibleCards.isNotEmpty()
        buttonRefresh.isEnabled = ready && !busy
        buttonReview.isEnabled = ready && !busy && hasCard
        buttonRetryHandoff.isEnabled = !busy && savedHandoff != null
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

    private fun openMakerLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MAKER_URL))
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
        if (focused != null && focused.visibility == View.VISIBLE) {
            if (focused is SeekBar) return
            if (focused.isEnabled && focused.isClickable) {
                focused.performClick()
            }
            return
        }

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
        const val SEEK_MIN_PERCENT = 10
        const val SEEK_MAX_PERCENT = 90
        const val MAX_LOG_ENTRIES = 200
        const val GAMEPAD_DEBOUNCE_MS = 350L
        const val MAKER_URL = "https://space.bilibili.com/258786"
        const val ERR_NOT_READY = "service_not_ready"
        const val ERR_HANDOFF_WRITE_FAILED = "handoff_write_failed"
        const val STATE_RUNNING = "running"
        const val STATE_PARTITIONED = "partitioned"
        const val STATE_FAILED = "failed"
        const val LEVEL_INFO = "info"
        const val LEVEL_WARN = "warn"
        const val LEVEL_ERROR = "error"
    }
}
