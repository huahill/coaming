package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.huahill.coaming.CoamingMain;
import io.github.huahill.coaming.oci.OciConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShimContractTest {
    @TempDir
    Path temp;

    @Test
    void parsesTheArgumentsTheShimPasses() {
        CommandLine line = CommandLine.parse(new String[] {
                "--root", "/run/coaming",
                "--log", "/tmp/coaming.log",
                "--log-format", "json",
                "--systemd-cgroup",
                "create", "--bundle", "/bundle", "--pid-file", "/pid", "demo"
        });

        assertEquals(Path.of("/run/coaming"), line.root());
        assertEquals(Path.of("/tmp/coaming.log"), line.log());
        assertEquals("json", line.logFormat());
        assertTrue(line.systemdCgroup());
        assertEquals("create", line.command());
        List<String> args = new ArrayList<>(line.arguments());
        assertEquals("/bundle", CommandLine.takeOption(args, "--bundle", "-b"));
        assertEquals("/pid", CommandLine.takeOption(args, "--pid-file"));
        assertEquals("demo", args.getFirst());
    }

    @Test
    void parsesEqualsFormOptions() {
        CommandLine line = CommandLine.parse(new String[] {
                "--root=/run/coaming",
                "--log=/tmp/coaming.log",
                "--log-format=json",
                "--rootless=true",
                "--debug",
                "events",
                "--interval=5s",
                "--resources=-",
                "demo"
        });

        assertEquals(Path.of("/run/coaming"), line.root());
        assertEquals(Path.of("/tmp/coaming.log"), line.log());
        assertEquals("json", line.logFormat());
        List<String> args = new ArrayList<>(line.arguments());
        assertEquals("5s", CommandLine.takeOption(args, "--interval"));
        assertEquals("-", CommandLine.takeOption(args, "--resources"));
        assertEquals("demo", args.getFirst());
    }

    @Test
    void featuresDoNotClaimUnsupportedCgroupDrivers() {
        String json = Features.json();
        assertTrue(json.contains("\"v2\":true"));
        assertTrue(json.contains("\"v1\":false"));
        assertTrue(json.contains("\"systemd\":false"));
        assertTrue(json.contains("\"systemdUser\":false"));
        assertFalse(json.contains("\"idmap\""));
    }

    @Test
    void featuresCommandPrintsThatDocument() throws Exception {
        String output = capture(() -> CoamingMain.run(new String[] {"features"}));
        assertTrue(output.contains("\"v2\":true"));
        assertFalse(output.contains("\"idmap\""));
    }

    @Test
    void failedCommandIsAppendedAsJson() throws Exception {
        Path log = temp.resolve("coaming.log");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CoamingMain.run(new String[] {"--log", log.toString(), "--log-format", "json", "nope"}));

        String text = Files.readString(log);
        assertTrue(text.contains("\"level\":\"error\""));
        assertTrue(text.contains(failure.getMessage()));
    }

    @Test
    void stateIncludesTheBundle() throws Exception {
        Path root = temp.resolve("root");
        Path state = root.resolve("demo");
        Files.createDirectories(state);
        Files.writeString(state.resolve("status"), "stopped");
        Files.writeString(state.resolve("pid"), "7");
        Files.writeString(state.resolve("bundle"), "/bundles/demo");

        String output = capture(() -> ContainerRuntime.state(root, "demo"));

        assertTrue(output.contains("\"id\":\"demo\""));
        assertTrue(output.contains("\"status\":\"stopped\""));
        assertTrue(output.contains("\"pid\":7"));
        assertTrue(output.contains("\"bundle\":\"/bundles/demo\""));
    }

    @Test
    void deleteRemovesAStoppedContainer() throws Exception {
        Path root = temp.resolve("root");
        Path state = root.resolve("demo");
        Files.createDirectories(state);
        Files.writeString(state.resolve("status"), "stopped");

        ContainerRuntime.delete(root, "demo", false);

        assertFalse(Files.exists(state));
    }

    @Test
    void deleteRefusesARunningContainerUnlessForced() throws Exception {
        Path root = temp.resolve("root");
        Path state = root.resolve("demo");
        Files.createDirectories(state);
        Files.writeString(state.resolve("status"), "running");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ContainerRuntime.delete(root, "demo", false));

        assertTrue(failure.getMessage().contains("running"));
        assertTrue(Files.isDirectory(state));
    }

    @Test
    void updateReadsResourcesFromStdin() throws Exception {
        Path root = temp.resolve("root");
        Path group = temp.resolve("cgroup");
        Path state = root.resolve("demo");
        Files.createDirectories(state);
        Files.createDirectories(group);
        Files.writeString(state.resolve("status"), "running");
        Files.writeString(state.resolve("cgroup"), group.toString());
        byte[] body = "{\"memory\":{\"limit\":4096}}".getBytes(StandardCharsets.UTF_8);

        ContainerRuntime.update(root, "demo", "-", new ByteArrayInputStream(body));

        assertEquals("4096", Files.readString(group.resolve("memory.max")).trim());
    }

    @Test
    void psPrintsTheInitPid() throws Exception {
        Path root = temp.resolve("root");
        Path state = root.resolve("demo");
        Files.createDirectories(state);
        Files.writeString(state.resolve("status"), "running");
        Files.writeString(state.resolve("pid"), "42");

        String output = capture(() -> ContainerRuntime.ps(root, "demo"));

        assertEquals("[42]", output.trim());
    }

    @Test
    void statsIsOneShotAndAStoppedContainerDoesNotBlock() throws Exception {
        Path root = temp.resolve("root");
        Path state = root.resolve("demo");
        Files.createDirectories(state);
        Files.writeString(state.resolve("status"), "stopped");
        Files.writeString(state.resolve("pid"), "42");

        String stats = capture(() -> ContainerRuntime.events(root, "demo", "5s", true));
        String done = capture(() -> ContainerRuntime.events(root, "demo", "5s", false));

        assertTrue(stats.contains("\"type\":\"stats\""));
        assertTrue(stats.contains("\"id\":\"demo\""));
        assertEquals("", done.trim());
    }

    @Test
    void readsProcessEnvironment() throws Exception {
        Path bundle = temp.resolve("bundle");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("config.json"), """
                {
                  "process": {"cwd": "/", "args": ["/bin/echo"], "env": ["PATH=/bin", "HOME=/root"]},
                  "root": {"path": "rootfs"}
                }
                """);

        OciConfig config = OciConfig.read(bundle);

        assertEquals(List.of("PATH=/bin", "HOME=/root"), config.env());
    }

    private static String capture(Command command) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            command.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Command {
        void run() throws Exception;
    }
}
