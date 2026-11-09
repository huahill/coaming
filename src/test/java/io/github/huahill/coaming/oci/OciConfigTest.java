package io.github.huahill.coaming.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OciConfigTest {
    @TempDir
    Path bundle;

    @Test
    void readsTheFieldsPhaseOneRuns() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "hostname": "demo",
                  "process": {"cwd": "/work", "args": ["/bin/echo", "hi"]},
                  "root": {"path": "rootfs"},
                  "linux": {"namespaces": [{"type": "pid"}, {"type": "mount"}, {"type": "uts"}]}
                }
                """);

        OciConfig config = OciConfig.read(bundle);

        assertEquals(ListOf("/bin/echo", "hi"), config.args());
        assertEquals("/work", config.cwd());
        assertEquals(bundle.resolve("rootfs"), config.root(bundle));
        assertEquals("demo", config.hostname());
        assertEquals(ListOf("pid", "mount", "uts"), config.namespaces());
        assertEquals(List.of(), config.env());
    }

    private static java.util.List<String> ListOf(String... values) {
        return java.util.List.of(values);
    }
}
