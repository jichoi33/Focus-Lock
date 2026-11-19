package com.focuslock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

final class LockNotifications {
    static final String OPEN_SCHEDULE = "com.focuslock.app.SHOW_SCHEDULE";
    static final String RULE_ID = "rule_id";
    static final String OPEN_HARD = "com.focuslock.app.SHOW_HARD_LOCK";
    static final String OPEN_SOFT = "com.focuslock.app.SHOW_SOFT_LOCK";
    static final String SOFT_CHANNEL = "soft_lock_session";
    private static final int SOFT_ID = 83;
    private static final int HARD_PREVIEW_ID = 85;
    private static final int COMPLETION_ID = 86;
    private static final String COMPLETION_CHANNEL = "focus_completion";

    private LockNotifications() {}

    static void createChannel(NotificationManager manager, String id, String name) {
        boolean hard = FocusService.CHANNEL.equals(id);
        NotificationChannel channel = new NotificationChannel(id, name,
                hard ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW);
        if (hard) {
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setDescription("Pop-up, lock screen countdown, and Emergency break controls");
        }
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    static PendingIntent openApp(Context context, boolean soft) {
        return PendingIntent.getActivity(context, soft ? 23 : 20,
                new Intent(context, MainActivity.class).setAction(soft ? OPEN_SOFT : OPEN_HARD)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean available(Context context, boolean soft) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String id = soft ? SOFT_CHANNEL : FocusService.CHANNEL;
        createChannel(manager, id, soft ? "Soft Lock" : "Hard Lock");
        NotificationChannel channel = manager.getNotificationChannel(id);
        return manager.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static boolean hardVisible(Context context) {
        if (!available(context, false)) return false;
        try {
            return android.provider.Settings.Secure.getInt(
                    context.getContentResolver(), "lock_screen_show_notifications", 1) != 0;
        } catch (SecurityException unavailable) {
            // The device can restrict access to its lock screen preference.
            return true;
        }
    }

    static void previewHard(Context context) {
        if (!available(context, false)) return;
        context.getSystemService(NotificationManager.class).notify(HARD_PREVIEW_ID,
                new Notification.Builder(context, FocusService.CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Focus Lock notification preview")
                        .setContentText("Hard Lock controls use this notification channel.")
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(openApp(context, false))
                        .setAutoCancel(true)
                        .setTimeoutAfter(10_000)
                        .build());
    }

    static Notification.Builder timer(Context context, String channel, String title, long end, boolean soft) {
        Notification.Builder builder = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentIntent(openApp(context, soft))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(end > 0);
        if (end > 0) {
            String time = ClockLabels.time(context, end);
            builder.setContentText("Ends at " + time)
                    .setWhen(end).setUsesChronometer(true).setChronometerCountDown(true);
        }
        return builder;
    }

    static void showSoft(Context context, SoftStore.Session session) {
        showSoft(context, session, session.test ? 0 : EmergencyBreaks.current(context).until);
    }

    static void showSoft(Context context, SoftStore.Session session, long breakUntil) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        long remaining = session.timer.elapsedEnd - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            manager.cancel(SOFT_ID);
            return;
        }
        if (!available(context, true)) return;
        boolean paused = !session.test && breakUntil > SystemClock.elapsedRealtime();
        long countdown = paused ? Math.min(remaining, breakUntil - SystemClock.elapsedRealtime()) : remaining;
        manager.notify(SOFT_ID, timer(context, SOFT_CHANNEL, session.test ? "Soft Lock test" : paused ? "Emergency break" : "Soft Lock",
                System.currentTimeMillis() + countdown, true).setTimeoutAfter(remaining).build());
    }

    static void showCompletion(Context context, int modes) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        manager.notify(COMPLETION_ID, completion(context, modes).build());
    }

    static Notification.Builder completion(Context context, int modes) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(COMPLETION_CHANNEL, "Session completion", NotificationManager.IMPORTANCE_LOW));
        boolean softOnly = modes == SessionCompletion.SOFT;
        return new Notification.Builder(context, COMPLETION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Focus complete")
                .setContentText("Your focus session has ended. Tap to see your result.")
                .setContentIntent(openApp(context, softOnly))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);
    }

    static void hideCompletion(Context context) {
        context.getSystemService(NotificationManager.class).cancel(COMPLETION_ID);
    }

    static void hideSoft(Context context) {
        context.getSystemService(NotificationManager.class).cancel(SOFT_ID);
    }
}
