package com.streamrate.gateway.ratelimit;

/** Converts milliseconds to the whole seconds the Retry-After header needs (rounded up, at least 1). */
public final class RetryAfter {
    private RetryAfter() {}

    public static long seconds(long retryAfterMs) {
        return Math.max(1, (retryAfterMs + 999) / 1000);
    }
}
