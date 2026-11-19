package com.focuslock.app;

import java.net.IDN;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class BlockRules {
    static final String SAMSUNG_INTERNET = "com.sec.android.app.sbrowser";
    private static final Pattern DISPLAY_PADDING = Pattern.compile("^[\\s\\p{Cf}]+|[\\s\\p{Cf}]+$");

    private BlockRules() {}

    static boolean appBlocked(String id, boolean allowlist, Set<String> selection, Set<String> essentials) {
        return !id.isEmpty() && !essentials.contains(id)
                && (allowlist != selection.contains(id));
    }

    static Set<String> tightenedApps(boolean allowlist, Set<String> current, Set<String> apps) {
        Set<String> next = new HashSet<>(current);
        if (allowlist) next.removeAll(apps);
        else next.addAll(apps);
        return next;
    }

    static boolean blockUnreadableBrowser(String id, boolean strict) {
        // Samsung Internet can omit the address bar from its accessibility tree during normal use.
        return strict && !SAMSUNG_INTERNET.equals(id);
    }

    static String domain(String input) {
        if (input == null) return "";
        String value = input.trim();
        if (value.isEmpty() || value.startsWith("/") && !value.startsWith("//")) return "";
        if (!value.startsWith("//") && !value.contains("://")) value = "//" + value;
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() != null && !uri.getScheme().equalsIgnoreCase("https")
                    && !uri.getScheme().equalsIgnoreCase("http")) return "";
            String authority = uri.getRawAuthority();
            if (authority == null || authority.contains("@") || authority.contains("%")) return "";
            String host = authority.replaceFirst(":[0-9]+$", "").replaceFirst("\\.$", "");
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host.contains(".") && host.length() <= 253 ? host : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    static String browserDomain(CharSequence text) {
        if (text == null) return "";
        String value = DISPLAY_PADDING.matcher(text).replaceAll("");
        int authorityStart = 0;
        if (value.startsWith("//")) authorityStart = 2;
        else if (value.regionMatches(true, 0, "https://", 0, 8)) authorityStart = 8;
        else if (value.regionMatches(true, 0, "http://", 0, 7)) authorityStart = 7;
        // The toolbar may display a decoded path or omit part of it. Only parse the origin.
        for (int end = authorityStart; end < value.length(); end++) {
            char character = value.charAt(end);
            if (character == '/' || character == '?' || character == '#') {
                return domain(value.substring(0, end));
            }
        }
        return domain(value);
    }

    static boolean matches(String host, Set<String> blocked) {
        for (String domain : blocked) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }
}
