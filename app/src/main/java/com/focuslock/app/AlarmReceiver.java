package com.focuslock.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;


public final class AlarmReceiver extends BroadcastReceiver {
    private static final String BOUNDARY = "com.focuslock.app.BOUNDARY";
    private static final int WALL_CODE = 71;
    private static final int ELAPSED_CODE = 72;
    static final int SCHEDULE_NOTICE_ID = 82;

    private static PendingIntent alarm(Context context, int code) {
        return PendingIntent.getBroadcast(context, code,
                new Intent(context, AlarmReceiver.class).setAction(BOUNDARY),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean canSchedule(Context context) {
        return Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void cancel(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        manager.cancel(alarm(context, WALL_CODE));
        manager.cancel(alarm(context, ELAPSED_CODE));
    }

    static void schedule(Context context, Rule.State state) {
        if (!FocusAdmin.enabled(context)) {
            cancel(context);
            return;
        }
        scheduleOne(context, WALL_CODE, AlarmManager.RTC_WAKEUP, state.nextWall);
        long until = state.active.isEmpty() ? 0 : EmergencyBreaks.current(context).until;
        scheduleOne(context, ELAPSED_CODE, AlarmManager.ELAPSED_REALTIME_WAKEUP,
                until > 0 ? Math.min(until, state.nextElapsed) : state.nextElapsed);
    }

    private static void scheduleOne(Context context, int code, int type, long when) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        PendingIntent event = alarm(context, code);
        if (when == Long.MAX_VALUE) {
            manager.cancel(event);
            return;
        }
        if (canSchedule(context)) {
            try {
                manager.setExactAndAllowWhileIdle(type, when, event);
                return;
            } catch (SecurityException denied) {
                Log.i("FocusLock", "Exact-alarm permission changed");
            }
        }
        manager.setAndAllowWhileIdle(type, when, event);
    }

    @Override public void onReceive(Context context, Intent intent) {
        FocusStore store = new FocusStore(context);
        if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
            EmergencyBreaks.rebase(context);
            store.rebaseQuick();
            new SoftStore(context).rebase();
        }
        SessionClock clock = new SessionClock(context);
        Rule.State state = store.state(clock);
        new SessionCompletion(context).reconcileHard(clock, state);
        schedule(context, state);
        boolean admin = FocusAdmin.enabled(context);
        if (state.active.isEmpty() || !admin) {
            context.getSystemService(NotificationManager.class).cancel(SCHEDULE_NOTICE_ID);
        }
        if (!admin) return;
        if (!state.active.isEmpty() || FocusService.running) {
            try {
                FocusService.refresh(context);
            } catch (IllegalStateException | SecurityException denied) {
                Log.i("FocusLock", "Background session could not start", denied);
                showStartFailure(context, state);
            }
        }
    }

    static void showStartFailure(Context context, Rule.State state) {
        long end = 0L;
        for (Rule.Window window : state.active) {
            if (!window.rule.quick()) end = Math.max(end, window.end);
        }
        long remaining = end - System.currentTimeMillis();
        if (remaining <= 0 || !LockNotifications.available(context, false)) return;
        context.getSystemService(NotificationManager.class).notify(SCHEDULE_NOTICE_ID,
                new Notification.Builder(context, FocusService.CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Scheduled lock couldn’t start")
                        .setContentText("Tap to activate.")
                        .setContentIntent(LockNotifications.openApp(context, false))
                        .setTimeoutAfter(remaining)
                        .setAutoCancel(true)
                        .build());
    }
}
