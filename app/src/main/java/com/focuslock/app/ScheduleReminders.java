package com.focuslock.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.service.notification.StatusBarNotification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class ScheduleReminders {
    static final String ACTION = "com.focuslock.app.SCHEDULE_REMINDER";
    static final String CHANNEL = "schedule_reminders";
    private static final int ALARM_CODE = 73;
    private static final int NOTIFICATION_ID = 87;
    private static final long LEAD_TIME = 5 * Rule.MINUTE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", Locale.ENGLISH);

    private ScheduleReminders() {}

    static NotificationChannel channel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Scheduled lock reminders",
                NotificationManager.IMPORTANCE_DEFAULT));
        return manager.getNotificationChannel(CHANNEL);
    }

    static boolean available(Context context) {
        return context.getSystemService(NotificationManager.class).areNotificationsEnabled()
                && channel(context).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static PendingIntent alarm(Context context) {
        return PendingIntent.getBroadcast(context, ALARM_CODE,
                new Intent(context, AlarmReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void cancel(Context context) {
        context.getSystemService(AlarmManager.class).cancel(alarm(context));
        removeOldNotifications(context.getSystemService(NotificationManager.class), java.util.Collections.emptySet());
    }

    static void schedule(Context context, Rule.State state) {
        if (!available(context) || state.upcoming.isEmpty()) {
            cancel(context);
            return;
        }

        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        SharedPreferences prefs = context.getSharedPreferences("schedule_reminders", Context.MODE_PRIVATE);
        Set<String> delivered = prefs.getStringSet("delivered", java.util.Collections.emptySet());
        Set<String> current = new HashSet<>();
        Set<String> sent = new HashSet<>();
        for (Rule.Window window : state.upcoming) {
            if (window.start <= now) continue;
            long remindAt = window.start - LEAD_TIME;
            if (remindAt > now) {
                next = Math.min(next, remindAt);
                continue;
            }
            String key = window.key();
            current.add(key);
            if (!delivered.contains(key)) notify(context, notifications, key, window, now);
            sent.add(key);
        }
        if (!sent.equals(delivered)) prefs.edit().putStringSet("delivered", sent).apply();
        removeOldNotifications(notifications, current);

        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent event = alarm(context);
        if (next == Long.MAX_VALUE) {
            alarms.cancel(event);
            return;
        }
        if (AlarmReceiver.canSchedule(context)) {
            try {
                // Keep allow-while-idle alarm quota available for the actual lock boundary.
                alarms.setExact(AlarmManager.RTC_WAKEUP, next, event);
                return;
            } catch (SecurityException ignored) {
                // Exact-alarm permission can change between the check and scheduling.
            }
        }
        alarms.set(AlarmManager.RTC_WAKEUP, next, event);
    }

    private static void removeOldNotifications(NotificationManager manager, Set<String> current) {
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (notification.getId() == NOTIFICATION_ID && !current.contains(notification.getTag())) {
                manager.cancel(notification.getTag(), NOTIFICATION_ID);
            }
        }
    }

    private static void notify(Context context, NotificationManager manager, String key, Rule.Window window, long now) {
        ZoneId zone = ZoneId.systemDefault();
        String range = TIME.format(Instant.ofEpochMilli(window.start).atZone(zone)) + " → "
                + TIME.format(Instant.ofEpochMilli(window.end).atZone(zone));
        String name = window.rule.name == null || window.rule.name.trim().isEmpty() ? "Scheduled Hard Lock" : window.rule.name;
        Intent openSchedule = new Intent(context, MainActivity.class).setAction(LockNotifications.OPEN_SCHEDULE)
                .setData(Uri.fromParts("focuslock", key, null))
                .putExtra(LockNotifications.RULE_ID, window.rule.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, ALARM_CODE, openSchedule,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(key, NOTIFICATION_ID, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name + " starts soon")
                .setContentText(range)
                .setStyle(new Notification.BigTextStyle().bigText(range))
                .setContentIntent(open)
                .setTimeoutAfter(window.start - now)
                .setAutoCancel(true)
                .build());
    }
}
