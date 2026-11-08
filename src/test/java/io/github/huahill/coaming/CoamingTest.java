package io.github.huahill.coaming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoamingTest {
    @Test
    void identityIsCoaming() {
        assertEquals("coaming", Coaming.NAME);
        assertEquals("io.github.huahill", Coaming.GROUP_ID);
    }
}
