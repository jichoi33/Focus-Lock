package com.focuslock.app;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

public final class TimedDeadlineTest {
    private static final long MINUTE = Rule.MINUTE;
    private static final long WALL = 1_800_000_000_000L;
    private static final long ELAPSED = 45 * MINUTE;
    private static final int BOOT = 7;

    public static void main(String[] args) {
        TimedDeadline timer = TimedDeadline.start(BOOT, ELAPSED, WALL, 20 * MINUTE);
        check(15 * MINUTE, timer.remaining(BOOT, ELAPSED + 5 * MINUTE, WALL + 120 * MINUTE), "clock moved forward");
        check(15 * MINUTE, timer.remaining(BOOT, ELAPSED + 5 * MINUTE, WALL - 120 * MINUTE), "clock moved backward");
        check(0, timer.remaining(BOOT, ELAPSED + 20 * MINUTE, WALL - MINUTE), "deadline after sleep");

        TimedDeadline rebased = timer.rebase(BOOT, ELAPSED + 5 * MINUTE, WALL + 120 * MINUTE);
        check(timer.elapsedEnd, rebased.elapsedEnd, "rebasing wall clock retains same-boot deadline");
        TimedDeadline rebooted = rebased.restore(BOOT + 1, 3 * MINUTE, WALL + 125 * MINUTE);
        check(13 * MINUTE, rebooted.elapsedEnd, "new boot resumes bounded remaining time");
        check(0, rebased.restore(BOOT + 1, 100, WALL + 200 * MINUTE).remaining(BOOT + 1, 100, WALL + 200 * MINUTE), "expired across reboot");
        check(15 * MINUTE, rebased.remaining(BOOT + 1, 0, WALL - MINUTE), "backdated reboot cannot exceed saved budget");
        TimedDeadline oldSoft = new TimedDeadline(-1, 0L, WALL + 20 * MINUTE, Long.MAX_VALUE);
        TimedDeadline migrated = oldSoft.restore(BOOT, ELAPSED + 5 * MINUTE, WALL + 5 * MINUTE);
        check(timer.elapsedEnd, migrated.elapsedEnd, "session from 1.3 migrates to an elapsed deadline");

        Rule quick = new Rule();
        quick.id = Rule.QUICK_ID;
        quick.from = WALL;
        quick.to = timer.wallEnd;
        quick.timer = timer;
        Rule oneTime = new Rule();
        oneTime.from = WALL + 120 * MINUTE;
        oneTime.to = WALL + 150 * MINUTE;
        long jumped = WALL + 130 * MINUTE;
        Rule.State state = Rule.at(Arrays.asList(quick, oneTime), jumped, ELAPSED + 5 * MINUTE, BOOT, ZoneId.of("UTC"));
        check(2, state.active.size(), "elapsed quick and wall-clock calendar rules both remain represented");
        check(timer.elapsedEnd, state.nextElapsed, "immediate lock uses its own elapsed alarm");
        check(oneTime.to, state.nextWall, "calendar rule retains civil-time alarm");
        Rule.State ended = Rule.at(Collections.singletonList(quick), WALL - 1, timer.elapsedEnd, BOOT, ZoneId.of("UTC"));
        check(0, ended.active.size(), "elapsed expiry wins over a rolled-back calendar");
        System.out.println("12 elapsed-timer and alarm separation assertions passed");
    }

    private static void check(long expected, long actual, String scenario) {
        if (expected != actual) throw new AssertionError(scenario + ": expected " + expected + ", got " + actual);
    }
}
