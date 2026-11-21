package com.focuslock.app;

import android.content.Context;
import android.text.format.DateFormat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

final class ClockLabels {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private ClockLabels() {}

    static boolean is24Hour(Context context) {
        return DateFormat.is24HourFormat(context.getApplicationContext());
    }

    static String time(int minute, boolean use24Hour) {
        return formatter(use24Hour).format(LocalTime.of(minute / 60, minute % 60));
    }

    static String deadline(long millis, long now, boolean use24Hour, String action) {
        ZoneId zone = ZoneId.systemDefault();
        var end = Instant.ofEpochMilli(millis).atZone(zone);
        var today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long days = ChronoUnit.DAYS.between(today, end.toLocalDate());
        String time = formatter(use24Hour).format(end);
        if (days == 0) return action + " today at " + time;
        if (days == 1) return action + " tomorrow at " + time;
        if (days < 0) return action + " at " + dateTime(millis, use24Hour);
        return action + " on " + WEEKDAY.format(end) + " at " + time + "\n" + days + " days from today";
    }

    static long nextMidnight(long now) {
        ZoneId zone = ZoneId.systemDefault();
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static String unlockTime(long millis, long now, boolean use24Hour) {
        ZoneId zone = ZoneId.systemDefault();
        var end = Instant.ofEpochMilli(millis).atZone(zone);
        var today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        String time = formatter(use24Hour).format(end);
        return end.toLocalDate().equals(today) ? time : DATE.format(end) + ", " + time;
    }

    static String dateTime(long millis, boolean use24Hour) {
        var local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        return DATE.format(local) + ", " + formatter(use24Hour).format(local);
    }

    private static DateTimeFormatter formatter(boolean use24Hour) {
        return use24Hour ? CLOCK_24 : CLOCK_12;
    }
}
