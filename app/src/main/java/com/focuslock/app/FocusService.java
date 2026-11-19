package com.focuslock.app;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;



public final class FocusService extends Service {
    static final String CHANNEL = "focus_session";
    static final String BREAK = "com.focuslock.app.BREAK";
    static final String END_TEST = "com.focuslock.app.END_HARD_TEST";
    static final String EXTEND = "com.focuslock.app.EXTEND";
    static final long MAX_QUICK = 24 * 60 * Rule.MINUTE;
    private static final int NOTIFICATION_ID = 81;
    private static final int WAITING_NOTICE_ID = 88;
    private static final long UNLOCK_RETRY_MS = 50;
    private static final long UNLOCK_RETRY_WINDOW_MS = 500;
    private static final long UNLOCK_FALLBACK_MS = 1000;
    static boolean running;
    // This service and the accessibility service run in the same process and on the main thread.
    static long settingsBlockedUntil;
    static Runnable enforcementChanged;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FocusStore store;
    private FocusHistory history;
    private SessionCompletion completion;
    private NotificationManager notifications;
    private KeyguardManager keyguard;
    private PowerManager power;
    private int foregroundId;
    private String noticeKey = "";
    private long scheduledWall = Long.MIN_VALUE;
    private long scheduledElapsed = Long.MIN_VALUE;
    private long retryUnlockUntil;
    private final Runnable tick = this::sync;
    private final Runnable unlockCheck = this::checkUnlock;
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                retryUnlockUntil = 0;
                handler.removeCallbacks(unlockCheck);
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                retryUnlockUntil = SystemClock.uptimeMillis() + UNLOCK_RETRY_WINDOW_MS;
                if (foregroundId != 0) updateSession(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                sync();
            }
        }
    };

    static void refresh(Context context) {
        Intent intent = new Intent(context, FocusService.class);
        if (running) context.startService(intent);
        else context.startForegroundService(intent);
    }

    static void createChannel(NotificationManager manager) {
        LockNotifications.createChannel(manager, CHANNEL, "Hard Lock");
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        store = new FocusStore(this);
        history = new FocusHistory(this, false);
        completion = new SessionCompletion(this);
        notifications = getSystemService(NotificationManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        power = getSystemService(PowerManager.class);
        createChannel(notifications);

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        // These protected broadcasts are sent by Android, including components outside the app UID.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenState, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(screenState, filter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (END_TEST.equals(action)) {
            for (Rule.Window window : store.state(new SessionClock(this)).active) {
                if (window.rule.test) {
                    store.remove(window.rule.id);
                    break;
                }
            }
        }
        if (BREAK.equals(action) && hasRegularLock(store.state(new SessionClock(this)))) EmergencyBreaks.begin(this);
        if (EXTEND.equals(action)) extendQuick();
        updateSession(false);
        return START_STICKY;
    }

    private static boolean hasRegularLock(Rule.State state) {
        return state.active.stream().anyMatch(window -> !window.rule.test);
    }

    private void extendQuick() {
        SessionClock clock = new SessionClock(this);
        for (Rule.Window window : store.state(clock).active) {
            if (!window.rule.quick() || window.rule.test) continue;
            long remaining = window.elapsedEnd - clock.elapsed;
            long duration = Math.min(remaining + 10 * Rule.MINUTE, MAX_QUICK);
            if (duration > remaining) {
                window.rule.timer = clock.start(duration);
                window.rule.to = window.rule.timer.wallEnd;
                store.put(window.rule);
            }
            return;
        }
    }

    private void sync() {
        if (foregroundId != 0) updateSession(false);
    }

    private void updateSession(boolean justUnlocked) {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(unlockCheck);
        SessionClock clock = new SessionClock(this);
        Rule.State state = store.state(clock);
        EmergencyBreaks breaks = EmergencyBreaks.read(this, clock, state, new SoftStore(this).read());
        if (breaks.until > clock.elapsed) state.nextElapsed = Math.min(state.nextElapsed, breaks.until);
        if (state.nextWall != scheduledWall || state.nextElapsed != scheduledElapsed) {
            AlarmReceiver.schedule(this, state);
            scheduledWall = state.nextWall;
            scheduledElapsed = state.nextElapsed;
        }

        if (state.active.isEmpty() || !FocusAdmin.enabled(this)
                || !keyguard.isDeviceSecure()) {
            if (state.active.isEmpty()) completion.reconcileHard(clock, state);
            else completion.cancelHard();
            setEnforcedUntil(0L);
            history.stop();
            if (state.active.isEmpty() && handleCompletion(clock, justUnlocked)) return;
            if (foregroundId == 0) publish(NOTIFICATION_ID, notification("Starting Hard Lock", 0, false, false, false));
            stopSelf();
            return;
        }

        boolean wasWaiting = foregroundId == WAITING_NOTICE_ID;
        if (wasWaiting) noticeKey = "";
        long end = state.activeEnd();
        boolean testing = !hasRegularLock(state);
        boolean hasQuick = state.active.stream().anyMatch(window -> window.rule.quick() && !window.rule.test);
        boolean paused = !testing && breaks.until > clock.elapsed;
        long noticeEnd = paused ? clock.wall + Math.min(end - clock.wall, breaks.until - clock.elapsed) : end;
        updateNotice(noticeEnd, hasQuick, breaks, paused, testing);
        if (wasWaiting && completion.pending() != 0) LockNotifications.showCompletion(this, completion.pending());
        if (!paused && !enforce()) {
            completion.cancelHard();
            return;
        }
        completion.trackHard(clock, state);
        long protectionEnd = clock.elapsed + (end - clock.wall);
        java.util.Set<String> sessions = new java.util.HashSet<>();
        long regularEnd = 0;
        for (Rule.Window window : state.active) {
            if (window.rule.test) continue;
            sessions.add(window.key());
            regularEnd = Math.max(regularEnd, clock.elapsed + (window.end - clock.wall));
        }
        history.sample(clock.elapsed, paused ? 0 : regularEnd, sessions);
        setEnforcedUntil(protectionEnd);
        if (paused) {
            handler.postDelayed(tick, Math.max(1, Math.min(breaks.until - clock.elapsed, state.next - clock.wall)));
            return;
        }
        scheduleUnlockCheck();
        handler.postDelayed(tick, Math.max(1, Math.min(10_000, state.next - clock.wall)));
    }

    private boolean handleCompletion(SessionClock clock, boolean justUnlocked) {
        long remaining = completion.autoOpenRemaining(clock);
        if (remaining == 0) return false;
        if (justUnlocked && !keyguard.isKeyguardLocked()) {
            completion.consumeAutoOpen();
            if (enforcementChanged == null) {
                try {
                    startActivity(new Intent(this, MainActivity.class).setAction(LockNotifications.OPEN_HARD)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } catch (IllegalStateException | SecurityException blocked) {
                    Log.i("FocusLock", "Couldn’t open completion after unlock", blocked);
                }
            }
            return false;
        }
        if (!keyguard.isKeyguardLocked()) return false;

        if (foregroundId != WAITING_NOTICE_ID) {
            publish(WAITING_NOTICE_ID,
                    LockNotifications.completion(this, completion.pending()).setAutoCancel(false).setOngoing(true).build());
            LockNotifications.hideCompletion(this);
        }
        handler.postDelayed(tick, remaining);
        return true;
    }

    private void checkUnlock() {
        if (!power.isInteractive()) return;
        if (keyguard.isKeyguardLocked()) {
            scheduleUnlockCheck();
        } else {
            // Recheck session expiry and permission before asking Android to lock again.
            sync();
        }
    }

    private void scheduleUnlockCheck() {
        if (!power.isInteractive()) return;
        long delay = SystemClock.uptimeMillis() < retryUnlockUntil ? UNLOCK_RETRY_MS : UNLOCK_FALLBACK_MS;
        handler.postDelayed(unlockCheck, delay);
    }

    private boolean enforce() {
        // Android's lock screen owns the incoming and emergency call UI.
        if (keyguard.isKeyguardLocked()) return true;
        retryUnlockUntil = 0;
        try {
            getSystemService(DevicePolicyManager.class).lockNow();
            return true;
        } catch (SecurityException denied) {
            Log.i("FocusLock", "Device admin no longer available", denied);
            setEnforcedUntil(0L);
            stopSelf();
            return false;
        }
    }

    private void updateNotice(long end, boolean hasQuick, EmergencyBreaks breaks, boolean paused, boolean testing) {
        boolean canStop = !testing && breaks.enabled && breaks.remaining() > 0 && !paused;
        String key = end / 1000 + "/" + hasQuick + "/" + canStop + "/" + paused + "/" + testing + "/" + ClockLabels.is24Hour(this);
        if (key.equals(noticeKey)) return;
        noticeKey = key;
        notifications.cancel(AlarmReceiver.SCHEDULE_NOTICE_ID);
        publish(NOTIFICATION_ID, notification(testing ? "Hard Lock test" : paused ? "Emergency break" : "Hard Lock", end, hasQuick, canStop, testing));
    }

    private void publish(int id, Notification notification) {
        if (foregroundId == id) {
            notifications.notify(id, notification);
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(id, notification);
        }
        foregroundId = id;
    }

    private Notification notification(String title, long end, boolean extend, boolean canStop, boolean testing) {
        Notification.Builder builder = LockNotifications.timer(this, CHANNEL, title, end, false);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (testing) builder.addAction(action("End test", END_TEST, 24));
        if (canStop) builder.addAction(action("Emergency break", BREAK, 21));
        if (extend) builder.addAction(action("Add 10 min", EXTEND, 22));
        return builder.build();
    }

    private Notification.Action action(String label, String action, int requestCode) {
        PendingIntent pending = PendingIntent.getService(this, requestCode,
                new Intent(this, FocusService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action.Builder builder = new Notification.Action.Builder((Icon) null, label, pending);
        if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(false);
        return builder.build();
    }

    @Override public void onDestroy() {
        running = false;
        history.stop();
        setEnforcedUntil(0L);
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(screenState);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (foregroundId == WAITING_NOTICE_ID && completion.pending() != 0) {
            LockNotifications.showCompletion(this, completion.pending());
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static void setEnforcedUntil(long end) {
        boolean changed = (settingsBlockedUntil == 0L) != (end == 0L);
        settingsBlockedUntil = end;
        if (changed && enforcementChanged != null) enforcementChanged.run();
    }
}
