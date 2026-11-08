package io.github.huahill.coaming.runtime;

import io.github.huahill.coaming.oci.OciConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ContainerRuntime {
    private ContainerRuntime() {}

    public static int create(Path root, Path bundle, String id) throws Exception {
        requireLinux();
        Path state = containerDir(root, id);
        if (Files.exists(state)) {
            throw new IllegalStateException("container already exists: " + id);
        }
        OciConfig config = OciConfig.read(bundle);
        Files.createDirectories(state);
        Files.writeString(state.resolve("bundle"), bundle.toAbsolutePath().toString());
        Files.writeString(state.resolve("status"), "creating");
        LinuxCalls.mkfifo(state.resolve("start.fifo").toString());
        String cgroup = Cgroups.create(id);
        Files.writeString(state.resolve("cgroup"), cgroup);
        Process helper = new ProcessBuilder(
                        executable(), "init", "--state", state.toString(), "--bundle", bundle.toAbsolutePath().toString())
                .inheritIO()
                .start();
        int helperExit = helper.waitFor();
        if (helperExit != 0) {
            Files.writeString(state.resolve("status"), "failed");
            throw new IllegalStateException("init helper exited " + helperExit);
        }
        int pid = Integer.parseInt(Files.readString(state.resolve("pid")).trim());
        Cgroups.add(cgroup, pid);
        waitUntil(state, "created", "failed");
        return 0;
    }

    public static int init(Path state, Path bundle) throws Exception {
        OciConfig config = OciConfig.read(bundle);
        int startFd = LinuxCalls.openStartFifo(state.resolve("start.fifo").toString());
        Files.writeString(state.resolve("status"), "created");
        LinuxCalls.readStartByte(startFd);
        Files.writeString(state.resolve("status"), "running");
        Path rootfs = config.root(bundle).toAbsolutePath();
        LinuxCalls.bindPrivate(rootfs.toString());
        Path oldRoot = rootfs.resolve("oldroot");
        Files.createDirectories(oldRoot);
        LinuxCalls.pivotRoot(rootfs.toString(), oldRoot.toString());
        LinuxCalls.chdir("/");
        LinuxCalls.umountDetach("/oldroot");
        LinuxCalls.mountProc("/proc");
        LinuxCalls.hostname(config.hostname());
        if (!"/".equals(config.cwd())) {
            LinuxCalls.chdir(config.cwd());
        }
        LinuxCalls.exec(config.args().toArray(String[]::new));
        return 1;
    }

    public static int start(Path root, String id) {
        requireLinux();
        Path state = requireContainer(root, id);
        LinuxCalls.signalStart(state.resolve("start.fifo").toString());
        return 0;
    }

    public static int state(Path root, String id) throws IOException {
        Path state = requireContainer(root, id);
        String status = Files.readString(state.resolve("status")).trim();
        String pid = Files.exists(state.resolve("pid")) ? Files.readString(state.resolve("pid")).trim() : "0";
        if ((status.equals("running") || status.equals("created")) && !pid.equals("0")
                && !Files.exists(Path.of("/proc").resolve(pid))) {
            status = "stopped";
            Files.writeString(state.resolve("status"), status);
        }
        System.out.printf("{\"ociVersion\":\"1.2.0\",\"id\":\"%s\",\"status\":\"%s\",\"pid\":%s}%n", id, status, pid);
        return 0;
    }

    public static int kill(Path root, String id, String signal) throws IOException {
        requireLinux();
        int pid = Integer.parseInt(Files.readString(requireContainer(root, id).resolve("pid")).trim());
        LinuxCalls.kill(pid, signalNumber(signal));
        return 0;
    }

    public static int delete(Path root, String id) throws IOException {
        Path state = requireContainer(root, id);
        String status = Files.readString(state.resolve("status")).trim();
        if (!status.equals("stopped") && !status.equals("failed") && !status.equals("created")) {
            if (Files.exists(state.resolve("pid"))) {
                try {
                    kill(root, id, "KILL");
                } catch (RuntimeException ignored) {
                    // The process may already have exited.
                }
            }
        }
        if (Files.exists(state.resolve("cgroup"))) {
            Cgroups.remove(Files.readString(state.resolve("cgroup")).trim());
        }
        deleteTree(state);
        return 0;
    }

    private static void waitUntil(Path state, String wanted, String failed) throws IOException, InterruptedException {
        for (int i = 0; i < 100; i++) {
            String status = Files.readString(state.resolve("status")).trim();
            if (wanted.equals(status)) {
                return;
            }
            if (failed.equals(status)) {
                throw new IllegalStateException("container init failed");
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("timed out waiting for container creation");
    }

    private static Path containerDir(Path root, String id) {
        if (id.isBlank() || id.contains("/") || id.contains("..")) {
            throw new IllegalArgumentException("invalid container id");
        }
        return root.resolve(id);
    }

    private static Path requireContainer(Path root, String id) {
        Path state = containerDir(root, id);
        if (!Files.isDirectory(state)) {
            throw new IllegalArgumentException("container not found: " + id);
        }
        return state;
    }

    private static String executable() {
        return ProcessHandle.current().info().command().orElseThrow(() -> new IllegalStateException("cannot find own executable"));
    }

    private static int signalNumber(String signal) {
        return switch (signal.toUpperCase(Locale.ROOT)) {
            case "TERM", "SIGTERM", "15" -> 15;
            case "KILL", "SIGKILL", "9" -> 9;
            default -> Integer.parseInt(signal);
        };
    }

    private static void requireLinux() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IllegalStateException("container commands require Linux");
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }
}
