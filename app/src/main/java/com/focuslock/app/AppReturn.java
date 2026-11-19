package com.focuslock.app;

final class AppReturn {
    private static final long NOTICE_MS = 900;
    private String blockedPackage;
    private long returnAt;
    private boolean attempted;

    long remaining(String packageName, long now) {
        if (!packageName.equals(blockedPackage)) {
            blockedPackage = packageName;
            returnAt = now + NOTICE_MS;
            attempted = false;
        }
        return attempted ? Long.MAX_VALUE : Math.max(0, returnAt - now);
    }

    void markAttempted() {
        attempted = true;
    }

    void reset() {
        blockedPackage = null;
        attempted = false;
    }
}
