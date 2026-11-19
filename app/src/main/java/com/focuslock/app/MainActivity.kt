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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
            return (hour * 60 + minute).takeIf { hour in 0..24 && minute in 0..59 && it in 1..1440 }
        }

    companion object {
        fun of(total: Int) = DurationInput((total / 60).toString(), (total % 60).toString())
    }
}

internal data class Prompt(val title: String, val text: String, val action: String, val confirm: () -> Unit)

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

internal val focusDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
internal fun daysLabel(mask: Int): String =
    focusDays.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.joinToString(", ")

internal fun durationLabel(total: Int): String = when {
    total < 60 -> "${total} min"
    total % 60 == 0 -> "${total / 60} hr"
    else -> "${total / 60} hr ${total % 60} min"
}

class MainActivity : ComponentActivity() {
    private lateinit var hardStore: FocusStore
    private lateinit var softStore: SoftStore
    private lateinit var completionStore: SessionCompletion
    private val completionChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SessionCompletion.PENDING || key == SessionCompletion.HARD_RESULT || key == SessionCompletion.SOFT_RESULT) {
            updateCompletion()
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val updateBoundary = Runnable { update() }
    private val blockingChanged = Runnable { update() }
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
    internal fun windowLabel(window: Rule.Window) = "${dateLabel(window.start)} → ${dateLabel(window.end)}"

    internal var snapshot by mutableStateOf<FocusSnapshot?>(null)
        private set
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            update()
        }
    }
    internal var completionDescription by mutableStateOf("")
        private set
    internal var completedModes by mutableIntStateOf(0)
        private set
    internal var mode by mutableStateOf(FocusMode.Hard)
    internal var quickDuration by mutableStateOf(DurationInput())
    internal var softDuration by mutableStateOf(DurationInput())
    internal var allowlist by mutableStateOf(false)
    internal var strictSites by mutableStateOf(false)
    internal var returnHomeOnBlock by mutableStateOf(true)
    internal var blockedApps by mutableStateOf(emptySet<String>())
    internal var allowedApps by mutableStateOf(emptySet<String>())
    internal var blockedSites by mutableStateOf(emptySet<String>())
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
        val returnHomeDefault = softStore.returnHomeByDefault()
        returnHomeOnBlock = savedInstanceState?.getBoolean("return_home", returnHomeDefault) ?: returnHomeDefault
        allowlist = savedInstanceState?.getBoolean("allowlist") ?: session.allowlist
        styleSystemBars(resources.configuration)
        update()
        openReminderSchedule(intent)
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
        LockNotifications.OPEN_SOFT -> FocusMode.Soft
        else -> null
    }

    private fun Bundle?.readDuration(prefix: String): DurationInput {
        val defaults = DurationInput()
        return DurationInput(
            hours = this?.getString("${prefix}_hours") ?: defaults.hours,
            minutes = this?.getString("${prefix}_minutes") ?: defaults.minutes
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("soft_mode", mode == FocusMode.Soft)
        outState.putString("hard_hours", quickDuration.hours)
        outState.putString("hard_minutes", quickDuration.minutes)
        outState.putString("soft_hours", softDuration.hours)
        outState.putString("soft_minutes", softDuration.minutes)
        outState.putBoolean("allowlist", allowlist)
        outState.putBoolean("unverifiable", strictSites)
        outState.putBoolean("return_home", returnHomeOnBlock)
        outState.putStringArrayList("soft_apps", ArrayList(blockedApps))
        outState.putStringArrayList("allowed_apps", ArrayList(allowedApps))
        outState.putStringArrayList("soft_sites", ArrayList(blockedSites))
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
        val nextDelay = minOf(hardDelay, softDelay, if (until > clock.elapsed) until - clock.elapsed else Long.MAX_VALUE)
        if (nextDelay != Long.MAX_VALUE) handler.postDelayed(updateBoundary, nextDelay.coerceAtLeast(1L))
    }

    private fun updateCompletion() {
        completedModes = completionStore.pending()
        completionDescription = completionStore.description()
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

    private fun requireHard(exact: Boolean = false): Boolean {
        val current = snapshot ?: return false
        if (!current.secure) {
            prompt = Prompt("Set up screen lock", "Set an Android PIN, pattern, or password first.", "Open settings") {
                openSettings(Settings.ACTION_SECURITY_SETTINGS)
            }
            return false
        }
        if (!current.admin) {
            enableAdmin()
            return false
        }
        if (exact && !current.alarms) {
            prompt = Prompt("Scheduled lock permission", "Allow Alarms & reminders to start locks at the scheduled time.", "Open settings") {
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
        if (!requireHard()) return
        val current = snapshot ?: return
        if (current.hard.active.isNotEmpty()) return
        if (test && current.hard.next - System.currentTimeMillis() <= Rule.MINUTE) {
            notify("A scheduled Hard Lock is about to start.")
            return
        }
        val notice = if (current.hardLockScreen) "" else
            "\n\nLock screen notifications are off. To see controls, enable Focus Lock notifications and show notifications on the Android lock screen."
        val details = when {
            test -> "Your phone will lock for one minute. Wake the screen and expand the Hard Lock test notification to check the controls. Tap End test to finish early."
            current.breaks.enabled -> "Your phone will lock for ${durationLabel(minutes)}. Wake the screen and expand the Hard Lock notification to take an emergency break."
            else -> "Your phone will lock for ${durationLabel(minutes)}."
        }
        prompt = Prompt(if (test) "Test Hard Lock?" else "Start Hard Lock?",
            details + notice, if (test) "Start test" else "Lock") {
            val clock = SessionClock(this)
            val latest = hardStore.state(clock)
            if (latest.active.isEmpty() && (!test || latest.next - clock.wall > Rule.MINUTE)) {
                val rule = Rule()
                rule.id = Rule.QUICK_ID
                rule.name = if (test) "Hard Lock test" else "Lock now"
                rule.test = test
                rule.timer = clock.start(minutes * Rule.MINUTE)
                rule.from = clock.wall
                rule.to = rule.timer.wallEnd
                hardStore.put(rule)
                refreshHard()
            } else notify("A Hard Lock is already active or about to start.")
        }
    }

    internal fun startSoft(test: Boolean = false) {
        val minutes = if (test) 1 else softDuration.total ?: return invalidDuration()
        val apps = if (allowlist) allowedApps else blockedApps
        if (!allowlist && apps.isEmpty() && blockedSites.isEmpty()) {
            notify("Add at least one app or website to block.")
            return
        }
        if (!requireSoftConnection()) return
        val summary = when {
            !allowlist -> "Your selected apps and websites will be blocked."
            apps.isEmpty() -> "Only essential features, including Home and Phone, will be available."
            else -> "Only your ${apps.size} selected apps and essential features will be available."
        }
        val usingAllowlist = allowlist
        val sites = if (usingAllowlist) emptySet() else blockedSites
        val strict = strictSites
        val returnHome = returnHomeOnBlock
        val notice = if (LockNotifications.available(this, true)) "" else
            "\nEnable Soft Lock notifications to see the countdown outside Focus Lock."
        val session = if (test) "1-minute test. You can end it at any time." else "${durationLabel(minutes)} session."
        prompt = Prompt(if (test) "Test Soft Lock?" else "Start Soft Lock?",
            "$session\n$summary\nAndroid Settings will be blocked.$notice",
            "Start") {
            if (requireSoftConnection() && SoftBlocker.activeTest() == null &&
                !softStore.read().active(SystemClock.elapsedRealtime())) {
                if (test) {
                    SoftBlocker.startTest(apps, sites, strict, usingAllowlist, returnHome)
                } else {
                    softStore.start(minutes * Rule.MINUTE, apps, sites, strict, usingAllowlist, returnHome)
                }
            }
        }
    }

    private fun requireSoftConnection(): Boolean {
        if (SoftBlocker.isConnected()) return true
        if (!SoftBlocker.enabled(this)) openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        else prompt = Prompt("Blocking is paused", "Reconnect the Focus Lock accessibility service to start a session.",
            "Open settings") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
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
        if (!b.enabled || b.remaining() == 0 || b.until > SystemClock.elapsedRealtime()) return
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
        if (requireHard(exact = true)) sheet = "schedule"
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
        schedule = null
    }

    internal fun saveRepeatingSchedule(rule: Rule, name: String, days: Int, start: Int, end: Int) {
        if (days == 0) return notify("Choose at least one day.")
        val draft = rule.withRepeatTimes(days, start, end)
        draft.name = name.trim().ifBlank { "Repeating lock" }
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

    internal fun previewSchedule(draft: Rule): Pair<SchedulePreview?, SchedulePreview?> =
        previewSchedule(draft.copy().apply { enabled = true }, SessionClock(this))

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
            return notify("Choose a time and duration so the lock ends in the future.")
        }
        if (draft.enabled && !requireHard(exact = true)) return
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

    private fun invalidDuration() = notify("Enter 0–24 hours and 0–59 minutes, for a total of 1 minute to 24 hours.")
    internal fun notify(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

