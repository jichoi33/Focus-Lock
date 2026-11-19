package com.focuslock.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FocusStore {
    private final SharedPreferences prefs;
    private final Context context;

    FocusStore(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE);
    }

    boolean emergencyAllowed() {
        return prefs.getBoolean("emergency_allowed", true);
    }

    void setEmergencyAllowed(boolean allowed) {
        prefs.edit().putBoolean("emergency_allowed", allowed).apply();
    }

    void observe(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    void unobserve(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    Rule.State state(SessionClock clock) {
        return Rule.at(rules(clock, false), clock.wall, clock.elapsed, clock.boot, java.time.ZoneId.systemDefault());
    }

    List<Rule> rules() {
        return rules(new SessionClock(context), false);
    }

    void rebaseQuick() {
        rules(new SessionClock(context), true);
    }

    private List<Rule> rules(SessionClock clock, boolean rebase) {
        JSONArray array;
        try {
            array = new JSONArray(prefs.getString("rules", "[]"));
        } catch (JSONException error) {
            Log.e("FocusLock", "Cannot read rules", error);
            return new ArrayList<>();
        }
        List<Rule> result = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < array.length(); i++) {
            JSONObject data = array.optJSONObject(i);
            if (data == null || !data.has("id")) continue;
            Rule rule = new Rule();
            rule.id = data.optString("id");
            rule.name = data.optString("name");
            rule.test = rule.quick() && data.optBoolean("test");
            boolean wasLocationBased = data.has("area");
            rule.enabled = data.optBoolean("enabled", true) && !wasLocationBased;
            changed |= wasLocationBased;
            rule.days = data.optInt("days");
            rule.startMinute = data.optInt("start");
            rule.endMinute = data.optInt("end");
            rule.durationMinutes = data.optInt("duration");
            rule.from = data.optLong("from");
            rule.to = data.optLong("to");
            rule.skippedThrough = data.optLong("skipped");
            if (rule.quick()) {
                TimedDeadline saved = new TimedDeadline(data.optInt("timer_boot", -1),
                        data.optLong("timer_elapsed", 0L), rule.to,
                        data.optLong("timer_budget", Math.max(0L, rule.to - rule.from)));
                rule.timer = rebase ? clock.rebase(saved) : clock.restore(saved);
                changed |= rule.timer != saved;
                rule.to = rule.timer.wallEnd;
            }
            result.add(rule);
        }
        if (changed) write(result);
        return result;
    }

    void write(List<Rule> rules) {
        JSONArray array = new JSONArray();
        try {
            for (Rule rule : rules) {
                JSONObject data = new JSONObject();
                data.put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled)
                        .put("days", rule.days).put("start", rule.startMinute)
                        .put("end", rule.endMinute).put("duration", rule.durationMinutes)
                        .put("from", rule.from).put("to", rule.to)
                        .put("skipped", rule.skippedThrough);
                if (rule.test) data.put("test", true);
                if (rule.timer != null) {
                    data.put("timer_boot", rule.timer.boot).put("timer_elapsed", rule.timer.elapsedEnd)
                            .put("timer_budget", rule.timer.budget);
                }
                array.put(data);
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        prefs.edit().putString("rules", array.toString()).apply();
    }

    void put(Rule updated) {
        List<Rule> rules = rules();
        rules.removeIf(rule -> rule.id.equals(updated.id));
        rules.add(updated);
        write(rules);
    }

    void remove(String id) {
        List<Rule> rules = rules();
        rules.removeIf(rule -> rule.id.equals(id));
        write(rules);
    }
}
