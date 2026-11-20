package com.focuslock.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** A quota belongs to a session, shared by overlapping locks. All callers run on the main thread. */
final class EmergencyBreaks {
    final int minutes, limit, used;
    final long until, sessionEnd;
    final boolean enabled;

    private EmergencyBreaks(int minutes, int limit, int used, long until, long sessionEnd, boolean enabled) {
        this.minutes = minutes;
        this.limit = limit;
        this.used = used;
        this.until = until;
        this.sessionEnd = sessionEnd;
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
        for (Rule.Window window : hard.active) {
            if (window.rule.test) continue;
            active.add(window.key());
            end = Math.max(end, clock.elapsed + window.end - clock.wall);
        }
        if (!soft.test && soft.active(clock.elapsed)) {
            active.add(new SoftStore(c).sessionId());
            end = Math.max(end, soft.timer.elapsedEnd);
        }
        Set<String> previous = p.getStringSet("sessions", Collections.emptySet());
        boolean continuing = !Collections.disjoint(previous, active);
        int used = continuing ? p.getInt("used", 0) : 0;
        long until = 0;
        SharedPreferences.Editor edit = p.edit();
        boolean changed = !previous.equals(active);
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
        return new EmergencyBreaks(p.getInt("minutes", 2), p.getInt("limit", 3), used, until, end,
                new FocusStore(c).emergencyAllowed());
    }

    private static void storeTimer(SharedPreferences.Editor edit, TimedDeadline t) {
        edit.putLong("wall", t.wallEnd).putLong("elapsed", t.elapsedEnd).putInt("boot", t.boot).putLong("budget", t.budget);
    }

    static void rebase(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("wall")) return;
        SessionClock clock = new SessionClock(c);
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
