package com.focuslock.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** A quota renews every 24 hours and is shared by overlapping locks. All callers run on the main thread. */
final class EmergencyBreaks {
    private static final long DAY_MS = Rule.DAY * Rule.MINUTE;

    final int minutes, limit, used;
    final long until, sessionEnd, nextReset;
    final boolean enabled;

    private EmergencyBreaks(int minutes, int limit, int used, long until, long sessionEnd, long nextReset, boolean enabled) {
        this.minutes = minutes;
        this.limit = limit;
        this.used = used;
        this.until = until;
        this.sessionEnd = sessionEnd;
        this.nextReset = nextReset;
        this.enabled = enabled;
    }

    int remaining() { return Math.max(0, limit - used); }

    boolean available(long now) {
        return enabled && remaining() > 0 && until <= now && sessionEnd > now;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("emergency_breaks", Context.MODE_PRIVATE);
    }

    static EmergencyBreaks current(Context c) {
        SessionClock clock = new SessionClock(c);
        return read(c, clock, new FocusStore(c).state(clock), new SoftStore(c).read());
    }

    static EmergencyBreaks read(Context c, SessionClock clock, Rule.State hard, SoftStore.Session soft) {
        SharedPreferences p = prefs(c);
        Set<String> active = new HashSet<>();
        long end = 0;
        long start = clock.wall;
        for (Rule.Window window : hard.active) {
            if (window.rule.test) continue;
            active.add(window.key());
            start = Math.min(start, window.rule.quick() ? window.rule.from : window.start);
            end = Math.max(end, clock.elapsed + window.end - clock.wall);
        }
        if (!soft.test && soft.active(clock.elapsed)) {
            active.add(new SoftStore(c).sessionId());
            start = Math.min(start, soft.timer.wallEnd - soft.timer.budget);
            end = Math.max(end, soft.timer.elapsedEnd);
        }
        Set<String> previous = p.getStringSet("sessions", Collections.emptySet());
        boolean continuing = !Collections.disjoint(previous, active);
        int used = continuing ? p.getInt("used", 0) : 0;
        long until = 0;
        SharedPreferences.Editor edit = p.edit();
        boolean changed = !previous.equals(active);
        long nextReset = Long.MAX_VALUE;
        if (!active.isEmpty()) {
            TimedDeadline saved = continuing ? resetTimer(p) : null;
            long remaining;
            if (saved == null) {
                long age = clock.wall - start;
                if (age >= DAY_MS) used = 0;
                remaining = DAY_MS - age % DAY_MS;
            } else {
                long delta = saved.boot == clock.boot && clock.boot != -1
                        ? saved.elapsedEnd - clock.elapsed : saved.wallEnd - clock.wall;
                if (delta <= 0L) {
                    used = 0;
                    remaining = DAY_MS + delta % DAY_MS;
                } else {
                    remaining = Math.min(DAY_MS, delta);
                }
            }
            nextReset = clock.elapsed + remaining;
            if (saved == null || saved.boot != clock.boot || saved.elapsedEnd != nextReset) {
                storeReset(edit, clock.start(remaining));
                changed = true;
            }
        } else if (p.contains("reset_wall")) {
            edit.remove("reset_wall").remove("reset_elapsed").remove("reset_boot");
            changed = true;
        }
        if (changed) edit.putStringSet("sessions", active).putInt("used", used);
        if (p.contains("wall")) {
            TimedDeadline timer = new TimedDeadline(p.getInt("boot", -1), p.getLong("elapsed", 0),
                    p.getLong("wall", 0), p.getLong("budget", 0));
            TimedDeadline restored = clock.restore(timer);
            if (continuing && restored.elapsedEnd > clock.elapsed) {
                until = Math.min(end, restored.elapsedEnd);
                if (restored != timer) { storeTimer(edit, restored); changed = true; }
            } else { edit.remove("wall"); changed = true; }
        }
        if (changed) edit.apply();
        if (nextReset >= end) nextReset = Long.MAX_VALUE;
        return new EmergencyBreaks(p.getInt("minutes", 2), p.getInt("limit", 3), used, until, end, nextReset,
                new FocusStore(c).emergencyAllowed());
    }

    private static void storeTimer(SharedPreferences.Editor edit, TimedDeadline t) {
        edit.putLong("wall", t.wallEnd).putLong("elapsed", t.elapsedEnd).putInt("boot", t.boot).putLong("budget", t.budget);
    }

    private static TimedDeadline resetTimer(SharedPreferences p) {
        return !p.contains("reset_wall") ? null : new TimedDeadline(p.getInt("reset_boot", -1),
                p.getLong("reset_elapsed", 0), p.getLong("reset_wall", 0), DAY_MS);
    }

    private static void storeReset(SharedPreferences.Editor edit, TimedDeadline timer) {
        edit.putInt("reset_boot", timer.boot).putLong("reset_elapsed", timer.elapsedEnd).putLong("reset_wall", timer.wallEnd);
    }

    static void rebase(Context c) {
        SharedPreferences p = prefs(c);
        SessionClock clock = new SessionClock(c);
        TimedDeadline reset = resetTimer(p);
        if (reset != null && reset.boot == clock.boot) {
            // Keep elapsed time authoritative when the user changes the wall clock.
            prefs(c).edit().putLong("reset_wall", clock.wall + reset.elapsedEnd - clock.elapsed).apply();
        }
        if (!p.contains("wall")) return;
        TimedDeadline saved = new TimedDeadline(p.getInt("boot", -1), p.getLong("elapsed", 0),
                p.getLong("wall", 0), p.getLong("budget", 0));
        SharedPreferences.Editor edit = p.edit();
        if (saved.remaining(clock.boot, clock.elapsed, clock.wall) == 0) edit.remove("wall");
        else storeTimer(edit, clock.rebase(saved));
        edit.apply();
    }

    static boolean configure(Context c, boolean enabled, int minutes, int limit) {
        if (current(c).sessionEnd > 0 || SoftBlocker.activeTest() != null ||
                minutes < 1 || minutes > 60 || limit < 1 || limit > 20) return false;
        prefs(c).edit().putInt("minutes", minutes).putInt("limit", limit).apply();
        new FocusStore(c).setEmergencyAllowed(enabled);
        return true;
    }

    static boolean begin(Context c) {
        EmergencyBreaks b = current(c);
        SessionClock clock = new SessionClock(c);
        if (!b.available(clock.elapsed)) return false;
        long duration = Math.min(b.minutes * Rule.MINUTE, b.sessionEnd - clock.elapsed);
        SharedPreferences.Editor edit = prefs(c).edit().putInt("used", b.used + 1);
        storeTimer(edit, clock.start(duration));
        edit.apply();
        if (new FocusStore(c).state(clock).active.stream().anyMatch(window -> !window.rule.test)) {
            new FocusHistory(c, false).breakUsed();
        }
        if (new SoftStore(c).read().active(clock.elapsed)) new FocusHistory(c, true).breakUsed();
        SoftBlocker.refreshBreak();
        return true;
    }
}
