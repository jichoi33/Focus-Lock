package com.focuslock.app

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

internal enum class FocusMode(val label: String) {
    Hard("Hard Lock"), Soft("Soft Lock")
}

internal data class DurationInput(val hours: String = "0", val minutes: String = "30") {
    val total: Int?
        get() {
            val hour = (hours.ifBlank { "0" }).toIntOrNull() ?: return null
            val minute = (minutes.ifBlank { "0" }).toIntOrNull() ?: return null
            if (hour !in 0..(Rule.MAX_LOCK_MINUTES / 60) || minute !in 0..59) return null
            return (hour * 60 + minute).takeIf {
                it in 1..Rule.DAY || (it <= Rule.MAX_LOCK_MINUTES && it > Rule.DAY && it % Rule.DAY == 0)
            }
        }

    companion object {
        fun of(total: Int) = DurationInput((total / 60).toString(), (total % 60).toString())
    }
}

internal data class Prompt(
    val title: String, val text: String, val action: String,
    val cancel: () -> Unit = {}, val durationMinutes: Int? = null, val confirm: () -> Unit
)

internal data class SchedulePreview(val window: Rule.Window, val release: Long) {
    fun matches(other: SchedulePreview?) =
        other != null && window.start == other.window.start && window.end == other.window.end &&
            release / Rule.MINUTE == other.release / Rule.MINUTE
}

internal data class ScheduleConfirmation(
    val continuous: Boolean,
    val enabled: Boolean,
    val active: SchedulePreview?,
    val next: SchedulePreview?,
    val apply: (Boolean) -> Unit
)

internal data class HardStartReview(
    val minutes: Int,
    val unlock: String?,
    val extension: String?,
    val breakInfo: String,
    val notificationInfo: String?,
    val updated: Boolean,
    val confirm: () -> Unit
) {
    val action: String
        get() = if (unlock != null) "Lock until $unlock" else "Lock phone"
}

internal data class FocusSnapshot(
    val rules: List<Rule>,
    val hard: Rule.State,
    val hardEndElapsed: Long,
    val hardEnforced: Boolean,
    val keyguardLocked: Boolean,
    val soft: SoftStore.Session,
    val softSessionId: String,
    val secure: Boolean,
    val admin: Boolean,
    val alarms: Boolean,
    val hardLockScreen: Boolean,
    val softNotifications: Boolean,
    val accessibility: Boolean,
    val blockingConnected: Boolean,
    val breaks: EmergencyBreaks
)

internal const val DEFAULT_REPEAT_NAME = "Repeating lock"

internal val repeatDayPresets = mapOf(127 to "Every day", 31 to "Weekdays", 96 to "Weekends")
internal val focusDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

internal fun repeatingDaysLabel(mask: Int): String = repeatDayPresets[mask]
    ?: focusDays.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.joinToString(", ")

internal fun durationLabel(total: Int): String = when {
    total > Rule.DAY && total % Rule.DAY == 0 -> "${total / Rule.DAY} days"
    total < 60 -> "${total} min"
    total % 60 == 0 -> "${total / 60} hr"
    else -> "${total / 60} hr ${total % 60} min"
}

class MainActivity : ComponentActivity() {
    private lateinit var hardStore: FocusStore
    private lateinit var softStore: SoftStore
    private lateinit var completionStore: SessionCompletion
    private val completionChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SessionCompletion.PENDING, SessionCompletion.HARD_RESULT, SessionCompletion.SOFT_RESULT,
            SessionCompletion.HARD_TIME, SessionCompletion.SOFT_TIME -> updateCompletion()
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val updateBoundary = Runnable { update() }
    private val blockingChanged = Runnable {
        update()
        if (SoftBlocker.isConnected()) resumeSetup()
    }
    private val hardChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> update() }
    private val softChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "end_elapsed") update()
    }

    internal var autoCelebration by mutableStateOf(false)
        private set

    internal var use24Hour by mutableStateOf(false)
        private set

    internal fun dateLabel(time: Long) = ClockLabels.dateTime(time, use24Hour)
    internal fun timeLabel(minute: Int) = ClockLabels.time(minute, use24Hour)
    internal fun unlockLabel(release: Rule.Release, now: Long): String =
        if (release.end == 0L) "No scheduled unlock in the next 7 days"
        else "Unlock at ${ClockLabels.unlockTime(release.end, now, use24Hour)}"

    internal fun releaseAfter(state: Rule.State, end: Long, now: Long): Rule.Release =
        state.releaseAfter(end, now, ZoneId.systemDefault())

    internal var snapshot by mutableStateOf<FocusSnapshot?>(null)
        private set
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            update()
        }
    }
    internal var completionMillis by mutableLongStateOf(0L)
        private set
    internal var completedModes by mutableIntStateOf(0)
        private set
    internal var mode by mutableStateOf(FocusMode.Hard)
    internal var quickDuration by mutableStateOf(DurationInput())
    internal var softDuration by mutableStateOf(DurationInput())
    internal var allowlist by mutableStateOf(false)
    internal var allowBrowserTabs by mutableStateOf(false)
    internal var strictSites by mutableStateOf(false)
    internal var returnHomeOnBlock by mutableStateOf(true)
    internal var blockedApps by mutableStateOf(emptySet<String>())
    internal var allowedApps by mutableStateOf(emptySet<String>())
    internal var blockedSites by mutableStateOf(emptySet<String>())
    private enum class SetupAction { Hard, HardTest, Soft, SoftTest, Schedule }
    private enum class SetupPermission { ScreenLock, Admin, Alarms, Accessibility }

    private var setupAction: SetupAction? = null
    private var setupPermission: SetupPermission? = null
    private var scheduleForReview: Rule? = null
    internal var hardStartReview by mutableStateOf<HardStartReview?>(null)
    internal var scheduleConfirmation by mutableStateOf<ScheduleConfirmation?>(null)
    internal var prompt by mutableStateOf<Prompt?>(null)
    internal var schedule by mutableStateOf<Rule?>(null)
    internal var sheet by mutableStateOf<String?>(null)
    internal var tighteningSessionId: String? = null
        private set

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshHard()
        refreshSoftNotification()
    }

    override fun attachBaseContext(newBase: Context) {
        val english = Configuration().apply { setLocale(Locale.ENGLISH) }
        super.attachBaseContext(newBase.createConfigurationContext(english))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hardStore = FocusStore(this)
        softStore = SoftStore(this)
        completionStore = SessionCompletion(this)
        val session = softStore.read()
        mode = if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean("soft_mode")) FocusMode.Soft else FocusMode.Hard
        } else {
            intent.lockMode() ?: if (SoftBlocker.activeTest() != null || session.active(SystemClock.elapsedRealtime())) {
                FocusMode.Soft
            } else FocusMode.Hard
        }
        quickDuration = savedInstanceState.readDuration("hard")
        softDuration = savedInstanceState.readDuration("soft")
        blockedApps = savedInstanceState?.getStringArrayList("soft_apps")?.toSet() ?: softStore.selectedApps(false)
        allowedApps = savedInstanceState?.getStringArrayList("allowed_apps")?.toSet() ?: softStore.selectedApps(true)
        blockedSites = savedInstanceState?.getStringArrayList("soft_sites")?.toSet() ?: softStore.blockedSites()
        strictSites = savedInstanceState?.getBoolean("unverifiable") ?: softStore.blockUnverifiable()
        allowBrowserTabs = savedInstanceState?.getBoolean("allow_browser_tabs", session.allowBrowserTabs) ?: session.allowBrowserTabs
        val returnHomeDefault = softStore.returnHomeByDefault()
        returnHomeOnBlock = savedInstanceState?.getBoolean("return_home", returnHomeDefault) ?: returnHomeDefault
        allowlist = savedInstanceState?.getBoolean("allowlist") ?: session.allowlist
        sheet = savedInstanceState?.getString("rule_editor")
        tighteningSessionId = savedInstanceState?.getString("editing_session")
        schedule = savedInstanceState?.getBundle("schedule_editor")?.readSchedule()
        scheduleForReview = savedInstanceState?.getBundle("schedule_review")?.readSchedule()
        setupAction = savedInstanceState?.getString("setup_action")?.let(SetupAction::valueOf)
        setupPermission = savedInstanceState?.getString("setup_permission")?.let(SetupPermission::valueOf)
        styleSystemBars(resources.configuration)
        update()
        openReminderSchedule(intent)
        if (savedInstanceState == null) openSoftBreak(intent)
        setContent { FocusTheme { FocusWorkspace(this) } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        styleSystemBars(newConfig)
    }

    private fun styleSystemBars(configuration: Configuration) {
        val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val transparent = android.graphics.Color.TRANSPARENT
        val style = androidx.activity.SystemBarStyle.auto(transparent, transparent) { night }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.lockMode()?.let { mode = it }
        update()
        openReminderSchedule(intent)
        openSoftBreak(intent)
    }

    private fun openSoftBreak(intent: Intent) {
        if (intent.action != LockNotifications.OPEN_SOFT_BREAK) return
        if (snapshot?.soft?.let { !it.test && it.active(SystemClock.elapsedRealtime()) } == true) offerBreak()
    }

    private fun openReminderSchedule(intent: Intent) {
        if (intent.action != LockNotifications.OPEN_SCHEDULE || schedule != null || sheet != null || prompt != null) return
        val id = intent.getStringExtra(LockNotifications.RULE_ID)
        val current = snapshot ?: return
        if (current.hard.active.any { it.rule.id == id }) return
        schedule = current.rules.firstOrNull { it.id == id && it.enabled && !it.quick() }
    }

    private fun Intent?.lockMode(): FocusMode? = when (this?.action) {
        LockNotifications.OPEN_HARD, LockNotifications.OPEN_SCHEDULE -> FocusMode.Hard
        LockNotifications.OPEN_SOFT, LockNotifications.OPEN_SOFT_BREAK -> FocusMode.Soft
        else -> null
    }

    private fun Bundle?.readDuration(prefix: String): DurationInput {
        val defaults = DurationInput()
        return DurationInput(
            hours = this?.getString("${prefix}_hours") ?: defaults.hours,
            minutes = this?.getString("${prefix}_minutes") ?: defaults.minutes
        )
    }

    private fun Rule.saveSchedule() = bundleOf(
        "id" to id, "name" to name, "enabled" to enabled, "days" to days,
        "start" to startMinute, "end" to endMinute, "duration" to durationMinutes,
        "from" to from, "to" to to, "skipped" to skippedThrough
    )

    private fun Bundle.readSchedule() = Rule().apply {
        val data = this@readSchedule
        id = requireNotNull(data.getString("id"))
        name = data.getString("name")
        enabled = data.getBoolean("enabled")
        days = data.getInt("days")
        startMinute = data.getInt("start")
        endMinute = data.getInt("end")
        durationMinutes = data.getInt("duration")
        from = data.getLong("from")
        to = data.getLong("to")
        skippedThrough = data.getLong("skipped")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("soft_mode", mode == FocusMode.Soft)
        outState.putString("hard_hours", quickDuration.hours)
        outState.putString("hard_minutes", quickDuration.minutes)
        outState.putString("soft_hours", softDuration.hours)
        outState.putString("soft_minutes", softDuration.minutes)
        outState.putBoolean("allowlist", allowlist)
        outState.putBoolean("unverifiable", strictSites)
        outState.putBoolean("allow_browser_tabs", allowBrowserTabs)
        outState.putBoolean("return_home", returnHomeOnBlock)
        outState.putStringArrayList("soft_apps", ArrayList(blockedApps))
        outState.putStringArrayList("allowed_apps", ArrayList(allowedApps))
        outState.putStringArrayList("soft_sites", ArrayList(blockedSites))
        outState.putBundle("schedule_editor", schedule?.saveSchedule())
        outState.putBundle("schedule_review", scheduleForReview?.saveSchedule())
        if (sheet in setOf("apps", "sites", "tighten-apps", "tighten-sites")) {
            outState.putString("rule_editor", sheet)
            outState.putString("editing_session", tighteningSessionId)
        }
        outState.putString("setup_action", setupAction?.name)
        outState.putString("setup_permission", setupPermission?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(unlocked, filter)
    }

    override fun onStop() {
        unregisterReceiver(unlocked)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        hardStore.observe(hardChanged)
        softStore.observe(softChanged)
        completionStore.observe(completionChanged)
        SoftBlocker.observeConnection(blockingChanged)
        FocusService.enforcementChanged = blockingChanged
        refreshHard()
        refreshSoftNotification()
        if (setupPermission != null) resumeSetup()
        else if (scheduleConfirmation == null && prompt == null) {
            scheduleForReview?.let { applySchedule(it, review = true) }
        }
    }

    override fun onPause() {
        hardStore.unobserve(hardChanged)
        softStore.unobserve(softChanged)
        completionStore.unobserve(completionChanged)
        SoftBlocker.unobserveConnection(blockingChanged)
        if (FocusService.enforcementChanged === blockingChanged) FocusService.enforcementChanged = null
        handler.removeCallbacks(updateBoundary)
        super.onPause()
    }

    private fun update() {
        use24Hour = ClockLabels.is24Hour(this)
        autoCelebration = completionStore.autoEnabled()
        val clock = SessionClock(this)
        val rules = hardStore.rules()
        softStore.checkCompletion(clock, completionStore)
        val savedSoft = softStore.read()
        val soft = SoftBlocker.activeTest() ?: savedSoft
        val windows = Rule.at(rules, clock.wall, clock.elapsed, clock.boot, ZoneId.systemDefault())
        completionStore.reconcileHard(clock, windows)
        updateCompletion()
        val keyguard = getSystemService(KeyguardManager::class.java)
        val hardEndElapsed = if (windows.active.isEmpty()) 0L else clock.elapsed + (windows.activeEnd() - clock.wall)
        snapshot = FocusSnapshot(
            rules, windows, hardEndElapsed, FocusService.settingsBlockedUntil > clock.elapsed, keyguard.isKeyguardLocked,
            soft, softStore.sessionId(), keyguard.isDeviceSecure,
            FocusAdmin.enabled(this), AlarmReceiver.canSchedule(this),
            LockNotifications.hardVisible(this),
            LockNotifications.available(this, true),
            SoftBlocker.enabled(this), SoftBlocker.isConnected(), EmergencyBreaks.read(this, clock, windows, savedSoft)
        )
        handler.removeCallbacks(updateBoundary)
        val hardDelay = if (windows.next == Long.MAX_VALUE) Long.MAX_VALUE else windows.next - clock.wall
        val softDelay = if (soft.active(clock.elapsed)) soft.timer.elapsedEnd - clock.elapsed else Long.MAX_VALUE
        val until = snapshot?.breaks?.until ?: 0L
        val reset = snapshot?.breaks?.nextReset ?: Long.MAX_VALUE
        val nextDelay = minOf(hardDelay, softDelay, if (reset != Long.MAX_VALUE) reset - clock.elapsed else Long.MAX_VALUE,
            if (until > clock.elapsed) until - clock.elapsed else Long.MAX_VALUE)
        if (nextDelay != Long.MAX_VALUE) handler.postDelayed(updateBoundary, nextDelay.coerceAtLeast(1L))
    }

    private fun updateCompletion() {
        completedModes = completionStore.pending()
        completionMillis = when (completedModes) {
            SessionCompletion.HARD, SessionCompletion.SOFT -> completionStore.protectedMillis(completedModes)
            else -> 0L
        }
    }

    internal fun dismissCompletion() {
        completionStore.dismiss()
        updateCompletion()
    }

    internal fun refreshHard() {
        update()
        val current = snapshot ?: return
        AlarmReceiver.schedule(this, current.hard)
        if (current.admin && (current.hard.active.isNotEmpty() || FocusService.running)) {
            try {
                FocusService.refresh(this)
            } catch (error: RuntimeException) {
                if (error !is IllegalStateException && error !is SecurityException) throw error
                AlarmReceiver.showStartFailure(this, current.hard)
                if (current.hard.active.isNotEmpty()) notify("Hard Lock couldn’t start. Try again.")
            }
        }
    }

    private fun refreshSoftNotification() {
        val current = snapshot ?: return
        if (current.softNotifications && current.blockingConnected && current.soft.active(SystemClock.elapsedRealtime())) {
            LockNotifications.showSoft(this, current.soft)
        }
    }

    internal fun setAutoCelebration(enabled: Boolean) {
        completionStore.setAutoEnabled(enabled)
        if (enabled && !SessionCompletion.canOpenOnUnlock(this)) {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app = true)
        }
        update()
    }

    internal fun openSettings(action: String, app: Boolean = false) {
        val intent = Intent(action).apply { if (app) data = Uri.fromParts("package", packageName, null) }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    internal fun enableAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, FocusAdmin.component(this))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Focus Lock uses device admin to reapply the screen lock during a session.")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            notify("Device admin settings are unavailable on this device.")
        }
    }

    internal fun enableNotifications(soft: Boolean) {
        openNotificationSettings(if (soft) LockNotifications.SOFT_CHANNEL else FocusService.CHANNEL)
    }

    internal fun openScheduleReminderSettings() {
        ScheduleReminders.channel(this)
        openNotificationSettings(ScheduleReminders.CHANNEL)
    }

    private fun openNotificationSettings(channel: String) {
        val asked = getPreferences(MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            !getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (!asked.getBoolean("asked_notice", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            asked.edit().putBoolean("asked_notice", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                startActivity(appSettings)
            } else {
                val channelSettings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
                try {
                    startActivity(channelSettings)
                } catch (_: ActivityNotFoundException) {
                    startActivity(appSettings)
                }
            }
        }
    }

    private fun setupPrompt(
        action: SetupAction, permission: SetupPermission,
        title: String, message: String, button: String = "Open settings", open: () -> Unit
    ) {
        prompt = Prompt(title, message, button, cancel = {
            if (action == SetupAction.Schedule) dismissScheduleReview()
        }) {
            setupAction = action
            setupPermission = permission
            open()
        }
    }

    private fun resumeSetup() {
        val action = setupAction ?: return
        val current = snapshot ?: return
        val granted = when (setupPermission) {
            SetupPermission.ScreenLock -> current.secure
            SetupPermission.Admin -> current.admin
            SetupPermission.Alarms -> current.alarms
            SetupPermission.Accessibility -> {
                // Android may connect the enabled service shortly after the activity resumes.
                if (current.accessibility && !current.blockingConnected) return
                current.blockingConnected
            }
            null -> return
        }
        setupPermission = null
        setupAction = null
        if (!granted) {
            if (action == SetupAction.Schedule) dismissScheduleReview()
            return
        }
        when (action) {
            SetupAction.Hard, SetupAction.HardTest -> startHard(action == SetupAction.HardTest)
            SetupAction.Soft, SetupAction.SoftTest -> startSoft(action == SetupAction.SoftTest)
            SetupAction.Schedule -> scheduleForReview?.let { applySchedule(it, review = true) }
        }
    }

    private fun requireHard(action: SetupAction): Boolean {
        val current = snapshot ?: return false
        if (!current.secure) {
            setupPrompt(action, SetupPermission.ScreenLock, "Set up screen lock",
                "Set an Android PIN, pattern, or password so Focus Lock can lock the screen.") {
                openSettings(Settings.ACTION_SECURITY_SETTINGS)
            }
            return false
        }
        if (!current.admin) {
            setupPrompt(action, SetupPermission.Admin, "Allow screen locking",
                "Allow Focus Lock to activate the Android lock screen during a Hard Lock session.",
                "Continue", ::enableAdmin)
            return false
        }
        if (action == SetupAction.Schedule && !current.alarms) {
            setupPrompt(action, SetupPermission.Alarms, "Allow scheduled starts",
                "Allow Alarms & reminders so Focus Lock can start your schedule on time.") {
                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true)
            }
            return false
        }
        return true
    }

    internal fun openLockScreenSettings() {
        try {
            startActivity(Intent("android.settings.LOCK_SCREEN_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            openSettings(Settings.ACTION_SETTINGS)
        }
    }

    internal fun startHard(test: Boolean = false) {
        val minutes = if (test) 1 else quickDuration.total ?: return invalidDuration()
        update()
        if (!requireHard(if (test) SetupAction.HardTest else SetupAction.Hard)) return
        val current = snapshot ?: return
        if (current.hard.active.isNotEmpty()) return
        if (test) {
            if (current.hard.next - System.currentTimeMillis() <= Rule.MINUTE) {
                notify("A scheduled Hard Lock is about to start.")
                return
            }
            val controls = if (current.hardLockScreen) "Expand the lock screen notification and tap End test to finish early."
                else "Lock screen notifications are off. The test ends automatically after one minute."
            prompt = Prompt("Ready for your 1-minute test", "Your phone will lock for one minute. $controls", "Start test") {
                beginHard(minutes, test = true)
            }
        } else reviewHardStart(minutes)
    }

    private fun reviewHardStart(minutes: Int, updated: Boolean = false) {
        val current = snapshot ?: return
        val clock = SessionClock(this)
        val planned = clock.start(minutes * Rule.MINUTE)
        val release = releaseAfter(current.hard, planned.wallEnd, clock.wall)
        val unlock = release.end.takeIf { it > 0L }?.let { ClockLabels.unlockTime(it, clock.wall, use24Hour) }
        val extension = release.extension?.let {
            val name = it.rule.name.orEmpty().ifBlank { "A scheduled lock" }
            "$name extends this lock."
        }
        val breaks = current.breaks
        val breakInfo = if (breaks.enabled) {
            val uses = if (breaks.remaining() == 1) "1 break" else "${breaks.remaining()} breaks"
            "$uses available, up to ${breaks.minutes} min each"
        } else "Emergency breaks are off"
        val notificationInfo = when {
            breaks.enabled && !current.hardLockScreen -> "Lock screen notifications are off. Turn them on to access break controls."
            breaks.enabled -> "Expand the Hard Lock notification on the lock screen to take a break."
            else -> null
        }
        hardStartReview = HardStartReview(minutes, unlock, extension, breakInfo, notificationInfo, updated) {
            beginHard(minutes, test = false, reviewed = release)
        }
    }

    private fun beginHard(minutes: Int, test: Boolean, reviewed: Rule.Release? = null) {
        update()
        if (!requireHard(if (test) SetupAction.HardTest else SetupAction.Hard)) return
        val clock = SessionClock(this)
        val latest = hardStore.state(clock)
        if (latest.active.isNotEmpty() || (test && latest.next - clock.wall <= Rule.MINUTE)) {
            notify("A Hard Lock is already active or about to start.")
            return
        }
        val timer = clock.start(minutes * Rule.MINUTE)
        if (reviewed != null) {
            val release = releaseAfter(latest, timer.wallEnd, clock.wall)
            if (release.end / Rule.MINUTE != reviewed.end / Rule.MINUTE ||
                release.extension?.key() != reviewed.extension?.key()) {
                reviewHardStart(minutes, updated = true)
                return
            }
        }
        val rule = Rule().apply {
            id = Rule.QUICK_ID
            name = if (test) "Hard Lock test" else "Lock now"
            this.test = test
            this.timer = timer
            from = clock.wall
            to = timer.wallEnd
        }
        hardStore.put(rule)
        refreshHard()
    }

    internal fun startSoft(test: Boolean = false) {
        val minutes = if (test) 1 else softDuration.total ?: return invalidDuration()
        val apps = if (allowlist) allowedApps else blockedApps
        if (!allowlist && apps.isEmpty() && blockedSites.isEmpty()) {
            notify("Add at least one app or website to block.")
            return
        }
        if (!requireSoftConnection(test)) return
        val summary = when {
            !allowlist -> "Your selected apps and websites will be blocked."
            apps.isEmpty() -> "Only essential features, including Home and Phone, will be available."
            else -> "Only your ${apps.size} selected apps and essential features will be available."
        }
        val usingAllowlist = allowlist
        val sites = if (usingAllowlist) emptySet() else blockedSites
        val tabs = allowBrowserTabs
        val strict = strictSites
        val returnHome = returnHomeOnBlock
        val notice = if (LockNotifications.available(this, true)) "" else
            "\nEnable Soft Lock notifications to see the countdown outside Focus Lock."
        val session = if (test) "1-minute test. You can end it at any time." else "${durationLabel(minutes)} session."
        val breakInfo = if (test) "" else if (EmergencyBreaks.current(this).enabled)
            "\nFor urgent use, open Focus Lock and tap Emergency break." else "\nEmergency breaks are off."
        prompt = Prompt(if (test) "Ready for your 1-minute test" else "Start Soft Lock?",
            "$session\n$summary\nAndroid Settings will be blocked.$breakInfo$notice",
            if (test) "Start test" else "Start", durationMinutes = minutes) {
            if (requireSoftConnection(test) && SoftBlocker.activeTest() == null &&
                !softStore.read().active(SystemClock.elapsedRealtime())) {
                if (test) {
                    SoftBlocker.startTest(apps, sites, strict, usingAllowlist, tabs, returnHome)
                } else {
                    softStore.start(minutes * Rule.MINUTE, apps, sites, strict, usingAllowlist, tabs, returnHome)
                }
            }
        }
    }

    private fun requireSoftConnection(test: Boolean): Boolean {
        if (SoftBlocker.isConnected()) return true
        setupPrompt(if (test) SetupAction.SoftTest else SetupAction.Soft, SetupPermission.Accessibility,
            "Connect Soft Lock", "Turn on the Focus Lock accessibility service so it can block your selected apps and sites.") {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        return false
    }

    internal fun extendSoft(duration: DurationInput) {
        val minutes = duration.total ?: return invalidDuration()
        if (!softStore.extend(minutes)) notify("The session has already ended.")
        sheet = null
    }

    internal fun openRuleTightening(sessionId: String) {
        tighteningSessionId = sessionId
        sheet = "tighten"
    }

    internal fun tightenSoft(sessionId: String, allowlist: Boolean, apps: Set<String> = emptySet(), sites: Set<String> = emptySet()) {
        if (!softStore.tighten(sessionId, allowlist, apps, sites)) {
            notify("This Soft Lock session has ended.")
            sheet = null
            return
        }
        if (allowlist) allowedApps = softStore.selectedApps(true)
        else {
            blockedApps = softStore.selectedApps(false)
            blockedSites = softStore.blockedSites()
        }
        update()
        sheet = "tighten"
    }

    internal fun hardAction(action: String) {
        val intent = Intent(this, FocusService::class.java).setAction(action)
        if (FocusService.running) startService(intent) else startForegroundService(intent)
    }

    internal fun offerBreak() {
        val b = EmergencyBreaks.current(this)
        val now = SystemClock.elapsedRealtime()
        if (!b.available(now)) {
            notify("No emergency break is available right now.")
            return
        }
        prompt = Prompt("Take an emergency break?", "Use one of your ${b.remaining()} remaining breaks for up to ${b.minutes} min. Your session timer keeps running.", "Take break") {
            if (EmergencyBreaks.begin(this)) refreshHard() else notify("This break is no longer available.")
        }
    }

    internal fun configureBreaks(enabled: Boolean, minutes: Int, limit: Int) {
        if (EmergencyBreaks.configure(this, enabled, minutes, limit)) {
            sheet = null
            update()
        } else notify("Choose 1–60 minutes and 1–20 breaks before starting a lock.")
    }

    internal fun openNewSchedule() {
        sheet = "schedule"
    }

    internal fun startScheduleDraft(repeating: Boolean) {
        sheet = null
        schedule = Rule().apply {
            from = ZonedDateTime.now().plusHours(1).withSecond(0).withNano(0).toInstant().toEpochMilli()
            to = from + 45 * Rule.MINUTE
            days = if (repeating) 31 else 0
            startMinute = 22 * 60
            endMinute = 7 * 60
        }
    }

    internal fun dismissSchedule() {
        dismissScheduleReview()
        schedule = null
    }

    internal fun dismissScheduleReview() {
        scheduleConfirmation = null
        scheduleForReview = null
    }

    internal fun saveRepeatingSchedule(rule: Rule, name: String, days: Int, start: Int, end: Int) {
        if (days == 0) return notify("Choose at least one day.")
        val draft = rule.withRepeatTimes(days, start, end)
        draft.name = name.trim().ifBlank { DEFAULT_REPEAT_NAME }
        applySchedule(draft, review = true)
    }

    internal fun saveOneTimeSchedule(rule: Rule, name: String, from: Long, duration: DurationInput) {
        val minutes = duration.total ?: return invalidDuration()
        val draft = rule.copy()
        draft.from = from
        draft.to = from + minutes * Rule.MINUTE
        draft.name = name.trim().ifBlank { "One-time lock" }
        applySchedule(draft, review = true)
    }

    private fun previewSchedule(draft: Rule, clock: SessionClock): Pair<SchedulePreview?, SchedulePreview?> {
        val zone = ZoneId.systemDefault()
        val state = Rule.at(listOf(draft), clock.wall, clock.elapsed, clock.boot, zone)
        val rules = hardStore.rules().filterNot { it.id == draft.id } + draft
        fun preview(window: Rule.Window?) = window?.let {
            SchedulePreview(it, Rule.finalRelease(rules, it, clock.wall, clock.elapsed, clock.boot, zone))
        }
        return preview(state.latestActive()) to preview(state.nextStart)
    }

    private fun applySchedule(
        draft: Rule,
        review: Boolean = false,
        confirmed: ScheduleConfirmation? = null,
        defer: Boolean = false
    ) {
        val clock = SessionClock(this)
        if (!draft.repeats() && draft.to <= clock.wall) {
            dismissScheduleReview()
            return notify("Choose a time and duration so the lock ends in the future.")
        }
        if (draft.enabled && !requireHard(SetupAction.Schedule)) {
            scheduleForReview = draft
            return
        }
        val (current, upcoming) = previewSchedule(draft.copy().apply { enabled = true }, clock)
        val active = current.takeIf { draft.enabled }
        val next = upcoming ?: current.takeUnless { draft.enabled }
        val continuous = draft.enabled && draft.repeatsWithoutGap()
        val sameActive = active?.matches(confirmed?.active) ?: (confirmed?.active == null)
        val sameNext = next?.matches(confirmed?.next) ?: (confirmed?.next == null)
        if ((review || active != null || continuous) && !(confirmed != null && sameActive && sameNext)) {
            val proposal = ScheduleConfirmation(continuous, draft.enabled, active, next) { nextOnly ->
                val confirmedSelection = scheduleConfirmation
                scheduleConfirmation = null
                applySchedule(draft, review, confirmedSelection, nextOnly)
            }
            scheduleForReview = draft
            scheduleConfirmation = proposal
            return
        }
        if (defer && active != null) {
            draft.skippedThrough = maxOf(draft.skippedThrough, active.window.start + 1)
        }
        scheduleConfirmation = null
        hardStore.put(draft)
        dismissSchedule()
        refreshHard()
    }

    internal fun toggleSchedule(rule: Rule) {
        applySchedule(rule.copy().apply { enabled = !enabled })
    }

    internal fun deleteSchedule(rule: Rule) {
        prompt = Prompt("Delete schedule?", rule.name, "Delete") {
            hardStore.remove(rule.id)
            dismissSchedule()
            refreshHard()
        }
    }

    private fun invalidDuration() = notify("Choose a duration from 1 minute to ${Rule.MAX_LOCK_DAYS} days.")
    internal fun notify(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

