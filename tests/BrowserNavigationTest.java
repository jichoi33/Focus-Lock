package com.focuslock.app;

public final class BrowserNavigationTest {
    public static void main(String[] args) {
        BrowserNavigation navigation = new BrowserNavigation();
        check(navigation.unreadableDelay("browser", 1, 1_000) == 1_800, "An unreadable transition starts a grace period");
        check(navigation.unreadableDelay("browser", 1, 1_500) == 1_300, "A short navigation gap remains allowed");
        navigation.reset(); // Search field focused or a valid allowed address was observed.
        check(navigation.unreadableDelay("browser", 1, 2_600) == 1_800, "Editing and allowed pages reset the grace period");
        check(navigation.unreadableDelay("browser", 1, 4_400) == 0, "Continuously unreadable browser still triggers strict blocking");
        check(navigation.unreadableDelay("browser", 2, 4_401) == 1_800, "A new browser window gets its own grace period");

        check(navigation.blockedHostDelay("browser", 2, "example.com", 4_450) == 220, "Require a stable blocked host");
        navigation.reset();
        check(navigation.blockedHostDelay("browser", 2, "example.com", 4_700) == 220, "A transient blocked observation doesn't carry forward");
        check(navigation.blockedHostDelay("browser", 2, "example.com", 4_920) == 0, "A stable blocked host triggers the overlay");
        check(navigation.blockedHostDelay("browser", 2, "example.org", 4_921) == 220, "A different host is verified independently");
        System.out.println("Browser transition, editing, and strict-mode assertions passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
