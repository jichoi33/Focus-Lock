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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
            var presetRevision by remember { mutableIntStateOf(0) }
            FocusDialog("Session duration", close, {
                if (value.total == null) activity.notify("Enter a duration between 1 minute and ${Rule.MAX_LOCK_DAYS} days.")
                else {
                    if (hard) activity.quickDuration = value else activity.softDuration = value
                    close()
                }
            }) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15, 30, 60).forEach { minutes ->
                            FilterChip(
                                selected = value.total == minutes,
                                onClick = {
                                    value = DurationInput.of(minutes)
                                    // Replace the wheels so a previous fling cannot override the preset.
                                    presetRevision++
                                },
                                label = { Text(durationLabel(minutes)) }
                            )
                        }
                    }
                    key(presetRevision) {
                        DurationFields(value) { value = it }
                    }
                }
            }
        }
        "rules" -> FocusDialog("Soft Lock rules", close) {
            Column(Modifier.heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftPolicyCard(activity.allowlist, { activity.allowlist = it })
                GroupCard {
                    ActionRow(if (activity.allowlist) "Allowed apps" else "Blocked apps",
                        value = (if (activity.allowlist) activity.allowedApps.size else activity.blockedApps.size).toString()) { activity.sheet = "apps" }
                    if (activity.allowlist) {
                        InsetDivider()
                        SwitchRow("Allow in-app browser tabs", activity.allowBrowserTabs, { activity.allowBrowserTabs = it })
                        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                            Caption("Open links from allowed apps in supported in-app tabs. To open the full browser, add it to Allowed apps.")
                        }
                    } else {
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
            }
            PrimaryButton("Done", close)
        }
        "statistics" -> StatisticsDialog(activity, activity.mode, close)
        "celebration" -> FocusDialog("Celebration on unlock", close) {
            Caption("Show the celebration when you first unlock after Hard Lock ends. You can also open it from the completion notification.")
            if (!activity.autoCelebration && !SessionCompletion.canOpenOnUnlock(activity)) {
                Caption("In Android settings, select Focus Lock and allow it to display over other apps so it can open the celebration from the background.")
            }
            PrimaryButton(if (activity.autoCelebration) "Turn off" else "Enable on unlock", {
                activity.setAutoCelebration(!activity.autoCelebration)
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
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    DurationFields(duration) { duration = it }
                }
            }
        }
        "apps" -> {
            val allow = activity.allowlist
            val entries = pickerApps(activity, allow) ?: return
            val selected = if (allow) activity.allowedApps else activity.blockedApps
            AppPickerDialog(activity, if (allow) "Allowed apps" else "Blocked apps", entries, selected, allowBulkSelection = allow,
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
        val dismiss = activity::dismissScheduleReview
        val active = confirmation.active
        val next = confirmation.next
        var defer by remember(confirmation) { mutableStateOf(active != null && next != null) }
        val first = if (defer) next else active ?: next
        FocusDialog("Check your first lock", dismiss, { confirmation.apply(defer) }, "Save schedule") {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Check when the lock starts and when you can use your phone again.")
                if (!confirmation.enabled) {
                    Caption("This schedule stays off until you turn it on.")
                }
                if (active != null && next != null) {
                    Text("First lock", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(defer, { defer = true }, label = { Text("Next occurrence") })
                        FilterChip(!defer, { defer = false }, label = { Text("Start now") })
                    }
                }
                if (confirmation.continuous) {
                    Text("This schedule runs every day without a planned gap between locks.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                first?.let {
                    val startsNow = active != null && !defer
                    val title = when {
                        startsNow -> "Locks immediately"
                        !confirmation.enabled -> "Lock window if enabled"
                        else -> "Next occurrence"
                    }
                    SchedulePreviewCard(activity, title, it, startsNow, confirmation.enabled)
                }
                if (active != null && !defer && next != null) {
                    SchedulePreviewCard(activity, "Next occurrence", next)
                }
            }
            if (confirmation.enabled && active != null && !defer) {
                Text("Saving will lock your phone immediately.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    activity.hardStartReview?.let { review ->
        val dismiss = { activity.hardStartReview = null }
        FocusDialog("Start Hard Lock?", dismiss, {
            dismiss()
            review.confirm()
        }, review.action) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (review.updated) Caption("The unlock time or schedule changed. Check it before locking.")
                Text(review.unlock?.let { "Unlock at $it" } ?: "No scheduled unlock in the next 7 days",
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Caption("${durationLabel(review.minutes)} timer")
                review.extension?.let { Caption(it) }
            }
        }
    }
    activity.prompt?.let { prompt ->
        val dismiss = { activity.prompt = null }
        val cancel = { dismiss(); prompt.cancel() }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(prompt.title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prompt.durationMinutes?.let { minutes ->
                        val now by produceState(System.currentTimeMillis(), prompt) {
                            while (true) {
                                value = System.currentTimeMillis()
                                delay(Rule.MINUTE - value % Rule.MINUTE)
                            }
                        }
                        DeadlineText(ClockLabels.deadline(now + minutes * Rule.MINUTE, now, activity.use24Hour, "Blocking ends"))
                    }
                    Text(prompt.text, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { dismiss(); prompt.confirm() }) { Text(prompt.action) } },
            dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
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
            Text("Soft Lock preferences", style = MaterialTheme.typography.titleMedium)
            GroupCard { ActionRow("Emergency breaks", if (b.enabled) "${b.minutes} min each, ${b.limit} per 24 hours" else "Off", icon = "Clock") {
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
                ActionRow("Celebration on unlock", if (activity.autoCelebration) "On" else "Off") {
                    activity.sheet = "celebration"
                }
                InsetDivider()
                ActionRow("Scheduled lock reminders", "5 minutes before Hard Lock",
                    onClick = activity::openScheduleReminderSettings)
            }
            LockNotificationSetup(activity, state)
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
                    label = { Text("Breaks per 24 hours") }, enabled = !locked, singleLine = true, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
            }
            Caption(if (locked) "Change these limits after your session ends." else "Your session timer keeps running during a break.")
            if (enabled) {
                Caption("Breaks refill every 24 hours from the start of a continuing lock.")
                Caption("During Soft Lock, tap Emergency break in Focus Lock or its expanded notification.")
            }
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
            val installed = pickerApps(activity, session.allowlist) ?: return
            val entries = remember(installed, session.apps, session.allowlist) {
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
private fun pickerApps(activity: MainActivity, allowlist: Boolean): List<AppCatalog.Entry>? {
    val apps by produceState<List<AppCatalog.Entry>?>(null, activity, allowlist) {
        value = withContext(Dispatchers.IO) { AppCatalog.selectable(activity, allowlist) }
    }
    if (apps == null) FocusDialog("Loading apps", { activity.sheet = null }) { LinearProgressIndicator() }
    return apps
}

@Composable
private fun AppPickerDialog(
    activity: MainActivity,
    title: String,
    applications: List<AppCatalog.Entry>,
    initialSelection: Set<String>,
    close: () -> Unit,
    action: String = "Apply",
    allowBulkSelection: Boolean = false,
    applySelection: (Set<String>) -> Unit
) {
    var selection by rememberSaveable(title, stateSaver = Saver<Set<String>, ArrayList<String>>(
        save = { ArrayList(it) }, restore = { it.toSet() }
    )) { mutableStateOf(initialSelection) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (selection == initialSelection) close() else confirmDiscard = true }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var allQuery by rememberSaveable { mutableStateOf("") }
    var selectedQuery by rememberSaveable { mutableStateOf("") }
    val allListState = rememberLazyListState()
    val selectedListState = rememberLazyListState()
    val query = (if (selectedOnly) selectedQuery else allQuery).trim()
    val allEntries = remember(applications, initialSelection) {
        val ids = applications.mapTo(mutableSetOf()) { it.id }
        // Keep older selections visible even if an app has been removed.
        applications + (initialSelection - ids).sorted().map { AppCatalog.Entry(it, it) }
    }
    val duplicateLabels = remember(allEntries) { allEntries.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys }
    val entries = if (selectedOnly) allEntries else applications
    val filtered = remember(entries, query, if (selectedOnly) selection else null) {
        entries.filter { entry ->
            (!selectedOnly || entry.id in selection) &&
                (query.isEmpty() || entry.label.contains(query, ignoreCase = true) ||
                    entry.id.contains(query, ignoreCase = true))
        }
    }
    val height = dialogBodyHeight()
    FocusDialog(title, requestClose, { applySelection(selection) }, "$action (${selection.size})") {
        OutlinedTextField(
            if (selectedOnly) selectedQuery else allQuery,
            { if (selectedOnly) selectedQuery = it else allQuery = it },
            label = { Text(if (selectedOnly) "Search selected apps" else "Search apps") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
        )
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !selectedOnly, onClick = { selectedOnly = false },
                modifier = Modifier.semantics { role = Role.RadioButton }, label = { Text("All apps") })
            FilterChip(selected = selectedOnly, onClick = { selectedOnly = true },
                modifier = Modifier.semantics { role = Role.RadioButton }, label = { Text("Selected (${selection.size})") })
        }
        if (filtered.isEmpty()) {
            Caption(when {
                selectedOnly && selection.isEmpty() -> "No apps selected yet."
                query.isNotEmpty() -> "No apps match your search."
                else -> "No apps available."
            })
        } else GroupCard(Modifier.weight(1f, fill = false)) {
            LazyColumn(
                state = if (selectedOnly) selectedListState else allListState,
                modifier = Modifier.heightIn(max = height.coerceAtMost(360.dp))
            ) {
                if (allowBulkSelection && !selectedOnly) {
                    item {
                        val ids = remember(filtered) { filtered.mapTo(mutableSetOf()) { it.id } }
                        val allSelected = selection.containsAll(ids)
                        val label = when {
                            query.isEmpty() && allSelected -> "Deselect all apps"
                            query.isEmpty() -> "Select all apps"
                            allSelected -> "Deselect search results"
                            else -> "Select search results"
                        }
                        TextButton(onClick = {
                            selection = if (allSelected) selection - ids else selection + ids
                        }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        InsetDivider()
                    }
                }
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
                        Column(Modifier.weight(1f)) {
                            Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                            if (entry.label in duplicateLabels) Caption(entry.id)
                        }
                        Checkbox(checked, onCheckedChange = null)
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard app selection changes?") },
        confirmButton = { TextButton(onClick = close) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } }
    )
}

@Composable
private fun SitesDialog(
    title: String,
    initialSites: Set<String>,
    close: () -> Unit,
    action: String = "Apply",
    applySites: (Set<String>) -> Unit
) {
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialSites.joinToString("\n")))
    }
    var errorLine by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    FocusDialog(title, close, {
        val hosts = mutableSetOf<String>()
        var offset = 0
        errorLine = 0
        for ((index, line) in input.text.lineSequence().withIndex()) {
            val start = offset
            if (line.isNotBlank()) {
                val host = BlockRules.domain(line.trim())
                if (host.isEmpty()) {
                    errorLine = index + 1
                    focus.requestFocus()
                    input = input.copy(selection = TextRange(start, start + line.length))
                    break
                }
                hosts.add(host)
            }
            offset = start + line.length
            if (input.text.getOrNull(offset) == '\r') offset++
            if (input.text.getOrNull(offset) == '\n') offset++
        }
        if (errorLine == 0) applySites(hosts)
    }, action) {
        Caption("Enter one domain per line. Subdomains are included.")
        OutlinedTextField(input, {
            if (it.text != input.text) errorLine = 0
            input = it
        }, placeholder = { Text("youtube.com\nreddit.com") }, minLines = 3, maxLines = 6,
            isError = errorLine != 0,
            supportingText = if (errorLine != 0) ({ Text("Check the address on line $errorLine.") }) else null,
            modifier = Modifier.fillMaxWidth().focusRequester(focus), shape = RoundedCornerShape(14.dp))
        Caption("Focus Lock reads visible addresses in supported browsers. A page may begin loading before the block appears.")
    }
}

@Composable
private fun ScheduleDialog(activity: MainActivity, rule: Rule) {
    val repeating = rule.repeats()
    val originalName = rule.name.orEmpty().takeUnless { repeating && it == DEFAULT_REPEAT_NAME }.orEmpty()
    val originalDuration = DurationInput.of(rule.requestedDuration().coerceIn(1, Rule.MAX_LOCK_MINUTES))
    var name by rememberSaveable { mutableStateOf(originalName) }
    var preset by rememberSaveable { mutableStateOf(rule.days.takeIf { it in repeatDayPresets }) }
    var customDays by rememberSaveable { mutableStateOf(rule.days.takeUnless { it in repeatDayPresets }) }
    val days = preset ?: customDays ?: rule.days
    var startMinute by rememberSaveable { mutableIntStateOf(rule.startMinute) }
    var endMinute by rememberSaveable { mutableIntStateOf(rule.repeatEndMinute()) }
    var firstAt by rememberSaveable { mutableLongStateOf(rule.from) }
    var duration by rememberSaveable(stateSaver = listSaver(
        save = { listOf(it.hours, it.minutes) },
        restore = { DurationInput(it[0], it[1]) }
    )) { mutableStateOf(originalDuration) }
    var dateDialog by rememberSaveable { mutableStateOf(false) }
    var timeField by rememberSaveable { mutableStateOf<String?>(null) }
    var dateDay by rememberSaveable {
        mutableLongStateOf(Instant.ofEpochMilli(firstAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay())
    }
    val date = LocalDate.ofEpochDay(dateDay)
    val changed = name != originalName || if (repeating) {
        days != rule.days || startMinute != rule.startMinute || endMinute != rule.repeatEndMinute()
    } else {
        firstAt != rule.from || duration != originalDuration
    }
    val close = {
        if (changed) activity.prompt = Prompt("Discard schedule edits?", "Your unsaved changes will be lost.",
            "Discard changes", confirm = activity::dismissSchedule)
        else activity.dismissSchedule()
    }
    FocusDialog(if (repeating) "Repeating lock" else "One-time lock", close, {
        if (repeating) activity.saveRepeatingSchedule(rule, name, days, startMinute, endMinute)
        else activity.saveOneTimeSchedule(rule, name, firstAt, duration)
    }, "Save schedule") {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = dialogBodyHeight())
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (repeating) {
                Text("Lock window", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val overnight = endMinute <= startMinute
                    ScheduleTimeField("Starts at", activity.timeLabel(startMinute), Modifier.weight(1f).fillMaxHeight()) {
                        timeField = "Start time"
                    }
                    ScheduleTimeField("Ends at", activity.timeLabel(endMinute), Modifier.weight(1f).fillMaxHeight(),
                        nextDay = overnight) { timeField = "End time" }
                }
                Text("Starts on", style = MaterialTheme.typography.titleMedium)
                ScheduleDayPresets(preset) { selection ->
                    if (selection == null && customDays == null) customDays = days
                    preset = selection
                }
                if (preset == null) {
                    ScheduleDayPicker(days) { customDays = it }
                }
                if (days == 0) Caption("Choose at least one start day.")
                if (days == 127 && startMinute == endMinute) {
                    Text("Runs every day without a planned gap.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                GroupCard {
                    ActionRow("Start", value = activity.dateLabel(firstAt)) { dateDialog = true }
                }
                DurationFields(duration) { duration = it }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Schedule name (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (rule.name != null) {
                TextButton(onClick = { activity.deleteSchedule(rule) }) {
                    Text("Delete schedule", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (dateDialog) {
        DateDialog(date, { dateDialog = false }) { selected ->
            dateDay = selected.toEpochDay()
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
        ScheduleTimeDialog(title, initial, activity.use24Hour, { timeField = null }) { selected ->
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
private fun ScheduleDayPresets(selected: Int?, onSelect: (Int?) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val choices = repeatDayPresets.entries.map { (mask, label) -> mask to label } + (null to "Custom")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 232.dp && fontScale <= 1.3f) 2 else 1
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (mask, label) ->
                        FilterChip(
                            selected = selected == mask,
                            onClick = { onSelect(mask) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleDayPicker(days: Int, onChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val diameter = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val oneRow = maxWidth >= diameter * 7
        val gap = if (oneRow) ((maxWidth - diameter * 7) / 6).coerceAtMost(6.dp) else 6.dp
        val perRow = if (oneRow) 7 else ((maxWidth + gap) / (diameter + gap)).toInt().coerceIn(1, 4)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap)) {
            focusDays.indices.chunked(perRow).forEach { weekRow ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    weekRow.forEach { index ->
                        val bit = 1 shl index
                        val selected = days and bit != 0
                        Surface(checked = selected, onCheckedChange = { onChange(days xor bit) },
                            modifier = Modifier.size(diameter).semantics { role = Role.Checkbox },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = if (selected) colors.primary else colors.surfaceContainer,
                            contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                if (selected) Symbol("Check", modifier = Modifier.size(12.dp), tint = colors.onPrimary)
                                Text(focusDays[index], style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulePreviewCard(activity: MainActivity, title: String, preview: SchedulePreview,
                                startsNow: Boolean = false, enabled: Boolean = true) {
    val start = preview.window.start
    val end = preview.window.end
    val startLabel = activity.dateLabel(start)
    val endLabel = ClockLabels.unlockTime(end, start, activity.use24Hour)
    val unlockLabel = if (preview.release == 0L) "No unlock in the next 7 days"
        else activity.dateLabel(preview.release)
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(title)
            val release = when {
                preview.release == 0L -> unlockLabel
                enabled -> "Phone unlocks on $unlockLabel"
                else -> "Would unlock on $unlockLabel"
            }
            Text(release,
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (preview.release == end) {
                Caption("Lock starts ${if (startsNow) "now" else startLabel}")
            } else {
                Caption("Schedule runs $startLabel to $endLabel.")
                Caption("Overlapping locks keep your phone locked past this window.")
            }
        }
    }
}

@Composable
private fun ScheduleTimeField(label: String, time: String, modifier: Modifier,
                              nextDay: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(time, style = MaterialTheme.typography.titleLarge)
            if (nextDay) Text("Next day", style = MaterialTheme.typography.labelMedium)
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
private fun ScheduleTimeDialog(title: String, initial: Int, use24Hour: Boolean, dismiss: () -> Unit, onTime: (Int) -> Unit) {
    val picker = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = use24Hour)
    FocusDialog(title, dismiss, { onTime(picker.hour * 60 + picker.minute) }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimeInput(picker) }
    }
}

@Composable
private fun dialogBodyHeight() = (LocalConfiguration.current.screenHeightDp - 190).coerceIn(170, 640).dp
