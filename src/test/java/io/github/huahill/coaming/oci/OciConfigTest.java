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
        assertTrue(config.hooks().isEmpty());
    }

    @Test
    void readsSixOciHooks() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"},
                  "hooks": {
                    "prestart": [{"path": "/hooks/prestart"}],
                    "createRuntime": [{"path": "/hooks/createRuntime", "args": ["/hooks/createRuntime", "a"], "env": ["A=1"], "timeout": 3}],
                    "createContainer": [{"path": "/hooks/createContainer"}],
                    "startContainer": [{"path": "/hooks/startContainer"}],
                    "poststart": [{"path": "/hooks/poststart"}],
                    "poststop": [{"path": "/hooks/poststop"}]
                  }
                }
                """);
        OciConfig config = OciConfig.read(bundle);
        assertEquals("/hooks/prestart", config.hooks().get("prestart").getFirst().path());
        assertEquals("/hooks/createRuntime", config.hooks().get("createRuntime").getFirst().path());
        assertEquals(List.of("/hooks/createRuntime", "a"), config.hooks().get("createRuntime").getFirst().args());
        assertEquals(List.of("A=1"), config.hooks().get("createRuntime").getFirst().env());
        assertEquals(3, config.hooks().get("createRuntime").getFirst().timeout());
        assertEquals("/hooks/createContainer", config.hooks().get("createContainer").getFirst().path());
        assertEquals("/hooks/startContainer", config.hooks().get("startContainer").getFirst().path());
        assertEquals("/hooks/poststart", config.hooks().get("poststart").getFirst().path());
        assertEquals("/hooks/poststop", config.hooks().get("poststop").getFirst().path());
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
    void userNamespaceRequiresMappings() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NamespacePlan.arguments(List.of(new Namespace("user", null)), true));
        assertTrue(failure.getMessage().contains("uid and gid"));
    }

    @Test
    void plansANewUserNamespaceBeforeTheOthers() {
        List<IdMap> maps = List.of(new IdMap(0, 100000, 65536));

        assertEquals(List.of("--unshare", "user,pid,mount", "--uid-map", "0:100000:65536", "--gid-map", "0:100000:65536"),
                NamespacePlan.arguments(List.of(new Namespace("pid", null), new Namespace("user", null), new Namespace("mount", null)), true, maps, maps));
    }

    @Test
    void joinsAnExistingUserNamespaceWithoutRewritingMaps() {
        assertEquals(List.of("--unshare", "mount", "--join", "user=/proc/12/ns/user"),
                NamespacePlan.arguments(List.of(new Namespace("user", "/proc/12/ns/user"), new Namespace("mount", null)), true, List.of(new IdMap(0, 100000, 65536)), List.of(new IdMap(0, 100000, 65536))));
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
    void acceptsTheHighHostIdKubernetesAssigns() {
        assertEquals((int) 3918528512L, IdMap.bits(3918528512L));
        assertThrows(IllegalArgumentException.class, () -> IdMap.bits(0x1_0000_0000L));
    }

    @Test
    void plansAnIdmappedBindWithoutPassingTheOptionToMount() {
        MountPlan.Prepared prepared = MountPlan.prepare(new OciMount(
                "/data", "bind", "/host/data", List.of("bind", "ro", "idmap")));

        assertEquals(MountPlan.Idmap.MOUNT, prepared.idmap());
        assertEquals("", prepared.data());
        assertEquals(List.of("--idmap", "/host/data", "/data", "0", "0", "1048577", "278528"),
                IdmapPlan.arguments(List.of(new OciMount("/data", "bind", "/host/data", List.of("bind", "ro", "idmap"))), List.of(new IdMap(0, 100000, 65536)), List.of(new IdMap(0, 100000, 65536)), true));
    }

    @Test
    void rejectsIdmapWithoutANewUserNamespace() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> IdmapPlan.arguments(List.of(new OciMount("/data", "bind", "/host/data", List.of("bind", "idmap"))), List.of(), List.of(), false));

        assertTrue(failure.getMessage().contains("idmap"));
    }

    @Test
    void rejectsMountMappingsThatDifferFromTheContainer() {
        OciMount mount = new OciMount("/data", "bind", "/host/data", List.of("bind", "ridmap"), List.of(new IdMap(0, 200000, 65536)), List.of(new IdMap(0, 200000, 65536)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> IdmapPlan.arguments(List.of(mount), List.of(new IdMap(0, 100000, 65536)), List.of(new IdMap(0, 100000, 65536)), true));

        assertTrue(failure.getMessage().contains("match"));
    }

    @Test
    void acceptsTheMountsAKubernetesPodUses() {
        MountPlan.Prepared dev = MountPlan.prepare(new OciMount(
                "/dev", "tmpfs", "tmpfs", List.of("nosuid", "strictatime", "mode=755", "size=65536k")));
        MountPlan.Prepared pts = MountPlan.prepare(new OciMount(
                "/dev/pts", "devpts", "devpts", List.of("nosuid", "noexec", "newinstance", "ptmxmode=0666", "mode=0620", "gid=5")));
        MountPlan.Prepared cgroup = MountPlan.prepare(new OciMount(
                "/sys/fs/cgroup", "cgroup", "cgroup", List.of("ro", "nosuid", "noexec", "nodev", "relatime")));

        assertEquals(MountPlan.NOSUID | MountPlan.STRICTATIME, dev.flags());
        assertEquals("mode=755,size=65536k", dev.data());
        assertEquals(MountPlan.NOSUID | MountPlan.NOEXEC, pts.flags());
        assertEquals("newinstance,ptmxmode=0666,mode=0620,gid=5", pts.data());
        assertEquals("cgroup2", cgroup.fstype());
        assertEquals("cgroup2", cgroup.source());
        assertEquals(MountPlan.RDONLY | MountPlan.NOSUID | MountPlan.NOEXEC | MountPlan.NODEV | MountPlan.RELATIME, cgroup.flags());
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


    @Test
    void readsReadonlyPaths() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"},
                  "linux": {"readonlyPaths": ["/proc/sys", "/proc/sysrq-trigger"]}
                }
                """);

        assertEquals(List.of("/proc/sys", "/proc/sysrq-trigger"), OciConfig.read(bundle).readonlyPaths());
    }


    @Test
    void readsADeviceAndAllowsOnlyAnUnrestrictedPolicy() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"},
                  "linux": {
                    "devices": [{"path": "/dev/zero", "type": "c", "major": 1, "minor": 5, "fileMode": 438}],
                    "resources": {"devices": [{"allow": true, "type": "a", "access": "rwm"}]}
                  }
                }
                """);
        OciConfig config = OciConfig.read(bundle);
        assertEquals("/dev/zero", config.devices().getFirst().path());
        DevicePolicy.requireEnforceable(config.deviceRules());
    }

    @Test
    void rejectsANarrowDevicePolicy() {
        assertThrows(IllegalArgumentException.class, () -> DevicePolicy.requireEnforceable(List.of(
                new DeviceRule(false, "a", "rwm", true))));
    }

    @Test
    void encodesASeccompErrnoRule() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"},
                  "linux": {"seccomp": {
                    "defaultAction": "SCMP_ACT_ALLOW",
                    "syscalls": [{"names": ["getcwd"], "action": "SCMP_ACT_ERRNO", "errnoRet": 13}]
                  }}
                }
                """);
        SeccompProfile profile = OciConfig.read(bundle).seccomp();
        assertEquals(0x7fff0000, profile.defaultAction());
        assertEquals(0x00050000 | 13, profile.syscalls().getFirst().action());
    }

    @Test
    void createsTheDefaultDevicesWhenNoneAreConfigured() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"}
                }
                """);
        OciConfig config = OciConfig.read(bundle);
        assertEquals("/dev/null", config.devices().getFirst().path());
        assertEquals("/dev/ptmx", config.devices().getLast().path());
    }

    @Test
    void readsAnnotations() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"args": ["/bin/true"]},
                  "root": {"path": "rootfs"},
                  "annotations": {"org.opencontainers.runtime-tools": "hook stdin test"}
                }
                """);
        assertEquals("hook stdin test", OciConfig.read(bundle).annotations().get("org.opencontainers.runtime-tools"));
    }

    @Test
    void rejectsAnUnknownRuntimeSpecVersion() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {"ociVersion":"invalid","process":{"args":["/bin/true"]},"root":{"path":"rootfs"}}
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> OciConfig.read(bundle));
        assertTrue(failure.getMessage().contains("ociVersion"));
    }

    @Test
    void rejectsAnUnknownRlimit() throws Exception {
        Files.writeString(bundle.resolve("config.json"), """
                {"process":{"args":["/bin/true"],"rlimits":[{"type":"RLIMIT_TEST","soft":1,"hard":1}]},"root":{"path":"rootfs"}}
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> OciConfig.read(bundle));
        assertTrue(failure.getMessage().contains("RLIMIT_TEST"));
    }

    private static java.util.List<String> ListOf(String... values) {
        return java.util.List.of(values);
    }
}
