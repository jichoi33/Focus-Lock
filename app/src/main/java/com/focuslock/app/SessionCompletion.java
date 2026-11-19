package com.focuslock.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers completed sessions until their result is acknowledged in the app. */
final class SessionCompletion {
    static final int HARD = 1;
    static final int SOFT = 2;
    static final String PENDING = "pending";
    static final String HARD_RESULT = "hard_result";
    static final String SOFT_RESULT = "soft_result";
    private static final String HARD_NAME = "hard_name";
    private static final String HARD_WALL = "hard_wall";
    private static final String HARD_ELAPSED = "hard_elapsed";
    private static final String HARD_BOOT = "hard_boot";
    private static final String SOFT_ID = "soft_id";
    private static final long RECENT = Rule.DAY * Rule.MINUTE;

    private final Context context;
    private final SharedPreferences prefs;

    SessionCompletion(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("session_completion", Context.MODE_PRIVATE);
    }

    int pending() {
        return prefs.getInt(PENDING, 0);
    }

    String description() {
        int modes = pending();
        String hard = (modes & HARD) != 0 ? prefs.getString(HARD_RESULT, "") : "";
        String soft = (modes & SOFT) != 0 ? prefs.getString(SOFT_RESULT, "") : "";
        if (hard.isEmpty()) return soft;
        return soft.isEmpty() ? hard : hard + " · " + soft;
    }

    void observe(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    void unobserve(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    void dismiss() {
        prefs.edit().remove(PENDING).remove(HARD_RESULT).remove(SOFT_RESULT).apply();
        LockNotifications.hideCompletion(context);
    }

    // Record the last enforced end so an alarm or a new activity can recover it after process death.
    void trackHard(SessionClock clock, Rule.State state) {
        Rule.Window latest = null;
        for (Rule.Window window : state.active) {
            if (!window.rule.test && (latest == null || window.end > latest.end)) latest = window;
        }
        if (latest == null) {
            reconcileHard(clock, state);
            return;
        }
        String name = latest.rule.quick() ? "Quick focus session" : latest.rule.name;
        if (prefs.getLong(HARD_WALL, 0) != latest.end
                || prefs.getLong(HARD_ELAPSED, 0) != latest.elapsedEnd
                || prefs.getInt(HARD_BOOT, -1) != clock.boot
                || !java.util.Objects.equals(prefs.getString(HARD_NAME, null), name)) {
            prefs.edit().putLong(HARD_WALL, latest.end).putLong(HARD_ELAPSED, latest.elapsedEnd)
                    .putInt(HARD_BOOT, clock.boot).putString(HARD_NAME, name).apply();
        }
    }

    void reconcileHard(SessionClock clock, Rule.State state) {
        long endWall = prefs.getLong(HARD_WALL, 0);
        if (endWall == 0 || state.active.stream().anyMatch(window -> !window.rule.test)) return;
        long endElapsed = prefs.getLong(HARD_ELAPSED, 0);
        long age = clock.boot != -1 && clock.boot == prefs.getInt(HARD_BOOT, -1) && endElapsed > 0
                ? clock.elapsed - endElapsed : clock.wall - endWall;
        String result = prefs.getString(HARD_NAME, "Hard Lock session");
        SharedPreferences.Editor edit = clearHardDeadline();
        if (age >= 0 && age < RECENT) record(edit.putString(HARD_RESULT, result), HARD);
        else edit.apply();
    }

    void cancelHard() {
        if (prefs.contains(HARD_WALL)) clearHardDeadline().apply();
    }

    private SharedPreferences.Editor clearHardDeadline() {
        return prefs.edit().remove(HARD_WALL).remove(HARD_ELAPSED).remove(HARD_BOOT).remove(HARD_NAME);
    }

    void checkSoft(SessionClock clock, long endWall, long endElapsed, int boot, String sessionId, boolean allowlist) {
        if (sessionId == null || sessionId.equals(prefs.getString(SOFT_ID, null))) return;
        long age = clock.boot != -1 && clock.boot == boot && endElapsed > 0
                ? clock.elapsed - endElapsed : clock.wall - endWall;
        if (endWall <= 0 || age < 0) return;
        SharedPreferences.Editor edit = prefs.edit().putString(SOFT_ID, sessionId);
        if (age < RECENT) {
            record(edit.putString(SOFT_RESULT, allowlist ? "Allowlist session" : "Blocklist session"), SOFT);
        } else {
            edit.apply();
        }
    }

    private void record(SharedPreferences.Editor edit, int mode) {
        int next = pending() | mode;
        edit.putInt(PENDING, next).apply();
        LockNotifications.showCompletion(context, next);
    }
}
