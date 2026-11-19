package com.focuslock.app;

/** Classifies consecutive address-bar observations for a single browser. */
final class BrowserNavigation {
    static final long VERIFY_HOST_MS = 220;
    static final long UNREADABLE_MS = 1_800;
    private String browser = "";
    private String pendingHost = "";
    private int window = -1;
    private long since;
    private long unreadableSince = -1;

    long blockedHostDelay(String id, int windowId, String host, long now) {
        select(id, windowId);
        unreadableSince = -1;
        if (!host.equals(pendingHost)) {
            pendingHost = host;
            since = now;
        }
        return Math.max(0, VERIFY_HOST_MS - (now - since));
    }

    long unreadableDelay(String id, int windowId, long now) {
        select(id, windowId);
        pendingHost = "";
        if (unreadableSince < 0) unreadableSince = now;
        return Math.max(0, UNREADABLE_MS - (now - unreadableSince));
    }

    private void select(String id, int windowId) {
        if (browser.equals(id) && window == windowId) return;
        reset();
        browser = id;
        window = windowId;
    }

    void reset() {
        browser = "";
        window = -1;
        pendingHost = "";
        unreadableSince = -1;
    }
}
