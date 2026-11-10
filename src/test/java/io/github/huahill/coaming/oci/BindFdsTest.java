package io.github.huahill.coaming.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BindFdsTest {
    @Test
    void rewritesAPreparedBindSource() {
        Map<String, Integer> descriptors = BindFds.descriptors(name -> switch (name) {
            case "COAMING_BIND_COUNT" -> "1";
            case "COAMING_BIND_DST_0" -> "/dev/shm";
            case "COAMING_BIND_FD_0" -> "200";
            default -> null;
        });

        assertEquals("/proc/self/fd/200", BindFds.source("/dev/shm", "/run/hidden/shm", descriptors));
        assertEquals("proc", BindFds.source("/proc", "proc", descriptors));
    }
}
