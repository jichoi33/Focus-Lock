package com.focuslock.app;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

final class SessionClock {
    final int boot;
    final long elapsed;
    final long wall;
    private static final int PROCESS_BOOT = -(new java.security.SecureRandom().nextInt(Integer.MAX_VALUE - 2) + 2);
    private static int bootCount = -1;

    SessionClock(Context context) {
        if (bootCount == -1) {
            // The process cannot survive a reboot. The fallback still tracks elapsed time
            // in this process on devices that omit the standard boot counter.
            bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, PROCESS_BOOT);
        }
        boot = bootCount;
        elapsed = SystemClock.elapsedRealtime();
        wall = System.currentTimeMillis();
    }

    TimedDeadline start(long duration) {
        return TimedDeadline.start(boot, elapsed, wall, duration);
    }

    TimedDeadline restore(TimedDeadline saved) {
        return saved.restore(boot, elapsed, wall);
    }

    TimedDeadline rebase(TimedDeadline saved) {
        return saved.rebase(boot, elapsed, wall);
    }
}
