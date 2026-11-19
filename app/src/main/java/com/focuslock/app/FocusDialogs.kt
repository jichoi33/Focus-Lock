@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.focuslock.app

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
internal fun WorkspaceDialogs(activity: MainActivity, state: FocusSnapshot) {
    val close = { activity.sheet = null }
    when (activity.sheet) {
        "settings" -> SettingsDialog(activity, state, close)
        "duration" -> {
            val hard = activity.mode == FocusMode.Hard
            var value by remember { mutableStateOf(if (hard) activity.quickDuration else activity.softDuration) }
            FocusDialog("Session duration", close, {
                if (value.total == null) activity.notify("Enter a duration between 1 minute and 24 hours.")
                else {
                    if (hard) activity.quickDuration = value else activity.softDuration = value
                    close()
                }
            }) { DurationFields(value) { value = it } }
        }
        "rules" -> FocusDialog("Soft Lock rules", close) {
            Column(Modifier.heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftPolicyCard(activity.allowlist, { activity.allowlist = it })
                GroupCard {
                    ActionRow(if (activity.allowlist) "Allowed apps" else "Blocked apps",
                        value = (if (activity.allowlist) activity.allowedApps.size else activity.blockedApps.size).toString()) { activity.sheet = "apps" }
                    if (!activity.allowlist) {
                        InsetDivider()
                        ActionRow("Blocked websites", value = activity.blockedSites.size.toString()) { activity.sheet = "sites" }
                        if (activity.blockedSites.isNotEmpty()) {
                            SwitchRow("Block unreadable browsers", activity.strictSites, { activity.strictSites = it })
                            if (activity.strictSites) {
                                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                                    Caption("Samsung Internet requires a readable address bar.")
                                }
                            }
                        }
                    }
                    InsetDivider()
                    SwitchRow("Return to Home", activity.returnHomeOnBlock, { activity.returnHomeOnBlock = it })
                }
                Caption("Home, Phone, and Focus Lock remain available. Android Settings is blocked during the session.")
            }
            PrimaryButton("Done", close)
        }
        "statistics" -> StatisticsDialog(activity, activity.mode, close)
        "hard_notifications" -> FocusDialog("Hard Lock notifications", close) {
            Column(Modifier.heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Show notification details on the lock screen. Expand the Hard Lock notification to see its controls.")
                GroupCard {
                    ActionRow("Hard Lock notification settings", "Enable notifications and pop-up display") {
                        activity.enableNotifications(false)
                    }
                    InsetDivider()
                    ActionRow("Android lock screen settings") { activity.openLockScreenSettings() }
                    InsetDivider()
                    ActionRow("Do Not Disturb settings", "Allow Focus Lock as an app exception") {
                        activity.openSettings("android.settings.ZEN_MODE_SETTINGS")
                    }
                }
                Caption("In Do Not Disturb, allow pop-ups for permitted notifications in your active mode.")
            }
            PrimaryButton("Preview pop-up", {
                close()
                if (LockNotifications.available(activity, false)) LockNotifications.previewHard(activity)
                else activity.enableNotifications(false)
            })
        }
        "breaks" -> BreaksDialog(activity, state, close)
        "schedule" -> FocusDialog("Add schedule", close) {
            GroupCard {
                ActionRow("One-time lock") { activity.startScheduleDraft(false) }
                InsetDivider()
                ActionRow("Repeating lock", "Choose days and a time range") { activity.startScheduleDraft(true) }
            }
        }
        "extend" -> {
            var duration by remember { mutableStateOf(DurationInput.of(10)) }
            FocusDialog("Extend session", close, onConfirm = { activity.extendSoft(duration) }, confirmLabel = "Add time") {
                DurationFields(duration) { duration = it }
            }
        }
        "apps" -> {
            val allow = activity.allowlist
            val entries = remember(activity) { AppCatalog.launchable(activity) }
            val selected = if (allow) activity.allowedApps else activity.blockedApps
            AppPickerDialog(activity, if (allow) "Allowed apps" else "Blocked apps", entries, selected,
                close = { activity.sheet = "rules" }) {
                if (allow) activity.allowedApps = it else activity.blockedApps = it
                activity.sheet = "rules"
            }
        }
        "sites" -> SitesDialog("Blocked websites", activity.blockedSites, close = { activity.sheet = "rules" }) {
            activity.blockedSites = it
            activity.sheet = "rules"
        }
        "tighten", "tighten-apps", "tighten-sites" -> TightenRulesDialog(activity, state, close)
    }
    activity.schedule?.let { rule ->
        key(rule.id) { ScheduleDialog(activity, rule) }
    }
    activity.scheduleConfirmation?.let { confirmation ->
        val dismiss = { activity.scheduleConfirmation = null }
        FocusDialog(if (confirmation.active == null) "Confirm repeating lock" else "This lock can start now", dismiss) {
            if (confirmation.continuous) {
                Text("This schedule runs every day without a planned gap between locks.",
                    color = MaterialTheme.colorScheme.error)
            }
            confirmation.active?.let { window ->
                Text("Start now", style = MaterialTheme.typography.titleMedium)
                Caption("Locks immediately until ${scheduleDateLabel(window.end)}.")
            }
            val next = confirmation.next
            if (next != null) {
                Text("Next occurrence", style = MaterialTheme.typography.titleMedium)
                Caption(windowLabel(next))
            }
            if (confirmation.active != null) {
                PrimaryButton("Start now", { confirmation.apply(false) })
                if (next != null) {
                    OutlinedButton(onClick = { confirmation.apply(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start next occurrence")
                    }
                }
            } else {
                PrimaryButton("Save schedule", { confirmation.apply(false) })
            }
        }
    }
    activity.prompt?.let { prompt ->
        val dismiss = { activity.prompt = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(prompt.title, style = MaterialTheme.typography.headlineSmall) },
            text = { Text(prompt.text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { dismiss(); prompt.confirm() }) { Text(prompt.action) } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(25.dp)
        )
    }
}

@Composable
internal fun FocusDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    confirmLabel: String = "Apply",
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(27.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        IconButton(onClick = onDismiss) { Symbol("Close", "Close") }
                    }
                    content()
                    if (onConfirm != null) PrimaryButton(confirmLabel, onConfirm)
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(activity: MainActivity, state: FocusSnapshot, close: () -> Unit) {
    val height = dialogBodyHeight()
    FocusDialog("Settings", close) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val b = state.breaks
            Text("Session preferences", style = MaterialTheme.typography.titleMedium)
            GroupCard { ActionRow("Emergency breaks", if (b.enabled) "${b.minutes} min each · ${b.limit} per session" else "Off", icon = "Clock") {
                activity.sheet = "breaks"
            } }
            if (!state.secure || !state.admin || !state.alarms) {
                Text("Hard Lock", style = MaterialTheme.typography.titleMedium)
                GroupCard {
                    if (!state.secure) {
                        ActionRow("Set up screen lock") { activity.openSettings(Settings.ACTION_SECURITY_SETTINGS) }
                        if (!state.admin || !state.alarms) InsetDivider()
                    }
                    if (!state.admin) {
                        ActionRow("Enable device admin", onClick = activity::enableAdmin)
                        if (!state.alarms) InsetDivider()
                    }
                    if (!state.alarms) {
                        ActionRow("Allow alarms and reminders") { activity.openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }
                    }
                }
            }

            GroupCard {
                ActionRow("Lock screen notifications", "Hard Lock countdown and controls") {
                    activity.sheet = "hard_notifications"
                }
                InsetDivider()
                ActionRow("Scheduled lock reminders", "5 minutes before Hard Lock",
                    onClick = activity::openScheduleReminderSettings)
            }
            if (!state.softNotifications) {
                GroupCard {
                    ActionRow("Enable Soft Lock notifications") { activity.enableNotifications(soft = true) }
                }
            }
            if (!state.accessibility) {
                Text("Soft Lock", style = MaterialTheme.typography.titleMedium)
                GroupCard {
                    ActionRow("Open accessibility settings") { activity.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                    if (Build.VERSION.SDK_INT >= 33) {
                        InsetDivider()
                        ActionRow("Open app info", "In the menu, choose Allow restricted settings.") { activity.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreaksDialog(activity: MainActivity, state: FocusSnapshot, close: () -> Unit) {
    val b = state.breaks
    val locked = b.sessionEnd > 0 || state.soft.test
    var enabled by remember { mutableStateOf(b.enabled) }
    var minutes by remember { mutableStateOf(b.minutes.toString()) }
    var uses by remember { mutableStateOf(b.limit.toString()) }
    FocusDialog("Emergency breaks", close) {
        Column(Modifier.fillMaxWidth().heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow("Allow breaks", enabled, { enabled = it }, !locked)
            if (enabled) {
                val keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                OutlinedTextField(minutes, { if (it.length <= 2 && it.all(Char::isDigit)) minutes = it },
                    label = { Text("Minutes per break") }, enabled = !locked, singleLine = true, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(uses, { if (it.length <= 2 && it.all(Char::isDigit)) uses = it },
                    label = { Text("Breaks per session") }, enabled = !locked, singleLine = true, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
            }
            Caption(if (locked) "Change these limits after your session ends." else "Your session timer keeps running during a break.")
        }
        if (locked) PrimaryButton("Done", close)
        else PrimaryButton("Save", { activity.configureBreaks(enabled, if (enabled) minutes.toIntOrNull() ?: 0 else b.minutes,
            if (enabled) uses.toIntOrNull() ?: 0 else b.limit) })
    }
}

@Composable
private fun TightenRulesDialog(activity: MainActivity, state: FocusSnapshot, close: () -> Unit) {
    val session = state.soft
    val sessionId = state.softSessionId
    if (activity.tighteningSessionId != sessionId || session.test || !session.active(SystemClock.elapsedRealtime())) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val back = { activity.sheet = "tighten" }
    when (activity.sheet) {
        "tighten" -> FocusDialog("Tighten rules", close) {
            Caption("Changes apply to this session and are saved for future sessions.")
            GroupCard {
                ActionRow(if (session.allowlist) "Remove allowed apps" else "Add blocked apps", value = session.apps.size.toString()) {
                    activity.sheet = "tighten-apps"
                }
                if (!session.allowlist) {
                    InsetDivider()
                    ActionRow("Add blocked websites", value = session.sites.size.toString()) { activity.sheet = "tighten-sites" }
                }
            }
            PrimaryButton("Done", close)
        }
        "tighten-apps" -> {
            val entries = remember(activity, session.apps, session.allowlist) {
                val installed = AppCatalog.launchable(activity)
                if (session.allowlist) {
                    val byId = installed.associateBy { it.id }
                    session.apps.map { byId[it] ?: AppCatalog.Entry(it, it) }.sortedBy { it.label.lowercase() }
                } else installed.filter { it.id !in session.apps }
            }
            val title = if (session.allowlist) "Remove allowed apps" else "Add blocked apps"
            AppPickerDialog(activity, title, entries, emptySet(), close = back,
                action = if (session.allowlist) "Remove" else "Add") {
                activity.tightenSoft(sessionId, session.allowlist, apps = it)
            }
        }
        "tighten-sites" -> if (!session.allowlist) {
            SitesDialog("Add blocked websites", emptySet(), close = back, action = "Add websites") {
                activity.tightenSoft(sessionId, allowlist = false, sites = it)
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    activity: MainActivity,
    title: String,
    applications: List<AppCatalog.Entry>,
    initialSelection: Set<String>,
    close: () -> Unit,
    action: String = "Apply",
    applySelection: (Set<String>) -> Unit
) {
    var selection by remember(title) { mutableStateOf(initialSelection) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, applications) {
        if (query.isBlank()) applications else applications.filter { entry ->
            entry.label.contains(query.trim(), ignoreCase = true) || entry.id.contains(query.trim(), ignoreCase = true)
        }
    }
    val height = dialogBodyHeight()
    FocusDialog(title, close, { applySelection(selection) }, "$action (${selection.size})") {
        OutlinedTextField(query, { query = it }, label = { Text("Search apps") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        if (filtered.isEmpty()) Caption("No apps match your search.")
        else GroupCard {
            LazyColumn(Modifier.heightIn(max = height.coerceAtMost(360.dp))) {
                items(filtered, key = { it.id }) { entry ->
                    val icon = remember(entry.id) {
                        runCatching { activity.packageManager.getApplicationIcon(entry.id).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                    }
                    val checked = entry.id in selection
                    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { value ->
                        selection = if (value) selection + entry.id else selection - entry.id
                    }.padding(horizontal = 13.dp, vertical = 9.dp).heightIn(min = 42.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(30.dp))
                        else Spacer(Modifier.size(30.dp))
                        Text(entry.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Checkbox(checked, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun SitesDialog(
    title: String,
    initialSites: Set<String>,
    close: () -> Unit,
    action: String = "Apply",
    applySites: (Set<String>) -> Unit
) {
    var input by remember { mutableStateOf(initialSites.joinToString("\n")) }
    var error by remember { mutableStateOf(false) }
    FocusDialog(title, close, {
        val hosts = input.lineSequence().map(String::trim).filter(String::isNotEmpty).map(BlockRules::domain).toList()
        if (hosts.any(String::isEmpty)) error = true
        else applySites(hosts.toSet())
    }, action) {
        Caption("Enter one domain per line. Subdomains are included.")
        OutlinedTextField(input, { input = it; error = false },
            placeholder = { Text("youtube.com\nreddit.com") }, minLines = 3, maxLines = 6,
            isError = error,
            supportingText = if (error) ({ Text("Check the domain or web address.") }) else null,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Caption("Focus Lock reads visible addresses in supported browsers. A page may begin loading before the block appears.")
    }
}

@Composable
private fun ScheduleDialog(activity: MainActivity, rule: Rule) {
    val repeating = rule.repeats()
    val close = activity::dismissSchedule
    var name by remember { mutableStateOf(rule.name.orEmpty()) }
    var days by remember { mutableIntStateOf(rule.days) }
    var startMinute by remember { mutableIntStateOf(rule.startMinute) }
    var endMinute by remember { mutableIntStateOf(rule.repeatEndMinute()) }
    var firstAt by remember { mutableLongStateOf(rule.from) }
    var duration by remember { mutableStateOf(DurationInput.of(rule.requestedDuration().coerceIn(1, Rule.DAY))) }
    var dateDialog by remember { mutableStateOf(false) }
    var timeField by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(Instant.ofEpochMilli(firstAt).atZone(ZoneId.systemDefault()).toLocalDate()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(repeating) {
        if (repeating) while (true) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val draft = if (repeating && days != 0) rule.withRepeatTimes(days, startMinute, endMinute) else null
    val zone = ZoneId.systemDefault()
    val preview = remember(days, startMinute, endMinute, now, zone) {
        draft?.let { Rule.at(listOf(it.apply { enabled = true }), now, zone) }
    }

    FocusDialog(if (repeating) "Repeating lock" else "One-time lock", close, {
        if (repeating) activity.saveRepeatingSchedule(rule, name, days, startMinute, endMinute)
        else activity.saveOneTimeSchedule(rule, name, firstAt, duration)
    }, "Save") {
        Column(Modifier.fillMaxWidth().heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Schedule name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (repeating) {
                Text("Lock window", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleTimeField("Starts at", startMinute, Modifier.weight(1f)) { timeField = "Start time" }
                    ScheduleTimeField("Ends at", endMinute, Modifier.weight(1f)) { timeField = "End time" }
                }
                when {
                    endMinute == startMinute -> Caption("Ends at the same time the next day.")
                    endMinute < startMinute -> Caption("Ends the next day")
                }
                Text("Starts on", style = MaterialTheme.typography.titleMedium)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(31, 96, 127).forEach { preset ->
                        FilterChip(days == preset, { days = preset }, label = { Text(daysLabel(preset)) })
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    focusDays.forEachIndexed { index, day ->
                        val bit = 1 shl index
                        FilterChip(days and bit != 0, { days = days xor bit }, label = { Text(day) })
                    }
                }
                if (days == 0) Caption("Choose at least one start day.")
                preview?.let { state ->
                    if (draft?.repeatsWithoutGap() == true) {
                        Text("Runs every day without a planned gap.", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    state.latestActive()?.let {
                        if (rule.enabled) Caption("If started now, this lock ends ${scheduleDateLabel(it.end)}.")
                    }
                    state.nextStart?.let {
                        Text(if (rule.enabled) "Next occurrence" else "Next occurrence if enabled",
                            style = MaterialTheme.typography.titleMedium)
                        Caption(windowLabel(it))
                    }
                }
            } else {
                GroupCard {
                    ActionRow("Start", value = dateLabel(firstAt)) { dateDialog = true }
                }
                DurationFields(duration) { duration = it }
            }
            if (rule.name != null) {
                TextButton(onClick = { activity.deleteSchedule(rule) }) {
                    Text("Delete schedule", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (dateDialog) {
        DateDialog(date, { dateDialog = false }) { selected ->
            date = selected
            dateDialog = false
            timeField = "Start time"
        }
    }
    timeField?.let { title ->
        val localTime = Instant.ofEpochMilli(firstAt).atZone(ZoneId.systemDefault())
        val initial = when {
            !repeating -> localTime.hour * 60 + localTime.minute
            title == "End time" -> endMinute
            else -> startMinute
        }
        ScheduleTimeDialog(title, initial, { timeField = null }) { selected ->
            when {
                !repeating -> firstAt = Rule.atMinute(date, selected, ZoneId.systemDefault())
                title == "End time" -> endMinute = selected
                else -> startMinute = selected
            }
            timeField = null
        }
    }
}

@Composable
private fun ScheduleTimeField(label: String, minute: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(timeLabel(minute), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DateDialog(date: LocalDate, dismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(onDismissRequest = dismiss,
        confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                val selected = picker.selectedDateMillis ?: return@TextButton
                onDate(Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text("Next") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) {
        DatePicker(picker, modifier = Modifier.heightIn(max = dialogBodyHeight().coerceAtLeast(290.dp))
            .verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun ScheduleTimeDialog(title: String, initial: Int, dismiss: () -> Unit, onTime: (Int) -> Unit) {
    val picker = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
    FocusDialog(title, dismiss, { onTime(picker.hour * 60 + picker.minute) }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimeInput(picker) }
    }
}

@Composable
private fun dialogBodyHeight() = (LocalConfiguration.current.screenHeightDp - 190).coerceIn(170, 640).dp
