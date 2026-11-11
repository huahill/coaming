package io.github.huahill.coaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.huahill.coaming.runtime.CommandLine;
import org.junit.jupiter.api.Test;

class CoamingTest {
    @Test
    void identityIsCoaming() {
        assertEquals("coaming", Coaming.NAME);
        assertEquals("io.github.huahill", Coaming.GROUP_ID);
    }

    @Test
    void versionUsesTheBuildVersion() {
        assertFalse(Coaming.version().isBlank());
        assertEquals("coaming version " + Coaming.version(), Coaming.versionLine());
    }

    @Test
    void versionFlagExitsZero() throws Exception {
        assertEquals("version", CommandLine.parse(new String[] {"--version"}).command());
        assertEquals(0, CoamingMain.run(new String[] {"--version"}));
    }
}
