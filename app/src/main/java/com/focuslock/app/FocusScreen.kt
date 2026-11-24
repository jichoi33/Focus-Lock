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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun FocusWorkspace(activity: MainActivity) {
    val state = activity.snapshot ?: return
    val completed = activity.completedModes
    val hardActive = state.hard.active.isNotEmpty() && state.admin && state.secure
    val dialogOpen = activity.sheet != null || activity.schedule != null || activity.prompt != null || activity.scheduleConfirmation != null || activity.hardStartReview != null
    val showCompletion = completed != 0 && !hardActive && !state.keyguardLocked
    if (showCompletion && !dialogOpen) {
        BackHandler(onBack = activity::dismissCompletion)
        CompletionScreen(completed, activity.completionMillis, activity::dismissCompletion)
        return
    }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().fillMaxHeight()) {
            WorkspaceHeader(Modifier.padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
                activity.sheet = "settings"
            }
            ModeControl(activity.mode, { activity.mode = it }, Modifier.padding(horizontal = 24.dp),
                showHelp = !state.admin && !state.accessibility)
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
    if (showCompletion && activity.autoCelebration && completed and SessionCompletion.HARD != 0) {
        Dialog(
            onDismissRequest = activity::dismissCompletion,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            CompletionScreen(completed, activity.completionMillis, activity::dismissCompletion)
        }
    }
}

@Composable
private fun WorkspaceHeader(modifier: Modifier = Modifier, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Focus Lock", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surface) {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Symbol("Sliders", "Settings", tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModeControl(mode: FocusMode, onChange: (FocusMode) -> Unit, modifier: Modifier = Modifier,
                        showHelp: Boolean = false) {
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.label, style = MaterialTheme.typography.labelLarge,
                            color = if (selected) colors.onSurface else colors.onSurfaceVariant)
                        if (showHelp) Text(if (item == FocusMode.Hard) "Lock the whole phone" else "Block apps and sites",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
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
    val now by produceState(initialValue = System.currentTimeMillis(), state.hard) {
        value = System.currentTimeMillis()
        while (true) {
            delay(Rule.MINUTE - value % Rule.MINUTE)
            value = System.currentTimeMillis()
        }
    }
    val test = state.hard.active.any { it.rule.test }
    val onlyTest = test && state.hard.active.all { it.rule.test }
    val quick = state.hard.active.firstOrNull { it.rule.quick() && !it.rule.test }
    if (state.hardEndElapsed > 0) {
        val paused = !onlyTest && state.breaks.until > SystemClock.elapsedRealtime()
        val title = when {
            onlyTest -> "Hard Lock test"
            paused -> "Break time remaining"
            state.hardEnforced -> "Hard Lock active"
            else -> "Hard Lock pending"
        }
        val release = remember(state.hard, now) { activity.releaseAfter(state.hard, state.hard.activeEnd(), now) }
        val countdownEnd = if (onlyTest || release.end == 0L) state.hardEndElapsed
            else state.hardEndElapsed + (release.end - state.hard.activeEnd())
        val unlock = if (release.end == 0L) "No scheduled unlock in the preview window"
            else ClockLabels.deadline(release.end, now, activity.use24Hour, "Unlocks")
        val detail = if (paused) breakDetail(countdownEnd, state.breaks.until) else unlock
        RunningCard(title, if (paused) minOf(countdownEnd, state.breaks.until) else countdownEnd, detail,
            showCountdown = paused || onlyTest || release.end != 0L) {
            if (onlyTest) TextButton(onClick = { activity.hardAction(FocusService.END_TEST) }) { Text("End test") }
            if (!state.hardEnforced && state.admin && state.secure) TextButton(onClick = activity::refreshHard) { Text("Activate") }
            if (quick != null) TextButton(onClick = { activity.hardAction(FocusService.EXTEND) }) { Text(if (paused) "Add 10 min to lock" else "Add 10 min") }
        }
        if (!onlyTest) BreakRow(activity, state)
    } else {
        val minutes = activity.quickDuration.total ?: 30
        val end = now + minutes * Rule.MINUTE
        val release = remember(state.hard, end) { activity.releaseAfter(state.hard, end, now) }
        SessionCard(activity.quickDuration, { activity.sheet = "duration" },
            if (!state.admin || !state.secure) "Set up Hard Lock"
            else if (minutes > Rule.DAY) "Start ${minutes / Rule.DAY}-day lock" else "Start ${minutes}-min lock", { activity.startHard() }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(activity.unlockLabel(release, now), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                release.extension?.let {
                    val name = it.rule.name.orEmpty().ifBlank { "A scheduled lock" }
                    Caption("$name extends this lock.")
                }
            }
        }
        TextButton(onClick = { activity.startHard(test = true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Test for 1 minute")
        }
    }

    if (state.hard.active.isEmpty()) LockNotificationSetup(activity, state)

    ScheduleHeading(activity::openNewSchedule)
    val nextByRule = remember(state.hard) {
        state.hard.upcoming.groupBy { it.rule.id }.mapValues { (_, windows) -> windows.minBy { it.start } }
    }
    val activeIds = state.hard.active.map { it.rule.id }.toSet()
    val scheduled = state.rules.filterNot { it.quick() }.sortedWith(
        compareBy<Rule> { it.id !in activeIds }.thenBy { nextByRule[it.id]?.start ?: Long.MAX_VALUE }
    )
    if (scheduled.isEmpty()) {
        Caption("Add a one-time or repeating lock.")
    } else Column {
        scheduled.forEachIndexed { index, rule ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ScheduleCard(activity, rule, rule.id in activeIds,
                { activity.schedule = rule }, { activity.toggleSchedule(rule) }, { activity.deleteSchedule(rule) })
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Edit duration", onClick = edit),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption("Duration")
                    Text(durationLabel(duration.total ?: 30),
                        style = TextStyle(fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-2).sp,
                            fontWeight = FontWeight.Medium, fontFeatureSettings = "'tnum'"))
                }
                Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            rules?.invoke()
        }
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
        ActionRow("Emergency break", "${b.minutes} min each, $left of ${b.limit} left", icon = "Clock", onClick = activity::offerBreak)
    } else Caption(if (left == 0) "All ${b.limit} breaks used for this 24-hour period." else "$left of ${b.limit} breaks left.")
}

@Composable
private fun ScheduleCard(activity: MainActivity, rule: Rule, active: Boolean,
                         onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val ended = !rule.repeats() && rule.to <= System.currentTimeMillis()
    val colors = MaterialTheme.colorScheme
    val edit = if (active) Modifier else Modifier.clickable(onClickLabel = "Edit schedule", onClick = onEdit)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).then(edit).padding(vertical = 14.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val time = if (rule.repeats()) {
                val range = "${activity.timeLabel(rule.startMinute)} – ${activity.timeLabel(rule.repeatEndMinute())}"
                if (rule.endsNextDay()) "$range (next day)" else range
            } else activity.dateLabel(rule.from)
            Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!rule.repeats() || rule.name != DEFAULT_REPEAT_NAME) {
                Text(rule.name, style = MaterialTheme.typography.bodyMedium)
            }
            Caption(if (rule.repeats()) repeatingDaysLabel(rule.days) else durationLabel(rule.requestedDuration()))
        }
        when {
            active -> Text("Active", color = colors.primary, style = MaterialTheme.typography.labelMedium)
            ended -> TextButton(onClick = onDelete) { Text("Delete") }
            else -> Switch(rule.enabled, { onToggle() },
                modifier = Modifier.semantics { contentDescription = "${rule.name} schedule" })
        }
    }
}

@Composable
private fun SoftWorkspace(activity: MainActivity, state: FocusSnapshot) {
    val session = state.soft
    if (session.active(SystemClock.elapsedRealtime())) {
        val summary = when {
            session.allowlist && session.apps.isEmpty() -> "Essential apps only"
            session.allowlist -> "Allowlist with " + quantity(session.apps.size, "app")
            else -> "Blocklist with " + quantity(session.apps.size, "app") + " and " + quantity(session.sites.size, "site")
        }
        val title = when {
            session.test -> "Soft Lock test"
            state.blockingConnected -> "Blocking active"
            else -> "Blocking paused"
        }
        val detail = if (state.blockingConnected) summary else "The timer continues while blocking is paused."
        val paused = !session.test && state.breaks.until > SystemClock.elapsedRealtime()
        RunningCard(if (paused) "Break time remaining" else title,
            if (paused) minOf(state.breaks.until, session.timer.elapsedEnd) else session.timer.elapsedEnd,
            if (paused) breakDetail(session.timer.elapsedEnd, state.breaks.until) else detail,
            blockingEnds = !paused) {
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
        val apps = if (activity.allowlist) activity.allowedApps else activity.blockedApps
        val count = apps.size
        val empty = !activity.allowlist && count == 0 && activity.blockedSites.isEmpty()
        val label = when {
            empty -> "Choose what to block"
            !state.blockingConnected -> "Set up Soft Lock"
            else -> "Start Soft Lock"
        }
        SessionCard(activity.softDuration, { activity.sheet = "duration" }, label,
            { if (empty) activity.sheet = "rules" else activity.startSoft() }) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Edit Soft Lock rules") { activity.sheet = "rules" }
                .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val sites = activity.blockedSites.size
                    val summary = when {
                        empty -> "Choose apps or websites"
                        activity.allowlist && count == 0 && activity.allowBrowserTabs -> "Essential apps"
                        activity.allowlist && count == 0 -> "Essential apps only"
                        activity.allowlist -> "${quantity(count, "app")} allowed"
                        count == 0 -> "${quantity(sites, "site")} blocked"
                        sites == 0 -> "${quantity(count, "app")} blocked"
                        else -> "${quantity(count, "app")} and ${quantity(sites, "site")} blocked"
                    }
                    Text(summary, style = MaterialTheme.typography.titleMedium)
                }
                Symbol("Chev", tint = MaterialTheme.colorScheme.outline)
            }
        }
        TextButton(onClick = { if (empty) activity.sheet = "rules" else activity.startSoft(test = true) },
            modifier = Modifier.fillMaxWidth()) { Text("Test for 1 minute") }
        LockNotificationSetup(activity, state)
    }

    if (android.os.Build.VERSION.SDK_INT >= 33 && !state.accessibility && !session.active(SystemClock.elapsedRealtime())) {
        TextButton(onClick = { activity.openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) {
            Text("Accessibility disabled? Check app info")
        }
    }
}

private fun quantity(count: Int, noun: String) = "$count $noun" + if (count == 1) "" else "s"

@Composable
internal fun LockNotificationSetup(activity: MainActivity, state: FocusSnapshot) {
    val step = state.notificationSetup ?: return
    GroupCard {
        ActionRow(step.label, icon = "Lock") { activity.openNotificationSetup(step) }
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
private fun RunningCard(title: String, end: Long, detail: String, showCountdown: Boolean = true,
                        blockingEnds: Boolean = false,
                        actions: @Composable () -> Unit) {
    TimerCard {
        CardHeading("Clock", title)
        if (showCountdown) {
            val time by produceState(initialValue = SystemClock.elapsedRealtime(), end) {
                value = SystemClock.elapsedRealtime()
                while (value < end) {
                    delay(1_000)
                    value = SystemClock.elapsedRealtime()
                }
            }
            val seconds = ((end - time + 999) / 1000).coerceAtLeast(0)
            Text(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60),
                style = TextStyle(fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = (-1).sp, fontFeatureSettings = "'tnum'"))
        }
        if (blockingEnds) {
            val use24Hour = ClockLabels.is24Hour(androidx.compose.ui.platform.LocalContext.current)
            val deadline by produceState("", end, use24Hour) {
                while (true) {
                    val wallNow = System.currentTimeMillis()
                    val remaining = (end - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    value = ClockLabels.deadline(wallNow + remaining, wallNow, use24Hour, "Blocking ends")
                    delay(Rule.MINUTE - wallNow % Rule.MINUTE)
                }
            }
            DeadlineText(deadline)
            Caption(detail)
        } else {
            DeadlineText(detail)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
internal fun DeadlineText(label: String) {
    Text(label.substringBefore('\n'), style = MaterialTheme.typography.titleMedium)
    if ('\n' in label) Caption(label.substringAfter('\n'))
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
