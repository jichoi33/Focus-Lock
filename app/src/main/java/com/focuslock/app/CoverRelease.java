package com.focuslock.app;

final class CoverRelease {
    static final long CONFIRM_MS = 100;
    private int candidateWindowId = -1;
    private long firstSeen;

    boolean confirmed(int windowId, long now) {
        if (windowId != candidateWindowId) {
            candidateWindowId = windowId;
            firstSeen = now;
        }
        return now - firstSeen >= CONFIRM_MS;
    }

    void reset() {
        candidateWindowId = -1;
    }
}
