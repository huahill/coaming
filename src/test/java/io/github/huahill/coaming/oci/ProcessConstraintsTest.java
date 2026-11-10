package io.github.huahill.coaming.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessConstraintsTest {
    @TempDir
    Path bundle;

    @Test
    void readsUserRlimitsAndOomScore() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {
                    "args": ["/bin/true"],
                    "oomScoreAdj": 100,
                    "rlimits": [{"type": "RLIMIT_NOFILE", "soft": 1024, "hard": 2048}],
                    "user": {"uid": 65534, "gid": 65534, "additionalGids": [1, 65534]}
                  },
                  "root": {"path": "rootfs"}
                }
                """);

        OciConfig config = OciConfig.read(bundle);

        assertEquals(new ProcessUser(65534, 65534, List.of(1, 65534)), config.user());
        assertEquals(List.of(new Rlimit("RLIMIT_NOFILE", 1024, 2048)), config.rlimits());
        assertEquals(100, config.oomScoreAdj());
    }

    @Test
    void anAbsentUserIsNotImplied() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {"process": {"args": ["/bin/true"]}, "root": {"path": "rootfs"}}
                """);

        OciConfig config = OciConfig.read(bundle);

        assertNull(config.user());
        assertEquals(List.of(), config.rlimits());
        assertNull(config.oomScoreAdj());
    }

    @Test
    void anAppArmorProfileIsKept() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {"process": {"args": ["/bin/true"], "apparmorProfile": "docker-default"}, "root": {"path": "rootfs"}}
                """);

        assertEquals("docker-default", OciConfig.read(bundle).apparmorProfile());
    }

    @Test
    void unconfinedAppArmorIsAccepted() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {"process": {"args": ["/bin/true"], "apparmorProfile": "unconfined"}, "root": {"path": "rootfs"}}
                """);

        assertNull(OciConfig.read(bundle).user());
    }

    @Test
    void aSelinuxLabelIsRejected() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {"process": {"args": ["/bin/true"], "selinuxLabel": "system_u:system_r:container_t:s0"}, "root": {"path": "rootfs"}}
                """);

        assertThrows(IllegalArgumentException.class, () -> OciConfig.read(bundle));
    }
}
