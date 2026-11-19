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
        equal(beforeGap + 120 * Rule.MINUTE, acrossGap.next, "new rules use elapsed duration across DST");
        equal(540, weekday.requestedDuration(), "existing overnight rule displays its duration");
        equal(20, quick.requestedDuration(), "one-shot displays its elapsed duration");
        System.out.println("18 scheduling assertions passed");
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
