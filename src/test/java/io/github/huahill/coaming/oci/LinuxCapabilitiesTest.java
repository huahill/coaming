package io.github.huahill.coaming.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LinuxCapabilitiesTest {
    @Test
    void parsesNamedSetsAndLeavesAnOmittedListEmpty() {
        LinuxCapabilities caps = LinuxCapabilities.parse("""
                {
                  "bounding": ["CAP_NET_BIND_SERVICE", "CAP_AUDIT_WRITE"],
                  "effective": ["CAP_NET_BIND_SERVICE"],
                  "permitted": ["CAP_NET_BIND_SERVICE", "CAP_AUDIT_WRITE"],
                  "inheritable": ["CAP_NET_BIND_SERVICE"]
                }
                """);

        assertEquals((1L << 10) | (1L << 29), caps.bounding());
        assertEquals(1L << 10, caps.effective());
        assertEquals((1L << 10) | (1L << 29), caps.permitted());
        assertEquals(1L << 10, caps.inheritable());
        assertEquals(0L, caps.ambient());
    }

    @Test
    void absentCapabilitiesAreNotApplied() {
        assertNull(LinuxCapabilities.parse(null));
    }

    @Test
    void anUnknownNameFails() {
        assertThrows(IllegalArgumentException.class, () -> LinuxCapabilities.parse("""
                {"effective": ["CAP_NOT_A_CAP"]}
                """));
    }

    @Test
    void ambientMustAlsoBePermittedAndInheritable() {
        assertThrows(IllegalArgumentException.class, () -> LinuxCapabilities.parse("""
                {"ambient": ["CAP_NET_BIND_SERVICE"], "permitted": ["CAP_NET_BIND_SERVICE"]}
                """));
    }

    @Test
    void configAndExecKeepTheSameSets() throws Exception {
        Path bundle = Files.createTempDirectory("caps");
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {
                    "args": ["/bin/true"],
                    "noNewPrivileges": true,
                    "capabilities": {"effective": ["CAP_KILL"]}
                  },
                  "root": {"path": "rootfs"}
                }
                """);

        OciConfig config = OciConfig.read(bundle);
        OciProcess process = OciProcess.parse("""
                {"args":["/bin/true"],"noNewPrivileges":true,"capabilities":{"bounding":["CAP_KILL"]}}
                """);

        assertTrue(config.noNewPrivileges());
        assertEquals(1L << 5, config.capabilities().effective());
        assertTrue(process.noNewPrivileges());
        assertEquals(1L << 5, process.capabilities().bounding());
    }
}
