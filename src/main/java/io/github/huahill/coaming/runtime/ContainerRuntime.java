package io.github.huahill.coaming.runtime;

import io.github.huahill.coaming.json.Json;
import io.github.huahill.coaming.oci.MountPlan;
import io.github.huahill.coaming.oci.NamespacePlan;
import io.github.huahill.coaming.oci.OciMount;
import io.github.huahill.coaming.oci.OciConfig;
import io.github.huahill.coaming.oci.OciProcess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ContainerRuntime {
    private ContainerRuntime() {}

    public static int create(Path root, Path bundle, String id, Path pidFile) throws Exception {
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
        String program = Memfd.copyCurrentExecutable();
        List<String> command = new ArrayList<>();
        command.add(program);
        command.add("init");
        command.add("--state");
        command.add(state.toString());
        command.add("--bundle");
        command.add(bundle.toAbsolutePath().toString());
        command.addAll(NamespacePlan.arguments(config.namespaces(), config.namespacesSpecified()));
        Process helper = new ProcessBuilder(command).inheritIO().start();
        int helperExit = helper.waitFor();
        if (helperExit != 0) {
            Files.writeString(state.resolve("status"), "failed");
            throw new IllegalStateException("init helper exited " + helperExit);
        }
        if (pidFile != null) {
            if (pidFile.getParent() != null) {
                Files.createDirectories(pidFile.getParent());
            }
            Files.copy(state.resolve("pid"), pidFile, StandardCopyOption.REPLACE_EXISTING);
        }
        int pid = Integer.parseInt(Files.readString(state.resolve("pid")).trim());
        Cgroups.add(cgroup, pid);
        waitUntil(state, "created", "failed");
        return 0;
    }

    public static int init(Path state, Path bundle) throws Exception {
        OciConfig config = OciConfig.read(bundle);
        recordExecutable(state);
        int startFd = LinuxCalls.openStartFifo(state.resolve("start.fifo").toString());
        Files.writeString(state.resolve("status"), "created");
        LinuxCalls.readStartByte(startFd);
        Files.writeString(state.resolve("status"), "running");
        Path rootfs = config.root(bundle).toAbsolutePath();
        LinuxCalls.bindPrivate(rootfs.toString());
        boolean mountsProc = false;
        for (OciMount mount : config.mounts()) {
            if ("/proc".equals(mount.destination())) {
                mountsProc = true;
            }
            mountOne(rootfs, mount);
        }
        Path oldRoot = rootfs.resolve("oldroot");
        Files.createDirectories(oldRoot);
        LinuxCalls.pivotRoot(rootfs.toString(), oldRoot.toString());
        LinuxCalls.chdir("/");
        LinuxCalls.umountDetach("/oldroot");
        if (!mountsProc) {
            LinuxCalls.mountProc("/proc");
        }
        for (String masked : config.maskedPaths()) {
            LinuxCalls.mask(masked);
        }
        LinuxCalls.hostname(config.hostname());
        if (!"/".equals(config.cwd())) {
            LinuxCalls.chdir(config.cwd());
        }
        String[] args = config.args().toArray(String[]::new);
        if (args.length == 0) {
            throw new IllegalStateException("container has no process args");
        }
        args[0] = resolveProgram(args[0], config.env());
        LinuxCalls.exec(args, config.env().toArray(String[]::new));
        return 1;
    }


    private static void mountOne(Path rootfs, OciMount mount) throws IOException {
        MountPlan.Prepared prepared = MountPlan.prepare(mount);
        Path dest = rootfs.resolve(mount.destination().substring(1));
        boolean directory = prepared.fstype() != null || Files.isDirectory(Path.of(prepared.source()));
        if (directory) {
            Files.createDirectories(dest);
        } else if (!Files.exists(dest)) {
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            Files.createFile(dest);
        }
        LinuxCalls.mountPrepared(prepared.source(), dest.toString(), prepared.fstype(), prepared.flags(), prepared.remount(), prepared.propagation(), prepared.data());
    }

    public static int exec(Path root, String id, Path process, Path pidFile, boolean detach) throws Exception {
        requireLinux();
        int target = Integer.parseInt(Files.readString(requireContainer(root, id).resolve("pid")).trim());
        OciProcess spec = OciProcess.parse(Files.readString(process));
        if (spec.args().isEmpty()) {
            throw new IllegalArgumentException("exec process has no args");
        }
        List<String> command = new ArrayList<>();
        command.add(Memfd.copyCurrentExecutable());
        command.add("exec-init");
        command.add("--pid");
        command.add(Integer.toString(target));
        command.add("--pid-path");
        command.add(pidFile.toAbsolutePath().toString());
        command.add("--cwd");
        command.add(spec.cwd());
        for (String variable : spec.env()) {
            command.add("--env");
            command.add(variable);
        }
        if (detach) {
            command.add("--detach");
        }
        command.add("--");
        command.addAll(spec.args());
        Process helper = new ProcessBuilder(command).inheritIO().start();
        return helper.waitFor();
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
        if (linuxHost() && (status.equals("running") || status.equals("created")) && !pid.equals("0")
                && !Files.exists(Path.of("/proc").resolve(pid))) {
            status = "stopped";
            Files.writeString(state.resolve("status"), status);
        }
        String bundle = text(state.resolve("bundle"));
        String rootfs = "";
        if (!bundle.isEmpty()) {
            try {
                rootfs = OciConfig.read(Path.of(bundle)).root(Path.of(bundle)).toString();
            } catch (RuntimeException | IOException ignored) {
                rootfs = "";
            }
        }
        System.out.printf(
                "{\"ociVersion\":\"1.2.0\",\"id\":\"%s\",\"status\":\"%s\",\"pid\":%s,\"bundle\":\"%s\",\"rootfs\":\"%s\"}%n",
                escape(id), escape(status), pid, escape(bundle), escape(rootfs));
        return 0;
    }

    public static int kill(Path root, String id, String signal) throws IOException {
        requireLinux();
        int pid = Integer.parseInt(Files.readString(requireContainer(root, id).resolve("pid")).trim());
        LinuxCalls.kill(pid, signalNumber(signal));
        return 0;
    }

    public static int delete(Path root, String id, boolean force) throws IOException {
        Path state = requireContainer(root, id);
        String status = Files.readString(state.resolve("status")).trim();
        if (!force && "running".equals(status) && processAlive(state)) {
            throw new IllegalStateException("container is running: " + id);
        }
        if (force || "created".equals(status) || "running".equals(status)) {
            signalCreatedOrForced(root, id, state);
        }
        if (Files.exists(state.resolve("cgroup"))) {
            Cgroups.remove(Files.readString(state.resolve("cgroup")).trim());
        }
        deleteTree(state);
        return 0;
    }

    public static int update(Path root, String id, String resources, InputStream in) throws IOException {
        Path state = requireContainer(root, id);
        String json = "-".equals(resources)
                ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(resources));
        if (!Files.exists(state.resolve("cgroup"))) {
            throw new IllegalStateException("container has no cgroup: " + id);
        }
        Path cgroup = Path.of(Files.readString(state.resolve("cgroup")).trim());
        Resources.apply(cgroup, Json.parse(json));
        return 0;
    }

    public static int ps(Path root, String id) throws IOException {
        Path state = requireContainer(root, id);
        String status = Files.readString(state.resolve("status")).trim();
        int pid = Files.exists(state.resolve("pid"))
                ? Integer.parseInt(Files.readString(state.resolve("pid")).trim())
                : 0;
        boolean show = pid > 0 && ("running".equals(status) || "created".equals(status)) && alive(pid);
        System.out.println(show ? "[" + pid + "]" : "[]");
        return 0;
    }

    public static int events(Path root, String id, String interval, boolean stats) throws IOException, InterruptedException {
        requireContainer(root, id);
        if (stats) {
            System.out.println(statsLine(id));
            return 0;
        }
        long pause = intervalMillis(interval);
        while (containerAlive(root, id)) {
            System.out.println(statsLine(id));
            System.out.flush();
            Thread.sleep(pause);
        }
        return 0;
    }

    static String resolveProgram(String program, List<String> env) {
        if (program.contains("/")) {
            return program;
        }
        String path = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
        for (String item : env) {
            if (item.startsWith("PATH=")) {
                path = item.substring("PATH=".length());
            }
        }
        for (String dir : path.split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, program);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return program;
    }

    static long intervalMillis(String value) {
        if (value == null || value.isBlank()) {
            return 5000;
        }
        String text = value.trim();
        if (text.endsWith("ms")) {
            return Long.parseLong(text.substring(0, text.length() - 2));
        }
        if (text.endsWith("s")) {
            return Long.parseLong(text.substring(0, text.length() - 1)) * 1000L;
        }
        if (text.endsWith("m")) {
            return Long.parseLong(text.substring(0, text.length() - 1)) * 60_000L;
        }
        return Long.parseLong(text) * 1000L;
    }

    private static void signalCreatedOrForced(Path root, String id, Path state) {
        if (!linuxHost() || !Files.exists(state.resolve("pid"))) {
            return;
        }
        try {
            kill(root, id, "KILL");
        } catch (RuntimeException | IOException ignored) {
            // The process may already have exited.
        }
    }

    private static boolean processAlive(Path state) throws IOException {
        if (!Files.exists(state.resolve("pid"))) {
            return true;
        }
        return alive(Integer.parseInt(Files.readString(state.resolve("pid")).trim()));
    }

    private static boolean containerAlive(Path root, String id) throws IOException {
        Path state = requireContainer(root, id);
        String status = Files.readString(state.resolve("status")).trim();
        return ("running".equals(status) || "created".equals(status)) && processAlive(state);
    }

    private static boolean alive(int pid) {
        if (pid <= 0) {
            return false;
        }
        if (!linuxHost()) {
            return true;
        }
        return Files.exists(Path.of("/proc").resolve(Integer.toString(pid)));
    }

    private static String statsLine(String id) {
        return "{\"type\":\"stats\",\"id\":\"" + escape(id) + "\",\"data\":{}}";
    }

    private static void recordExecutable(Path state) throws IOException {
        Path self = Path.of("/proc/self/exe");
        if (Files.exists(self)) {
            Files.writeString(state.resolve("exe"), Files.readSymbolicLink(self).toString());
        }
    }

    private static String text(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path).trim() : "";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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

    private static int signalNumber(String signal) {
        return switch (signal.toUpperCase(Locale.ROOT)) {
            case "TERM", "SIGTERM", "15" -> 15;
            case "KILL", "SIGKILL", "9" -> 9;
            default -> Integer.parseInt(signal);
        };
    }

    private static void requireLinux() {
        if (!linuxHost()) {
            throw new IllegalStateException("container commands require Linux");
        }
    }

    private static boolean linuxHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
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
