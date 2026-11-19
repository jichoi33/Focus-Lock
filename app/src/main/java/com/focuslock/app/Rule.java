package com.focuslock.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Rule {
    static final int DAY = 24 * 60;
    static final long MINUTE = 60_000L;
    static final String QUICK_ID = "quick";

    String id = UUID.randomUUID().toString();
    String name;
    boolean enabled = true;
    boolean test;
    int days;
    int startMinute;
    int endMinute;
    int durationMinutes;
    long from;
    long to;
    long skippedThrough;
    TimedDeadline timer;

    boolean repeats() {
        return days != 0;
    }

    boolean quick() {
        return QUICK_ID.equals(id);
    }

    int requestedDuration() {
        if (!repeats()) return (int) ((to - from) / MINUTE);
        if (durationMinutes > 0) return durationMinutes;
        return endMinute > startMinute ? endMinute - startMinute : DAY + endMinute - startMinute;
    }

    int repeatEndMinute() {
        return durationMinutes > 0 ? (startMinute + durationMinutes) % DAY : endMinute;
    }

    boolean endsNextDay() {
        return repeatEndMinute() <= startMinute;
    }

    static class Window {
        final Rule rule;
        final long start;
        final long end;
        final long elapsedEnd;

        String key() {
            return rule.id + "/" + (rule.quick() ? rule.from : start);
        }

        Window(Rule rule, long start, long end) {
            this(rule, start, end, 0L);
        }

        Window(Rule rule, long start, long end, long elapsedEnd) {
            this.rule = rule;
            this.start = start;
            this.end = end;
            this.elapsedEnd = elapsedEnd;
        }
    }

    static class State {
        final List<Window> active = new ArrayList<>();
        long next = Long.MAX_VALUE;
        long nextWall = Long.MAX_VALUE;
        long nextElapsed = Long.MAX_VALUE;

        long activeEnd() {
            long end = 0L;
            for (Window window : active) end = Math.max(end, window.end);
            return end;
        }

        void consider(Window window, long now) {
            if (window.start >= window.end || window.end <= now
                    || window.start < window.rule.skippedThrough) {
                return;
            }
            long boundary = window.start;
            if (window.start <= now) {
                active.add(window);
                boundary = window.end;
            }
            next = Math.min(next, boundary);
            nextWall = Math.min(nextWall, boundary);
        }
    }

    static State at(List<Rule> rules, long now, ZoneId zone) {
        return at(rules, now, 0L, -1, zone);
    }

    static State at(List<Rule> rules, long now, long elapsed, int boot, ZoneId zone) {
        State state = new State();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        for (Rule rule : rules) {
            if (!rule.enabled) continue;
            if (rule.quick() && rule.timer != null) {
                long remaining = rule.timer.remaining(boot, elapsed, now);
                if (remaining > 0) {
                    state.active.add(new Window(rule, now, now + remaining, elapsed + remaining));
                    state.next = Math.min(state.next, now + remaining);
                    state.nextElapsed = Math.min(state.nextElapsed, elapsed + remaining);
                }
                continue;
            }
            if (!rule.repeats()) {
                state.consider(new Window(rule, rule.from, rule.to), now);
                continue;
            }
            for (int offset = -1; offset <= 7; offset++) {
                LocalDate day = today.plusDays(offset);
                if ((rule.days & (1 << (day.getDayOfWeek().getValue() - 1))) == 0) continue;
                long start = atMinute(day, rule.startMinute, zone);
                long end;
                if (rule.durationMinutes > 0) {
                    end = start + rule.durationMinutes * MINUTE;
                } else {
                    LocalDate endDay = rule.endsNextDay() ? day.plusDays(1) : day;
                    end = atMinute(endDay, rule.endMinute, zone);
                }
                state.consider(new Window(rule, start, end), now);
            }
        }
        return state;
    }

    static long atMinute(LocalDate day, int minute, ZoneId zone) {
        return day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli();
    }
}
