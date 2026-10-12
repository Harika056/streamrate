package com.streamrate.gateway.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RetryAfterTest {

    @Test
    void roundsUpAndNeverBelowOne() {
        assertEquals(1, RetryAfter.seconds(0));
        assertEquals(1, RetryAfter.seconds(1));
        assertEquals(1, RetryAfter.seconds(1000));
        assertEquals(2, RetryAfter.seconds(1001));
        assertEquals(5, RetryAfter.seconds(4200));
    }
}
