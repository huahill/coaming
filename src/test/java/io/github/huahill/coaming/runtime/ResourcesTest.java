package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.huahill.coaming.json.Json;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourcesTest {
    @TempDir
    Path cgroup;

    @Test
    void enablesOnlyMissingControllers() {
        assertEquals("+cpu +memory +pids", Cgroups.enableLine("cpuset cpu io memory hugetlb pids", ""));
        assertEquals("+memory", Cgroups.enableLine("cpu memory", "cpu"));
        assertEquals("", Cgroups.enableLine("cpuset cpu memory", "cpu memory"));
    }

    @Test
    void rootlessUsesTheDelegatedCgroup() {
        assertEquals(Path.of("/sys/fs/cgroup"), Cgroups.parent(0, "0::/user.slice/user-1000.slice\n"));
        assertEquals(Path.of("/sys/fs/cgroup/user.slice/user-1000.slice/user@1000.service"),
                Cgroups.parent(1000, "0::/user.slice/user-1000.slice/user@1000.service\n"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Cgroups.parent(1000, "0::/\n"));
        assertTrue(failure.getMessage().contains("delegated"));
        assertEquals(1000, Cgroups.euid("Name:\tdemo\nUid:\t1000\t1000\t1000\t1000\n"));
    }


    @Test
    void resolvesAProgramFromPath(@TempDir Path bin) throws Exception {
        Path program = bin.resolve("echo");
        Files.writeString(program, "");
        program.toFile().setExecutable(true);

        assertEquals(program.toString(), ContainerRuntime.resolveProgram("echo", java.util.List.of("PATH=" + bin)));
        assertEquals("/bin/echo", ContainerRuntime.resolveProgram("/bin/echo", java.util.List.of()));
    }

    @Test
    void convertsSharesToCgroupV2Weight() {
        assertEquals(1, Resources.weightFromShares(2));
        assertEquals(39, Resources.weightFromShares(1024));
        assertEquals(10000, Resources.weightFromShares(262144));
    }

    @Test
    void writesMemoryAndCpuFiles() throws Exception {
        Files.writeString(cgroup.resolve("memory.max"), "");
        Files.writeString(cgroup.resolve("cpu.weight"), "");
        Files.writeString(cgroup.resolve("cpu.max"), "");

        Resources.apply(cgroup, Json.parse("""
                {"memory":{"limit":-1},"cpu":{"shares":1024,"quota":200000,"period":100000}}
                """));

        assertEquals("max", Files.readString(cgroup.resolve("memory.max")).trim());
        assertEquals("39", Files.readString(cgroup.resolve("cpu.weight")).trim());
        assertEquals("200000 100000", Files.readString(cgroup.resolve("cpu.max")).trim());
    }

    @Test
    void writesAPidsLimit() throws Exception {
        Files.writeString(cgroup.resolve("pids.max"), "");

        Resources.apply(cgroup, Json.parse("""
                {"pids":{"limit":64}}
                """));

        assertEquals("64", Files.readString(cgroup.resolve("pids.max")).trim());
    }

    @Test
    void doesNotCreateAMissingControllerFile() {
        assertThrows(NoSuchFileException.class, () -> Resources.apply(cgroup, Json.parse("""
                {"pids":{"limit":64}}
                """)));
    }

    @Test
    void letsUnifiedOverrideTheStructuredValue() throws Exception {
        Files.writeString(cgroup.resolve("memory.max"), "");
        Files.writeString(cgroup.resolve("memory.high"), "");

        Resources.apply(cgroup, Json.parse("""
                {"memory":{"limit":4096},"unified":{"memory.max":"8192","memory.high":"2048"}}
                """));

        assertEquals("8192", Files.readString(cgroup.resolve("memory.max")).trim());
        assertEquals("2048", Files.readString(cgroup.resolve("memory.high")).trim());
    }

    @Test
    void rejectsAUnifiedKeyOutsideTheCgroup() {
        assertThrows(IllegalArgumentException.class, () -> Resources.apply(cgroup, Json.parse("""
                {"unified":{"../memory.max":"1"}}
                """)));
    }
}
