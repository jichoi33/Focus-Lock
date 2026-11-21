package com.focuslock.app;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public final class FocusAdmin extends DeviceAdminReceiver {
    static ComponentName component(Context context) {
        return new ComponentName(context, FocusAdmin.class);
    }

    static boolean enabled(Context context) {
        return context.getSystemService(DevicePolicyManager.class).isAdminActive(component(context));
    }

    @Override public void onDisabled(Context context, Intent intent) {
        new SessionCompletion(context).cancelHard();
        context.stopService(new Intent(context, FocusService.class));
        AlarmReceiver.cancel(context);
        SessionClock clock = new SessionClock(context);
        SessionUninstallPolicy.reconcile(context, new Rule.State(), new SoftStore(context).read(), clock);
    }
}
