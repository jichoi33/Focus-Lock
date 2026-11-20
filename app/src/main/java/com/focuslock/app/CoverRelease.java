package com.focuslock.app;

final class CoverRelease {
    static final long CONFIRM_MS = 100;
    static final long RECOVERY_MS = 600;
    private long unverifiedSince = -1;
    private int candidateWindowId = -1;
    private long firstSeen;

    boolean confirmed(int windowId, long now) {
        unverifiedSince = -1;
        if (windowId != candidateWindowId) {
            candidateWindowId = windowId;
            firstSeen = now;
        }
        return now - firstSeen >= CONFIRM_MS;
    }

    boolean shouldRecheckWithoutCover(long now) {
        candidateWindowId = -1;
        if (unverifiedSince < 0) unverifiedSince = now;
        return now - unverifiedSince >= RECOVERY_MS;
    }

    void reset() {
        candidateWindowId = -1;
        unverifiedSince = -1;
    }
}
