package com.streamrate.gateway.ratelimit;

/** Result of one token-bucket check. `degraded` means Redis failed and we fell back to fail-open. */
public record Decision(boolean allowed, long remaining, long retryAfterMs, boolean degraded) {

    public static Decision degradedAllow() {
        return new Decision(true, -1, 0, true);
    }

    public static Decision degradedReject() {
        return new Decision(false, 0, 1000, true);
    }
}
