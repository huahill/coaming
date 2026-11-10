package io.github.huahill.coaming.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(List.of(new Namespace("pid", null), new Namespace("mount", null), new Namespace("uts", null)), config.namespaces());
        assertFalse(config.namespacesSpecified() && config.namespaces().isEmpty());
        assertTrue(config.namespacesSpecified());
        assertEquals(List.of(), config.env());
    }

    @Test
    void readsANamespacePath() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/sleep"]},
                  "root": {"path": "rootfs"},
                  "linux": {"namespaces": [{"type": "pid"}, {"type": "network", "path": "/proc/12/ns/net"}]}
                }
                """);

        OciConfig config = OciConfig.read(bundle);

        assertEquals(new Namespace("network", "/proc/12/ns/net"), config.namespaces().get(1));
    }

    @Test
    void plansPauseNetworkAndHostPid() {
        List<String> args = NamespacePlan.arguments(List.of(
                new Namespace("pid", null),
                new Namespace("mount", null),
                new Namespace("uts", null),
                new Namespace("ipc", null),
                new Namespace("network", "/proc/12/ns/net")), true);

        assertEquals(List.of("--unshare", "pid,mount,uts,ipc", "--join", "network=/proc/12/ns/net"), args);
    }

    @Test
    void hostPidDoesNotCreateAPidNamespace() {
        assertEquals(List.of("--unshare", "mount,uts"), NamespacePlan.arguments(List.of(
                new Namespace("mount", null),
                new Namespace("uts", null)), true));
    }

    @Test
    void missingNamespacesUseTheRuntimeDefault() {
        assertEquals(List.of("--unshare", "pid,mount,uts,ipc,network"), NamespacePlan.arguments(List.of(), false));
    }

    @Test
    void anEmptyNamespaceListSharesTheHost() {
        assertEquals(List.of("--unshare", "none"), NamespacePlan.arguments(List.of(), true));
    }

    @Test
    void userNamespaceIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NamespacePlan.arguments(List.of(new Namespace("user", null)), true));
        assertTrue(failure.getMessage().contains("user"));
    }


    @Test
    void readsABindMount() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"},
                  "mounts": [{"destination": "/data", "type": "bind", "source": "/host/data", "options": ["rbind", "rw", "rprivate"]}]
                }
                """);

        OciMount mount = OciConfig.read(bundle).mounts().getFirst();

        assertEquals("/data", mount.destination());
        assertEquals("/host/data", mount.source());
    }

    @Test
    void plansHostPathPropagation() {
        MountPlan.Prepared prepared = MountPlan.prepare(new OciMount("/data", "bind", "/host/data", List.of("rbind", "rw", "rshared")));

        assertEquals(MountPlan.BIND | MountPlan.REC, prepared.flags());
        assertEquals(0, prepared.remount());
        assertEquals(MountPlan.REC | MountPlan.SHARED, prepared.propagation());
    }

    @Test
    void readOnlyBindIsARemount() {
        MountPlan.Prepared prepared = MountPlan.prepare(new OciMount("/data", "bind", "/host/data", List.of("bind", "ro")));

        assertEquals(MountPlan.BIND, prepared.flags());
        assertEquals(MountPlan.BIND | MountPlan.REMOUNT | MountPlan.RDONLY, prepared.remount());
        assertEquals(MountPlan.REC | MountPlan.PRIVATE, prepared.propagation());
    }

    @Test
    void rejectsAnUnknownMountOption() {
        assertThrows(IllegalArgumentException.class,
                () -> MountPlan.prepare(new OciMount("/data", "bind", "/host/data", List.of("nosymfollow"))));
    }


    @Test
    void readsMaskedPaths() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"},
                  "linux": {"maskedPaths": ["/proc/kcore", "/sys/firmware"]}
                }
                """);

        assertEquals(List.of("/proc/kcore", "/sys/firmware"), OciConfig.read(bundle).maskedPaths());
    }

    private static java.util.List<String> ListOf(String... values) {
        return java.util.List.of(values);
    }
}
