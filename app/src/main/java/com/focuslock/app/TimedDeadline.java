package com.focuslock.app;

/** A timer uses elapsed time on this boot and a bounded wall-clock estimate after reboot. */
final class TimedDeadline {
    final int boot;
    final long elapsedEnd;
    final long wallEnd;
    final long budget;

    TimedDeadline(int boot, long elapsedEnd, long wallEnd, long budget) {
        this.boot = boot;
        this.elapsedEnd = elapsedEnd;
        this.wallEnd = wallEnd;
        this.budget = budget;
    }

    static TimedDeadline start(int boot, long elapsed, long wall, long duration) {
        return new TimedDeadline(boot, elapsed + duration, wall + duration, duration);
    }

    long remaining(int currentBoot, long elapsed, long wall) {
        if (currentBoot == boot && boot != -1) return Math.max(0L, elapsedEnd - elapsed);
        return Math.max(0L, Math.min(budget, wallEnd - wall));
    }

    TimedDeadline restore(int currentBoot, long elapsed, long wall) {
        return currentBoot == boot && boot != -1 ? this
                : start(currentBoot, elapsed, wall, remaining(currentBoot, elapsed, wall));
    }

    TimedDeadline rebase(int currentBoot, long elapsed, long wall) {
        long remaining = remaining(currentBoot, elapsed, wall);
        return start(currentBoot, elapsed, wall, remaining);
    }
}
