package com.focuslock.app

import android.os.SystemClock

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun FocusWorkspace(activity: MainActivity) {
    val state = activity.snapshot ?: return
    val completed = activity.completedModes
    val hardActive = state.hard.active.isNotEmpty() && state.admin && state.secure
    val dialogOpen = activity.sheet != null || activity.schedule != null || activity.prompt != null || activity.scheduleConfirmation != null
    if (completed != 0 && !hardActive && !state.keyguardLocked && !dialogOpen) {
        BackHandler(onBack = activity::dismissCompletion)
        CompletionScreen(completed, activity.completionDescription, activity::dismissCompletion)
        return
    }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().fillMaxHeight()) {
            WorkspaceHeader(Modifier.padding(start = 24.dp, end = 20.dp, top = 16.dp, bottom = 15.dp)) {
                activity.sheet = "settings"
            }
            ModeControl(activity.mode, { activity.mode = it }, Modifier.padding(horizontal = 24.dp))
            key(activity.mode) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 19.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (activity.mode == FocusMode.Hard) HardWorkspace(activity, state)
                    else SoftWorkspace(activity, state)
                    GroupCard {
                        ActionRow("${activity.mode.label} statistics", "Last 7 days", icon = "Chart") {
                            activity.sheet = "statistics"
                        }
                    }
                }
            }
        }
    }
    WorkspaceDialogs(activity, state)
}

@Composable
private fun WorkspaceHeader(modifier: Modifier = Modifier, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Focus Lock", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surface) {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Symbol("Sliders", "Settings", tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModeControl(mode: FocusMode, onChange: (FocusMode) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(colors.surfaceContainerHighest, RoundedCornerShape(15.dp))
            .padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        FocusMode.entries.forEach { item ->
            val selected = mode == item
            val fill by animateColorAsState(if (selected) colors.surface else colors.surfaceContainerHighest,
                label = "selected mode")
            Surface(Modifier.weight(1f), color = fill, shape = RoundedCornerShape(12.dp),
                shadowElevation = if (selected) 1.dp else 0.dp) {
                Box(
                    Modifier.selectable(selected = selected, role = Role.Tab, onClick = { onChange(item) })
                        .heightIn(min = 44.dp).padding(vertical = 7.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.label, style = MaterialTheme.typography.labelLarge,
                        color = if (selected) colors.onSurface else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CardHeading(icon: String, title: String, subtitle: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(43.dp).background(colors.primaryContainer, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center) { Symbol(icon, tint = colors.primary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Caption(subtitle)
        }
    }
}

@Composable
private fun HardWorkspace(activity: MainActivity, state: FocusSnapshot) {
    val test = state.hard.active.any { it.rule.test }
    val onlyTest = test && state.hard.active.all { it.rule.test }
    val quick = state.hard.active.firstOrNull { it.rule.quick() && !it.rule.test }
    if (state.hardEndElapsed > 0) {
        val paused = !onlyTest && state.breaks.until > SystemClock.elapsedRealtime()
        val title = when {
            onlyTest -> "Hard Lock test"
            paused -> "Emergency break"
            state.hardEnforced -> "Hard Lock active"
            else -> "Hard Lock pending"
        }
        RunningCard(title, if (paused) minOf(state.hardEndElapsed, state.breaks.until) else state.hardEndElapsed,
            if (paused) breakDetail(state.hardEndElapsed, state.breaks.until) else "Phone locked until ${dateLabel(state.hard.activeEnd())}") {
            if (onlyTest) TextButton(onClick = { activity.hardAction(FocusService.END_TEST) }) { Text("End test") }
            if (!state.hardEnforced && state.admin && state.secure) TextButton(onClick = activity::refreshHard) { Text("Activate") }
            if (quick != null) TextButton(onClick = { activity.hardAction(FocusService.EXTEND) }) { Text(if (paused) "Add 10 min to lock" else "Add 10 min") }
        }
        if (!onlyTest) BreakRow(activity, state)
    } else {
        SessionCard(activity.quickDuration, { activity.sheet = "duration" },
            if (state.admin && state.secure) "Start Hard Lock" else "Set up Hard Lock", { activity.startHard() })
        TextButton(onClick = { activity.startHard(test = true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Test for 1 minute")
        }
    }

    if (!state.hardLockScreen && state.hard.active.isEmpty()) GroupCard {
        ActionRow("Check lock screen notifications", "Required to see Emergency break", icon = "Lock") {
            activity.sheet = "hard_notifications"
        }
    }

    ScheduleHeading(activity::openNewSchedule)
    val upcoming = state.hard.nextStart
    if (state.hard.active.isEmpty() && upcoming != null) {
        Caption("Next lock  ${windowLabel(upcoming)}")
    }
    val scheduled = state.rules.filterNot { it.quick() }
    if (scheduled.isEmpty()) {
        Caption("Add a one-time or repeating lock.")
    } else {
        scheduled.forEach { rule ->
            val active = state.hard.active.any { it.rule.id == rule.id }
            ScheduleCard(rule, active, { activity.schedule = rule }, { activity.toggleSchedule(rule) },
                { activity.deleteSchedule(rule) })
        }
    }
}

@Composable
private fun ScheduleHeading(onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Schedules", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAdd) {
            Symbol("Plus")
            Spacer(Modifier.width(5.dp))
            Text("Add")
        }
    }
}

@Composable
private fun SessionCard(duration: DurationInput, edit: () -> Unit, label: String, start: () -> Unit,
                        rules: (@Composable () -> Unit)? = null) {
    TimerCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Duration", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = edit) { Text("Edit") }
        }
        Text(durationLabel(duration.total ?: 30), Modifier.clickable(onClick = edit),
            style = TextStyle(fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-2).sp,
                fontWeight = FontWeight.Medium, fontFeatureSettings = "'tnum'"))
        rules?.invoke()
        PrimaryButton(label, start)
    }
}

private fun breakDetail(sessionEnd: Long, breakEnd: Long) =
    if (sessionEnd <= breakEnd + 1000) "Your session finishes during this break." else "Blocking resumes automatically when this timer ends."

@Composable
private fun BreakRow(activity: MainActivity, state: FocusSnapshot) {
    val b = state.breaks
    if (!b.enabled) return
    val left = b.remaining()
    val available = left > 0 && b.until <= SystemClock.elapsedRealtime()
    if (available) GroupCard {
        ActionRow("Emergency break", "${b.minutes} min · $left of ${b.limit} left", icon = "Clock", onClick = activity::offerBreak)
    } else Caption(if (left == 0) "All ${b.limit} breaks used for this session." else "$left of ${b.limit} breaks left.")
}

@Composable
private fun ScheduleCard(rule: Rule, active: Boolean, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val ended = !rule.repeats() && rule.to <= System.currentTimeMillis()
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(rule.name, style = MaterialTheme.typography.titleMedium)
            if (rule.repeats()) {
                Text("${timeLabel(rule.startMinute)} – ${timeLabel(rule.repeatEndMinute())}",
                    style = MaterialTheme.typography.headlineSmall)
                val endDay = if (rule.endsNextDay()) " · Ends next day" else ""
                Caption(daysLabel(rule.days) + endDay)
            } else {
                Caption("${dateLabel(rule.from)} · ${durationLabel(rule.requestedDuration())}")
            }
            if (active) {
                Text("Active", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            } else {
                if (!rule.enabled && !ended) Caption("Turned off")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = if (ended) onDelete else onToggle) {
                        Text(if (ended) "Delete" else if (rule.enabled) "Turn off" else "Turn on")
                    }
                }
            }
        }
    }
}

@Composable
private fun SoftWorkspace(activity: MainActivity, state: FocusSnapshot) {
    val session = state.soft
    if (session.active(SystemClock.elapsedRealtime())) {
        val summary = when {
            session.allowlist && session.apps.isEmpty() -> "Essential apps only"
            session.allowlist -> "Allowlist · " + quantity(session.apps.size, "app")
            else -> "Blocklist · " + quantity(session.apps.size, "app") + " · " + quantity(session.sites.size, "site")
        }
        val title = when {
            session.test -> "Soft Lock test"
            state.blockingConnected -> "Blocking active"
            else -> "Blocking paused"
        }
        val detail = if (state.blockingConnected) summary else "The timer continues while blocking is paused."
        val paused = !session.test && state.breaks.until > SystemClock.elapsedRealtime()
        RunningCard(if (paused) "Emergency break" else title,
            if (paused) minOf(state.breaks.until, session.timer.elapsedEnd) else session.timer.elapsedEnd,
            if (paused) breakDetail(session.timer.elapsedEnd, state.breaks.until) else detail) {
            when {
                session.test -> PrimaryButton("End test", SoftBlocker::endTest)
                !state.blockingConnected -> PrimaryButton("Reconnect", { activity.openSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) })
                else -> TextButton(onClick = { activity.sheet = "extend" }) { Text(if (paused) "Add session time" else "Add time") }
            }
        }
        if (!session.test) {
            GroupCard {
                ActionRow("Tighten rules", if (session.allowlist) "Remove allowed apps" else "Add blocked apps or websites") {
                    activity.openRuleTightening(state.softSessionId)
                }
            }
            BreakRow(activity, state)
        }
    } else {
        val count = if (activity.allowlist) activity.allowedApps.size else activity.blockedApps.size
        val empty = !activity.allowlist && count == 0 && activity.blockedSites.isEmpty()
        val label = when {
            empty -> "Choose what to block"
            !state.blockingConnected -> "Set up Soft Lock"
            else -> "Start Soft Lock"
        }
        SessionCard(activity.softDuration, { activity.sheet = "duration" }, label,
            { if (empty) activity.sheet = "rules" else activity.startSoft() }) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable { activity.sheet = "rules" }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (activity.allowlist) "Allowlist" else "Blocklist", style = MaterialTheme.typography.titleMedium)
                    Caption(if (empty) "Choose apps or websites" else if (activity.allowlist && count == 0) "Essential apps only"
                        else if (activity.allowlist) quantity(count, "app") else quantity(count, "app") + " · " + quantity(activity.blockedSites.size, "site"))
                }
                Symbol("Chev", tint = MaterialTheme.colorScheme.outline)
            }
        }
        TextButton(onClick = { if (empty) activity.sheet = "rules" else activity.startSoft(test = true) },
            modifier = Modifier.fillMaxWidth()) { Text("Test for 1 minute") }
        if (!state.softNotifications) CountdownPermissionCard(activity, soft = true)
    }

    if (android.os.Build.VERSION.SDK_INT >= 33 && !state.accessibility && !session.active(SystemClock.elapsedRealtime())) {
        TextButton(onClick = { activity.openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) {
            Text("Accessibility disabled? Check app info")
        }
    }
}

private fun quantity(count: Int, noun: String) = "$count $noun" + if (count == 1) "" else "s"

@Composable
private fun CountdownPermissionCard(activity: MainActivity, soft: Boolean) {
    GroupCard {
        ActionRow("Enable countdown notifications", onClick = { activity.enableNotifications(soft) })
    }
}

@Composable
internal fun SoftPolicyCard(allowlist: Boolean, select: (Boolean) -> Unit) {
    GroupCard {
        Text("Restriction type", Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 3.dp),
            style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("Blocklist", "Block selected apps and websites.", !allowlist) { select(false) }
            InsetDivider()
            ChoiceRow("Allowlist", "Use only the apps you select.", allowlist) { select(true) }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, description: String, selected: Boolean, activate: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = activate, role = Role.RadioButton)
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Caption(description)
        }
        if (selected) {
            Box(Modifier.size(28.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) { Symbol("Check", tint = colors.primary) }
        } else Spacer(Modifier.size(28.dp))
    }
}

@Composable
internal fun ActionRow(title: String, description: String? = null, value: String? = null, icon: String? = null,
    onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 54.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (icon != null) Symbol(icon, tint = colors.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Caption(description)
        }
        if (value != null) Caption(value)
        Symbol("Chev", tint = colors.outline)
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChecked, enabled = enabled)
    }
}

@Composable
internal fun InsetDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun RunningCard(title: String, end: Long, detail: String, actions: @Composable () -> Unit) {
    val time by produceState(initialValue = SystemClock.elapsedRealtime(), end) {
        value = SystemClock.elapsedRealtime()
        while (value < end) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val seconds = ((end - time + 999) / 1000).coerceAtLeast(0)
    TimerCard {
        CardHeading("Clock", title)
        Text(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60),
            style = TextStyle(fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.Medium,
                letterSpacing = (-1).sp, fontFeatureSettings = "'tnum'"))
        Caption(detail)
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 780)
@Composable
private fun HomePreview() {
    FocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                WorkspaceHeader {}
                ModeControl(FocusMode.Hard, {})
                SessionCard(DurationInput(), {}, "Start Hard Lock", {})
                ScheduleHeading {}
                Caption("Add a one-time or repeating lock.")
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 820)
@Composable
private fun AllowlistPreview() {
    FocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WorkspaceHeader {}
                ModeControl(FocusMode.Soft, {})
                SessionCard(DurationInput(), {}, "Start Soft Lock", {}) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Allowlist", style = MaterialTheme.typography.titleMedium)
                    Caption("Essential apps only")
                }
                TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Test for 1 minute") }
            }
        }
    }
}
