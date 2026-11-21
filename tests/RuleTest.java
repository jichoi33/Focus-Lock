package com.focuslock.app;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;

public final class RuleTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static void main(String[] args) {
        long now = at("2026-11-17T22:05:00");
        Rule quick = new Rule();
        quick.from = now;
        quick.to = now + 20 * Rule.MINUTE;
        equal(0, state(quick, now - 1).active.size(), "one-shot starts at boundary");
        equal(quick.from, state(quick, now - 1).next, "one-shot start alarm");
        equal(1, state(quick, now).active.size(), "one-shot active");
        equal(quick.to, state(quick, now).next, "one-shot end alarm");
        equal(0, state(quick, quick.to).active.size(), "one-shot end is exclusive");

        Rule weekday = new Rule();
        weekday.days = 31;
        weekday.startMinute = 22 * 60;
        weekday.endMinute = 7 * 60;
        long friday = at("2026-11-20T22:00:00");
        long saturday = at("2026-11-21T07:00:00");
        long monday = at("2026-11-23T22:00:00");
        equal(friday, state(weekday, friday - 1).next, "Friday starts on Friday");
        equal(saturday, state(weekday, saturday - 1).next, "Friday runs into Saturday");
        equal(monday, state(weekday, saturday).next, "no weekend starts");

        Rule tuesday = new Rule();
        tuesday.days = 2;
        tuesday.startMinute = 22 * 60;
        tuesday.endMinute = 23 * 60;
        equal(at("2026-11-24T22:00:00"),
                state(tuesday, at("2026-11-17T23:15:00")).next, "same weekday next week");
        Rule.State overlap = Rule.at(Arrays.asList(weekday, tuesday, quick), now, SEOUL);
        equal(3, overlap.active.size(), "overlaps are tracked independently");
        equal(at("2026-11-18T07:00:00"), overlap.activeEnd(), "countdown ends with the last active lock");
        equal(0, state(quick, quick.to).activeEnd(), "no countdown without an active lock");

        weekday.skippedThrough = saturday;
        equal(0, state(weekday, friday + Rule.MINUTE).active.size(), "emergency skip current occurrence");
        equal(monday, state(weekday, friday + Rule.MINUTE).next, "emergency keeps later occurrences");
        tuesday.enabled = false;
        equal(Long.MAX_VALUE, state(tuesday, now).next, "disabled rule has no boundaries");

        ZoneId newYork = ZoneId.of("America/New_York");
        long spring = Rule.atMinute(LocalDate.of(2026, 3, 8), 150, newYork);
        equal(3, java.time.Instant.ofEpochMilli(spring).atZone(newYork).getHour(), "DST spring gap");
        Rule durationRule = new Rule();
        durationRule.days = 64;
        durationRule.startMinute = 60;
        durationRule.durationMinutes = 120;
        long beforeGap = Rule.atMinute(LocalDate.of(2026, 3, 8), 60, newYork);
        Rule.State acrossGap = Rule.at(Collections.singletonList(durationRule), beforeGap, newYork);
        equal(beforeGap + 120 * Rule.MINUTE, acrossGap.next, "saved duration rules keep elapsed time across DST");
        equal(180, durationRule.repeatEndMinute(), "saved duration rules have a displayable end time");

        Rule timeRange = new Rule();
        timeRange.days = 64;
        timeRange.startMinute = 60;
        timeRange.endMinute = 180;
        Rule.State wallRange = Rule.at(Collections.singletonList(timeRange), beforeGap, newYork);
        equal(beforeGap + 60 * Rule.MINUTE, wallRange.next, "time ranges end at the selected local time");

        timeRange.endMinute = timeRange.startMinute;
        equal(1, timeRange.endsNextDay() ? 1 : 0, "matching times mean the next day");
        equal(Rule.atMinute(LocalDate.of(2026, 3, 9), 60, newYork),
                Rule.at(Collections.singletonList(timeRange), beforeGap, newYork).next,
                "a full day ends at the same local time next day");
        equal(540, weekday.requestedDuration(), "existing overnight rule displays its duration");
        equal(20, quick.requestedDuration(), "one-shot displays its elapsed duration");
        equal(420, weekday.repeatEndMinute(), "overnight schedules show their saved end time");
        equal(1, weekday.endsNextDay() ? 1 : 0, "overnight labels show that the end is the next day");
        Rule overnight = weekday.withRepeatTimes(31, 22 * 60, 7 * 60);
        overnight.skippedThrough = 0;
        Rule.State current = state(overnight, friday + Rule.MINUTE);
        equal(friday, current.latestActive().start, "the save prompt sees the current Friday occurrence");
        equal(saturday, current.latestActive().end, "the save prompt shows the actual overnight end");
        equal(monday, current.nextStart.start, "the preview shows Monday as the next occurrence");
        equal(at("2026-11-24T07:00:00"), current.nextStart.end, "the next occurrence includes the following date");
        overnight.skippedThrough = current.latestActive().start + 1;
        equal(0, state(overnight, friday + Rule.MINUTE).active.size(), "deferred save leaves Friday inactive");
        equal(1, state(overnight, monday).active.size(), "deferred schedule starts on Monday");
        equal(0, overnight.repeatsWithoutGap() ? 1 : 0, "weekday schedules have a weekend gap");
        equal(1, overnight.withRepeatTimes(127, 540, 540).repeatsWithoutGap() ? 1 : 0, "equal daily times are continuous");

        Rule renamed = durationRule.withRepeatTimes(64, 60, 180);
        equal(120, renamed.durationMinutes, "editing the name or days retains legacy duration");
        Rule.Window dstPreview = Rule.at(Collections.singletonList(renamed), beforeGap - Rule.MINUTE, newYork).nextStart;
        equal(beforeGap + 120 * Rule.MINUTE, dstPreview.end, "legacy preview uses the scheduler's DST-adjusted end");
        equal(0, durationRule.withRepeatTimes(64, 60, 240).durationMinutes, "editing the times opts into wall-clock scheduling");
        equal(1, state(quick, now - 1).upcoming.size(), "future one-time locks can generate reminders");
        Rule instant = quick.copy();
        instant.id = Rule.QUICK_ID;
        equal(0, state(instant, now - 1).upcoming.size(), "quick locks never generate schedule reminders");

        Rule first = new Rule();
        first.from = now + Rule.MINUTE;
        first.to = now + 15 * Rule.MINUTE;
        Rule overlapping = new Rule();
        overlapping.from = first.to - Rule.MINUTE;
        overlapping.to = first.to + 10 * Rule.MINUTE;
        Rule adjacent = new Rule();
        adjacent.from = overlapping.to;
        adjacent.to = overlapping.to + 20 * Rule.MINUTE;
        Rule later = new Rule();
        later.from = adjacent.to + Rule.MINUTE;
        later.to = later.from + Rule.MINUTE;
        Rule.Window occurrence = state(first, now).nextStart;
        equal(first.to, Rule.finalRelease(Collections.singletonList(first), occurrence, now, 0L, -1, SEOUL),
                "a single occurrence releases at its own end");
        equal(adjacent.to, Rule.finalRelease(Arrays.asList(first, adjacent, later, overlapping), occurrence, now, 0L, -1, SEOUL),
                "overlapping and adjacent locks extend the release through the first gap");
        Rule.State pending = Rule.at(Arrays.asList(adjacent, later, overlapping), now, SEOUL);
        Rule.Release quickRelease = pending.releaseAfter(first.to, now, SEOUL);
        equal(adjacent.to, quickRelease.end, "a quick lock includes an overlapping and back-to-back schedule");
        if (quickRelease.extension.rule != adjacent) {
            throw new AssertionError("the last extending schedule is used as the reason");
        }
        Rule.Release beforeNext = pending.releaseAfter(overlapping.from - 1, now, SEOUL);
        equal(overlapping.from - 1, beforeNext.end, "a gap before a scheduled lock keeps the quick end");
        if (beforeNext.extension != null) throw new AssertionError("a gap has no extending schedule");
        adjacent.enabled = false;
        equal(overlapping.to, Rule.finalRelease(Arrays.asList(first, adjacent, overlapping), occurrence, now, 0L, -1, SEOUL),
                "disabled locks do not extend the release");

        Rule continuous = overnight.withRepeatTimes(127, 540, 540);
        continuous.skippedThrough = 0;
        equal(0, Rule.finalRelease(Collections.singletonList(continuous), state(continuous, now).latestActive(), now, 0L, -1, SEOUL),
                "continuous schedules have no release in the preview horizon");

        long weekEnd = now + Rule.MAX_LOCK_MINUTES * Rule.MINUTE;
        Rule followOn = new Rule();
        followOn.from = weekEnd;
        followOn.to = weekEnd + 30 * Rule.MINUTE;
        equal(followOn.to, state(followOn, now).releaseAfter(weekEnd, now, SEOUL).end,
                "a full-week timer ends after the adjacent scheduled lock");
        equal(0, state(continuous, now).releaseAfter(weekEnd, now, SEOUL).end,
                "a full-week timer still detects a continuous repeating lock");
        ZoneId eastern = ZoneId.of("America/New_York");
        long beforeDst = ZonedDateTime.parse("2026-03-07T12:00:00-05:00[America/New_York]").toInstant().toEpochMilli();
        long elapsedWeek = beforeDst + 7L * Rule.DAY * Rule.MINUTE;
        equal(elapsedWeek, new Rule.State().releaseAfter(elapsedWeek, beforeDst, eastern).end,
                "seven elapsed days show an exact unlock across spring daylight saving");

        System.out.println("48 scheduling assertions passed");

    }

    private static Rule.State state(Rule rule, long time) {
        return Rule.at(Collections.singletonList(rule), time, SEOUL);
    }

    private static long at(String value) {
        return ZonedDateTime.parse(value + "+09:00[Asia/Seoul]").toInstant().toEpochMilli();
    }

    private static void equal(long expected, long actual, String name) {
        if (expected != actual) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
    }
}
