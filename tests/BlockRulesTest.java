package com.focuslock.app;

import java.util.Collections;
import java.util.Set;

public final class BlockRulesTest {
    public static void main(String[] args) {
        equals("youtube.com", BlockRules.domain(" HTTPS://WWW.YouTube.Com:443/watch?v=1 "));
        equals("m.youtube.com", BlockRules.domain("m.youtube.com/shorts"));
        equals("xn--3e0b707e.kr", BlockRules.domain("한국.kr"));
        equals("reddit.com", BlockRules.domain("reddit.com.:8443/path"));
        equals("", BlockRules.domain("https://youtube.com@elsewhere.com/"));
        equals("", BlockRules.domain("file:///youtube.com/"));
        equals("", BlockRules.domain("*.youtube.com"));
        equals("", BlockRules.domain("/youtube.com"));

        Set<String> target = Collections.singleton("youtube.com");
        check(BlockRules.matches("youtube.com", target), "exact domain");
        check(BlockRules.matches("a.m.youtube.com", target), "nested subdomain");
        check(!BlockRules.matches("notyoutube.com", target), "suffix requires dot boundary");
        check(!BlockRules.matches("youtube.com.example.com", target), "not substring matching");
        check(!BlockRules.matches("", target), "empty host");
        Set<String> selected = Collections.singleton("com.example.study");
        Set<String> essential = Collections.singleton("com.example.home");
        check(!BlockRules.appBlocked("com.example.study", true, selected, essential),
                "allowed app stays accessible");
        check(BlockRules.appBlocked("com.example.browser", true, selected, essential),
                "unlisted browser is blocked without inspecting a URL");
        check(!BlockRules.appBlocked("com.example.home", true, selected, essential),
                "home remains available");
        check(!BlockRules.appBlocked("", true, selected, essential),
                "unidentified application window does not trigger lockout");
        check(BlockRules.appBlocked("com.example.study", false, selected, essential),
                "existing blocklist remains available");
        check(!BlockRules.appBlocked("com.example.browser", false, selected, essential),
                "blocklist leaves unselected apps available");
        check(BlockRules.appBlocked("com.example.browser", true, Collections.emptySet(), essential),
                "empty allowlist restricts all non-essential apps");
        check(BlockRules.customTabActivity("org.chromium.chrome.browser.customtabs.CustomTabActivity"),
                "Chromium custom tab receives the opt-in exception");
        check(BlockRules.customTabActivity("org.mozilla.fenix.customtabs.ExternalAppBrowserActivity"),
                "Firefox external app tab is recognized");
        check(!BlockRules.customTabActivity("org.chromium.chrome.browser.ChromeTabbedActivity"),
                "opening the standalone browser ends the exception");
        Set<String> changes = Set.of("com.example.browser", "com.example.home");
        check(BlockRules.tightenedApps(false, selected, changes).equals(Set.of("com.example.study", "com.example.browser", "com.example.home")),
                "tightening a blocklist adds apps and retains existing blocks");
        check(BlockRules.tightenedApps(true, selected, changes).equals(selected),
                "tightening an allowlist cannot add an app");
        check(BlockRules.tightenedApps(true, selected, selected).isEmpty(),
                "tightening an allowlist can revoke the final allowed app");
        check(!BlockRules.blockUnreadableBrowser(BlockRules.SAMSUNG_INTERNET, true),
                "a missing Samsung Internet address bar does not trigger a strict block");
        check(BlockRules.blockUnreadableBrowser("com.android.chrome", true), "strict mode still covers other browsers");
        check(!BlockRules.blockUnreadableBrowser("com.android.chrome", false), "strict mode must be enabled");
        equals("reddit.com", BlockRules.browserDomain("\u200ehttps://www.reddit.com/r/a b\u200f"));
        equals("reddit.com", BlockRules.browserDomain("reddit.com/out/https://example.com"));
        equals("reddit.com", BlockRules.browserDomain("reddit.com?query=value"));
        equals("", BlockRules.browserDomain("ftp://reddit.com/path"));
        equals("", BlockRules.browserDomain("https://reddit.com@example.org/path"));
        equals("", BlockRules.browserDomain("Search or enter address"));
        equals("", BlockRules.browserDomain(null));

        CoverRelease release = new CoverRelease();
        check(!release.confirmed(42, 1000), "one allowed result keeps the cover");
        check(!release.confirmed(42, 1099), "hold through transient focus changes");
        release.reset();
        check(!release.confirmed(42, 1100), "unidentified window breaks the allowed streak");
        check(!release.confirmed(43, 1200), "another window needs its own confirmation");
        check(release.confirmed(43, 1300), "stable allowed window can dismiss the cover");

        check(!release.shouldRecheckWithoutCover(1400), "a missing foreground root can be transient");
        check(!release.shouldRecheckWithoutCover(1999), "keep the cover during the recovery grace period");
        check(release.shouldRecheckWithoutCover(2000), "a persistently missing root triggers a fresh check without the cover");
        release.reset();
        check(!release.shouldRecheckWithoutCover(2100), "a fresh cover has a fresh recovery period");
        check(!release.confirmed(43, 2150), "an unknown window interrupts allowed-window confirmation");
        check(!release.shouldRecheckWithoutCover(2700), "an allowed observation ends the unknown-window streak");

        AppReturn appReturn = new AppReturn();
        check(appReturn.remaining("blocked.one", 2000) == 900, "show the notice before going home");
        check(appReturn.remaining("blocked.one", 2500) == 400, "rechecks do not restart the notice");
        check(appReturn.remaining("blocked.one", 2900) == 0, "return home once the notice has been shown");
        appReturn.markAttempted();
        check(appReturn.remaining("blocked.one", 3400) == Long.MAX_VALUE, "avoid repeating the home action");
        check(appReturn.remaining("blocked.two", 3500) == 900, "a different blocked app starts a new notice");
        appReturn.markAttempted();
        check(appReturn.remaining("blocked.two", 3550) == Long.MAX_VALUE, "manual home cancels the automatic action");
        appReturn.reset();
        check(appReturn.remaining("blocked.two", 4000) == 900, "reopening after dismissal starts a new notice");
        System.out.println("Domain, app, strict website, overlay release and home return assertions passed");
    }

    private static void equals(String expected, String actual) {
        check(expected.equals(actual), "expected " + expected + ", got " + actual);
    }

    private static void check(boolean success, String reason) {
        if (!success) throw new AssertionError(reason);
    }
}
