package com.focuslock.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class SoftStore {
    static final class Session {
        final TimedDeadline timer;
        final Set<String> apps;
        final Set<String> sites;
        final boolean blockUnverifiable;
        final boolean allowlist;
        final boolean returnHome;
        final boolean test;

        Session(TimedDeadline timer, Set<String> apps, Set<String> sites, boolean blockUnverifiable,
                boolean allowlist, boolean returnHome, boolean test) {
            this.timer = timer;
            this.apps = Collections.unmodifiableSet(new HashSet<>(apps));
            this.sites = Collections.unmodifiableSet(new HashSet<>(sites));
            this.blockUnverifiable = blockUnverifiable;
            this.allowlist = allowlist;
            this.returnHome = returnHome;
            this.test = test;
        }

        boolean active(long elapsedNow) {
            return elapsedNow < timer.elapsedEnd;
        }
    }

    private final SharedPreferences prefs;
    private final Context context;

    SoftStore(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("soft_lock", Context.MODE_PRIVATE);
    }

    String sessionId() { return prefs.getString("session_id", "soft/legacy"); }

    void checkCompletion(SessionClock clock, SessionCompletion completion) {
        completion.checkSoft(clock, prefs.getLong("end", 0L), prefs.getLong("end_elapsed", 0L),
                prefs.getInt("end_boot", -1), prefs.getString("session_id", null), prefs.getBoolean("allowlist", false));
    }

    Session read() {
        return new Session(deadline(new SessionClock(context), false),
                prefs.getStringSet("apps", Collections.emptySet()),
                prefs.getStringSet("sites", Collections.emptySet()),
                prefs.getBoolean("unverifiable", false), prefs.getBoolean("allowlist", false),
                prefs.getBoolean("return_home", false), false);
    }

    Set<String> selectedApps(boolean allowlist) {
        Session last = read();
        return prefs.getStringSet(allowlist ? "allow_apps" : "block_apps",
                last.allowlist == allowlist ? last.apps : Collections.emptySet());
    }

    Set<String> blockedSites() {
        return prefs.getStringSet("block_sites", read().sites);
    }

    boolean blockUnverifiable() {
        return prefs.getBoolean("block_unverifiable", read().blockUnverifiable);
    }

    boolean returnHomeByDefault() {
        return prefs.getBoolean("return_home", true);
    }

    void start(long duration, Set<String> apps, Set<String> sites, boolean blockUnverifiable,
               boolean allowlist, boolean returnHome) {
        Set<String> targets = new HashSet<>(apps);
        SharedPreferences.Editor edit = putDeadline(prefs.edit(), new SessionClock(context).start(duration))
                .putBoolean("allowlist", allowlist)
                .putString("session_id", "soft/" + java.util.UUID.randomUUID())
                .putBoolean("return_home", returnHome)
                .putStringSet("apps", targets)
                .putStringSet(allowlist ? "allow_apps" : "block_apps", new HashSet<>(targets));
        if (allowlist) {
            if (!prefs.contains("block_apps")) {
                edit.putStringSet("block_apps", new HashSet<>(selectedApps(false)))
                        .putStringSet("block_sites", new HashSet<>(blockedSites()))
                        .putBoolean("block_unverifiable", blockUnverifiable());
            }
            edit.putStringSet("sites", Collections.emptySet()).putBoolean("unverifiable", false);
        } else {
            edit.putStringSet("sites", new HashSet<>(sites))
                    .putStringSet("block_sites", new HashSet<>(sites))
                    .putBoolean("unverifiable", blockUnverifiable)
                    .putBoolean("block_unverifiable", blockUnverifiable);
        }
        edit.apply();
    }

    boolean extend(int minutes) {
        SessionClock clock = new SessionClock(context);
        TimedDeadline current = deadline(clock, false);
        long remaining = current.elapsedEnd - clock.elapsed;
        if (remaining <= 0 || minutes < 1 || minutes > Rule.DAY) return false;
        putDeadline(prefs.edit(), clock.start(remaining + minutes * Rule.MINUTE)).apply();
        return true;
    }

    boolean tighten(String expectedSessionId, boolean allowlist, Set<String> apps, Set<String> sites) {
        Session current = read();
        if (!current.active(SystemClock.elapsedRealtime()) || !sessionId().equals(expectedSessionId)
                || current.allowlist != allowlist || (allowlist && !sites.isEmpty())) return false;

        SharedPreferences.Editor edit = prefs.edit();
        Set<String> nextApps = BlockRules.tightenedApps(allowlist, current.apps, apps);
        if (!nextApps.equals(current.apps)) {
            edit.putStringSet("apps", nextApps)
                    .putStringSet(allowlist ? "allow_apps" : "block_apps", new HashSet<>(nextApps));
        }
        Set<String> nextSites = new HashSet<>(current.sites);
        for (String site : sites) {
            String domain = BlockRules.domain(site);
            if (domain.isEmpty()) return false;
            nextSites.add(domain);
        }
        if (!nextSites.equals(current.sites)) {
            edit.putStringSet("sites", nextSites).putStringSet("block_sites", new HashSet<>(nextSites));
        }
        edit.apply();
        return true;
    }

    void rebase() {
        deadline(new SessionClock(context), true);
    }

    private TimedDeadline deadline(SessionClock clock, boolean rebase) {
        long oldEnd = prefs.getLong("end", 0L);
        if (oldEnd == 0L) return new TimedDeadline(clock.boot, 0L, 0L, 0L);
        TimedDeadline saved = new TimedDeadline(prefs.getInt("end_boot", -1),
                prefs.getLong("end_elapsed", 0L), oldEnd, prefs.getLong("end_budget", Long.MAX_VALUE));
        TimedDeadline timer = rebase ? clock.rebase(saved) : clock.restore(saved);
        if (timer != saved) {
            checkCompletion(clock, new SessionCompletion(context));
            putDeadline(prefs.edit(), timer).apply();
        }
        return timer;
    }

    private SharedPreferences.Editor putDeadline(SharedPreferences.Editor edit, TimedDeadline timer) {
        return edit.putLong("end", timer.wallEnd).putInt("end_boot", timer.boot)
                .putLong("end_elapsed", timer.elapsedEnd).putLong("end_budget", timer.budget);
    }

    void observe(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    void unobserve(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
