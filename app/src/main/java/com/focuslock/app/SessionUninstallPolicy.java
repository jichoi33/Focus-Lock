package com.focuslock.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** Uses only policy authority already assigned by the device's administrator. */
final class SessionUninstallPolicy {
    private SessionUninstallPolicy() {}

    static void reconcile(Context context, Rule.State hard, SoftStore.Session soft, SessionClock clock) {
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        if (policy == null) return;
        String pkg = context.getPackageName();
        ComponentName admin = null;
        try {
            if (policy.isDeviceOwnerApp(pkg) || policy.isProfileOwnerApp(pkg)) {
                admin = FocusAdmin.component(context);
            } else if (!policy.getDelegatedScopes(null, pkg).contains(DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL)) {
                return;
            }
            boolean softActive = soft.active(clock.elapsed);
            boolean hardActive = FocusAdmin.enabled(context) && !hard.active.isEmpty();
            SharedPreferences prefs = context.getSharedPreferences("session_policy", Context.MODE_PRIVATE);
            boolean applied = prefs.getBoolean("applied", false);
            AlarmReceiver.schedulePolicyExpiry(context, softActive ? soft.timer.elapsedEnd : Long.MAX_VALUE);
            if (softActive || hardActive) {
                if (!policy.isUninstallBlocked(admin, pkg)) {
                    // Record ownership before the system call so process death can be reconciled.
                    if (!applied && !prefs.edit().putBoolean("applied", true).commit()) return;
                    policy.setUninstallBlocked(admin, pkg, true);
                }
            } else if (applied) {
                policy.setUninstallBlocked(admin, pkg, false);
                prefs.edit().remove("applied").apply();
            }
        } catch (SecurityException denied) {
            Log.d("FocusLock", "Session uninstall policy is unavailable for this installation");
        }
    }
}
