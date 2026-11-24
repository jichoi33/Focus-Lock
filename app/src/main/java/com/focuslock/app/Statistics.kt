package com.focuslock.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val dayLabel = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val dateLabel = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

private data class DayStats(val date: LocalDate, val millis: Long, val sessions: Long, val breaks: Long)

private data class StatsReport(
    val protectedMillis: Long,
    val sessions: Long,
    val breaks: Long,
    val activeDays: Int,
    val days: List<DayStats> = emptyList()
)

private fun readStats(history: FocusHistory, allTime: Boolean): StatsReport {
    val today = LocalDate.now()
    val dates = if (allTime) history.recordedDates().sorted() else (6L downTo 0L).map(today::minusDays)
    val days = dates.map { date ->
        DayStats(date, history.protectedMillis(date), history.sessions(date), history.breaks(date))
    }
    return StatsReport(days.sumOf { it.millis }, days.sumOf { it.sessions },
        days.sumOf { it.breaks }, days.count { it.millis > 0 }, days)
}

private fun focusTime(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds < 60) "$seconds sec" else durationLabel((seconds / 60).toInt())
}

@Composable
internal fun StatisticsDialog(activity: MainActivity, mode: FocusMode, close: () -> Unit) {
    val history = remember(activity, mode) { FocusHistory(activity, mode == FocusMode.Soft) }
    var allTime by rememberSaveable(mode) { mutableStateOf(false) }
    var shown by rememberSaveable(mode, allTime) { mutableStateOf(14) }
    val report by key(history, allTime) {
        val initial = remember { readStats(history, allTime) }
        produceState(initial) {
            while (true) {
                value = readStats(history, allTime)
                delay(5_000)
            }
        }
    }
    FocusDialog("${mode.label} statistics", close) {
        Column(
            Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !allTime, onClick = { allTime = false }, label = { Text("Last 7 days") })
                FilterChip(selected = allTime, onClick = { allTime = true }, label = { Text("All time") })
            }
            if (!allTime) {
                Caption("${report.days.first().date.format(dateLabel)} – ${report.days.last().date.format(dateLabel)}")
            }
            TimerCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Caption("Protected time")
                    Text(focusTime(report.protectedMillis), style = MaterialTheme.typography.headlineLarge)
                }
                if (!allTime) FocusChart(report.days)
            }
            GroupCard {
                val first = report.days.firstOrNull()?.date ?: LocalDate.now()
                val periodDays = if (allTime) (LocalDate.now().toEpochDay() - first.toEpochDay() + 1).coerceAtLeast(1) else 7L
                StatRow("Daily average", focusTime(report.protectedMillis / periodDays))
                InsetDivider()
                StatRow("Sessions started", report.sessions.toString())
                InsetDivider()
                if (mode == FocusMode.Soft) {
                    StatRow("Emergency breaks", report.breaks.toString())
                    InsetDivider()
                }
                StatRow("Days with focus", if (allTime) report.activeDays.toString() else "${report.activeDays} of 7")
            }
            Caption(if (allTime) "Daily average includes every day since the first recorded session." else "Daily average includes all seven days.")
            if (report.days.isNotEmpty()) {
                Text("Daily history", style = MaterialTheme.typography.titleMedium)
                GroupCard {
                    report.days.asReversed().take(shown).forEachIndexed { index, day ->
                        if (index > 0) InsetDivider()
                        StatRow(day.date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)),
                            "${focusTime(day.millis)}\n${day.sessions} ${if (day.sessions == 1L) "session" else "sessions"} started")
                    }
                }
                if (report.days.size > shown) androidx.compose.material3.TextButton(onClick = { shown += 14 }) { Text("Show earlier days") }
            }
            Caption(if (mode == FocusMode.Soft) "Protected time excludes tests and emergency breaks." else "Protected time excludes tests.")
        }
    }
}

@Composable
private fun FocusChart(days: List<DayStats>) {
    val peak = days.maxOf { it.millis }.coerceAtLeast(1)
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        days.forEach { day ->
            val label = day.date.format(dayLabel)
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "$label, ${focusTime(day.millis)}"
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.height(60.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val height = if (day.millis == 0L) 3.dp else (60 * day.millis.toFloat() / peak).dp.coerceAtLeast(5.dp)
                    Box(Modifier.width(16.dp).height(height).background(
                        if (day.millis == 0L) colors.outlineVariant else colors.primary, RoundedCornerShape(5.dp)
                    ))
                }
                Caption(label)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
