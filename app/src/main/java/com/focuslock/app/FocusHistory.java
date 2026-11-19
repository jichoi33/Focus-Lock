package com.focuslock.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Records elapsed time only while an enforcement service has observed a real session. */
final class FocusHistory {
    private static final long SAVE_INTERVAL = 30_000;
    private static final String RECENT_IDS = "protected_session_ids";
    private static FocusHistory hardTracker;
    private static FocusHistory softTracker;
    private final SharedPreferences prefs;
    private final boolean soft;
    private long recordedThrough;
    private long protectionEnd;
    private Map<String, Long> sessionEnds = Collections.emptyMap();

    FocusHistory(Context context, boolean soft) {
        this.soft = soft;
        prefs = context.getSharedPreferences(soft ? "soft_history" : "hard_history", Context.MODE_PRIVATE);
    }

    // Enforcement and completion run on the same main thread. Flush before reading a result.
    static long sessionMillis(Context context, boolean soft, String id, long now) {
        if (id == null) return 0L;
        FocusHistory tracker = soft ? softTracker : hardTracker;
        if (tracker == null) tracker = new FocusHistory(context, soft);
        tracker.save(now);
        return tracker.prefs.getLong("protected/" + id, 0L);
    }

    void sample(long elapsed, long end, Map<String, Long> activeSessions) {
        if (soft) softTracker = this;
        else hardTracker = this;
        boolean sameWindow = end == protectionEnd
                || (end > 0 && protectionEnd > 0 && Math.abs(end - protectionEnd) < 1000);
        if (sameWindow && sessionEnds.keySet().equals(activeSessions.keySet())
                && (end == 0 || elapsed - recordedThrough < SAVE_INTERVAL)) {
            protectionEnd = end;
            sessionEnds = activeSessions;
            return;
        }
        save(elapsed);
        protectionEnd = end;
        recordedThrough = elapsed;
        boolean newSessions = !activeSessions.isEmpty() && !sessionEnds.keySet().equals(activeSessions.keySet());
        sessionEnds = activeSessions;
        if (!newSessions) return;

        Set<String> activeIds = activeSessions.keySet();
        SharedPreferences.Editor update = prefs.edit();
        for (String previous : prefs.getStringSet(RECENT_IDS, Collections.emptySet())) {
            if (!activeIds.contains(previous)) update.remove("protected/" + previous);
        }
        update.putStringSet(RECENT_IDS, new HashSet<>(activeIds));
        Set<String> seen = new HashSet<>(prefs.getStringSet("started_ids", Collections.emptySet()));
        int before = seen.size();
        seen.addAll(activeIds);
        int added = seen.size() - before;
        if (added > 0) {
            putTotal(update, LocalDate.now(), "sessions", added);
            update.putStringSet("started_ids", seen);
        }
        update.apply();
    }

    private void save(long now) {
        long stop = Math.min(protectionEnd, now);
        if (stop <= recordedThrough) return;
        ZoneId zone = ZoneId.systemDefault();
        long endWall = System.currentTimeMillis() - (now - stop);
        long cursor = endWall - (stop - recordedThrough);
        SharedPreferences.Editor update = prefs.edit();
        while (cursor < endWall) {
            LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
            long boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long next = Math.min(endWall, boundary);
            putTotal(update, day, "time", next - cursor);
            cursor = next;
        }
        for (Map.Entry<String, Long> session : sessionEnds.entrySet()) {
            long duration = Math.min(stop, session.getValue()) - recordedThrough;
            if (duration <= 0) continue;
            String key = "protected/" + session.getKey();
            update.putLong(key, prefs.getLong(key, 0L) + duration);
        }
        recordedThrough = stop;
        update.apply();
    }

    private void putTotal(SharedPreferences.Editor update, LocalDate date, String type, long value) {
        String key = type + "/" + date;
        update.putLong(key, prefs.getLong(key, 0L) + value);
    }

    void breakUsed() {
        SharedPreferences.Editor update = prefs.edit();
        putTotal(update, LocalDate.now(), "breaks", 1L);
        update.apply();
    }

    long protectedMillis(LocalDate date) { return prefs.getLong("time/" + date, 0); }
    long sessions(LocalDate date) { return prefs.getLong("sessions/" + date, 0); }
    long breaks(LocalDate date) { return prefs.getLong("breaks/" + date, 0); }

    static final class Totals {
        long protectedMillis;
        long sessions;
        long breaks;
        int activeDays;
    }

    Totals allTime() {
        Totals totals = new Totals();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!(entry.getValue() instanceof Long)) continue;
            long amount = (Long) entry.getValue();
            if (amount <= 0) continue;
            String key = entry.getKey();
            if (key.startsWith("time/")) {
                totals.protectedMillis += amount;
                totals.activeDays++;
            } else if (key.startsWith("sessions/")) {
                totals.sessions += amount;
            } else if (key.startsWith("breaks/")) {
                totals.breaks += amount;
            }
        }
        return totals;
    }

    void stop() {
        sample(SystemClock.elapsedRealtime(), 0, Collections.emptyMap());
        if (soft) softTracker = null;
        else hardTracker = null;
    }
}
