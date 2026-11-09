package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.huahill.coaming.json.Json;
import java.nio.file.Files;
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
        Resources.apply(cgroup, Json.parse("""
                {"memory":{"limit":-1},"cpu":{"shares":1024,"quota":200000,"period":100000}}
                """));

        assertEquals("max", Files.readString(cgroup.resolve("memory.max")).trim());
        assertEquals("39", Files.readString(cgroup.resolve("cpu.weight")).trim());
        assertEquals("200000 100000", Files.readString(cgroup.resolve("cpu.max")).trim());
    }
}
