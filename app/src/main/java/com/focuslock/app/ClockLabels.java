package com.focuslock.app;

import android.content.Context;
import android.text.format.DateFormat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class ClockLabels {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private ClockLabels() {}

    static boolean is24Hour(Context context) {
        return DateFormat.is24HourFormat(context.getApplicationContext());
    }

    static String time(int minute, boolean use24Hour) {
        return formatter(use24Hour).format(LocalTime.of(minute / 60, minute % 60));
    }

    static String time(Context context, long millis) {
        return formatter(is24Hour(context)).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String dateTime(long millis, boolean use24Hour) {
        var local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        return DATE.format(local) + ", " + formatter(use24Hour).format(local);
    }

    private static DateTimeFormatter formatter(boolean use24Hour) {
        return use24Hour ? CLOCK_24 : CLOCK_12;
    }
}
