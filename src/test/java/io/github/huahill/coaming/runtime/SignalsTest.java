package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SignalsTest {
    @Test
    void acceptsNamesWithOrWithoutTheSigPrefix() {
        assertEquals(15, Signals.number("TERM"));
        assertEquals(15, Signals.number("SIGTERM"));
        assertEquals(10, Signals.number("USR1"));
        assertEquals(12, Signals.number("SIGUSR2"));
        assertEquals(9, Signals.number("9"));
    }
}
