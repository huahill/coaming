package io.github.huahill.coaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProbeMainTest {
    @Test
    void missingProbeDoesNotClaimJavaRanAfterIt() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = ProbeMain.run(Map.of(), new PrintStream(buffer));
        String text = buffer.toString(StandardCharsets.UTF_8);

        assertEquals(4, code);
        assertTrue(text.contains("probe=missing"));
        assertFalse(text.contains("java=1"));
    }

    @Test
    void recordedProbeIsSeparateFromTheJvmThreadCount() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = ProbeMain.run(
                Map.of("COAMING_PROBE_RC", "0", "COAMING_PROBE_THREADS", "1"),
                new PrintStream(buffer));
        String text = buffer.toString(StandardCharsets.UTF_8);

        assertEquals(0, code);
        assertTrue(text.contains("probe=0"));
        assertTrue(text.contains("threads=1"));
        assertTrue(text.contains("java=1"));
        assertFalse(text.contains("live_threads=1\n") || text.endsWith("live_threads=1"));
    }
}
