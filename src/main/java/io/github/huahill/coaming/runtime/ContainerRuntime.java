package io.github.huahill.coaming.runtime;

import io.github.huahill.coaming.json.Json;
import io.github.huahill.coaming.oci.DeviceNode;
import io.github.huahill.coaming.oci.DevicePolicy;
import io.github.huahill.coaming.oci.SystemdDevices;
import io.github.huahill.coaming.oci.MountPlan;
import io.github.huahill.coaming.oci.NamespacePlan;
import io.github.huahill.coaming.oci.OciMount;
import io.github.huahill.coaming.oci.OciConfig;
import io.github.huahill.coaming.oci.OciHook;
import io.github.huahill.coaming.oci.OciProcess;
import io.github.huahill.coaming.oci.Rlimit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import io.github.huahill.coaming.oci.BindFds;
import io.github.huahill.coaming.oci.IdMap;
import io.github.huahill.coaming.oci.IdmapPlan;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContainerRuntime {
    private ContainerRuntime() {}

    public static int create(Path root, Path bundle, String id, Path pidFile, boolean systemd) throws Exception {
        requireLinux();
        Path state = containerDir(root, id);
        if (Files.exists(state)) {
            throw new IllegalStateException("container already exists: " + id);
        }
        OciConfig config = OciConfig.read(bundle);
        SystemdDevices.Program devices = null;
        if (systemd) {
            devices = SystemdDevices.translate(config.deviceRules());
        } else {
            DevicePolicy.requireEnforceable(config.deviceRules());
        }
        Files.createDirectories(state);
        Files.writeString(state.resolve("bundle"), bundle.toAbsolutePath().toString());
        Files.writeString(state.resolve("status"), "creating");
        LinuxCalls.mkfifo(state.resolve("start.fifo").toString());
        LinuxCalls.mkfifo(state.resolve("exec.fifo").toString());
        long hostUid = IdMap.hostIdFor(config.uidMappings(), 0);
        long hostGid = IdMap.hostIdFor(config.gidMappings(), 0);
        String cgroup = null;
        if (!systemd) {
            cgroup = Cgroups.create(id);
            Files.writeString(state.resolve("cgroup"), cgroup);
        }
        String program = executableFor(state, hostUid, hostGid);
        List<String> command = new ArrayList<>();
        command.add(program);
        command.add("init");
        command.add("--state");
        command.add(state.toString());
        command.add("--bundle");
        command.add(bundle.toAbsolutePath().toString());
        command.addAll(NamespacePlan.arguments(config.namespaces(), config.namespacesSpecified(), config.uidMappings(), config.gidMappings()));
        command.addAll(IdmapPlan.arguments(config.mounts(), config.uidMappings(), config.gidMappings(), createsUserNamespace(config)));
        Process helper = new ProcessBuilder(command).inheritIO().start();
        int helperExit = helper.waitFor();
        if (helperExit != 0) {
            Files.writeString(state.resolve("status"), "failed");
            String reason = switch (helperExit) {
                case 78 -> "idmap mount failed";
                case 79 -> "rootless id mapping failed";
                default -> "init helper exited " + helperExit;
            };
            throw new IllegalStateException(reason);
        }
        if (pidFile != null) {
            if (pidFile.getParent() != null) {
                Files.createDirectories(pidFile.getParent());
            }
            Files.copy(state.resolve("pid"), pidFile, StandardCopyOption.REPLACE_EXISTING);
        }
        int pid = Integer.parseInt(Files.readString(state.resolve("pid")).trim());
        if (systemd) {
            SystemdCgroup.Scope scope = SystemdCgroup.scope(config.cgroupsPath(), id);
            SystemdBus.startScope(scope.unit(), scope.slice(), pid, devices);
            cgroup = "/sys/fs/cgroup" + scope.filesystemPath();
            Files.writeString(state.resolve("cgroup"), cgroup);
            Files.writeString(state.resolve("systemd-unit"), scope.unit());
        } else {
            Cgroups.add(cgroup, pid);
        }
        if (config.resources() != null) {
            applyResources(Path.of(cgroup), config.resources());
        }
        waitUntil(state, "created", "failed");
        Map<String, String> created = hookState(config, id, "created", Integer.toString(pid), bundle.toAbsolutePath().toString());
        runHooks(config, "createRuntime", created);
        runHooks(config, "prestart", created);
        return 0;
    }

    public static int init(Path state, Path bundle) throws Exception {
        OciConfig config = OciConfig.read(bundle);
        String hookId = state.getFileName().toString();
        String hookPid = Files.exists(state.resolve("pid")) ? Files.readString(state.resolve("pid")).trim() : "0";
        String hookBundle = bundle.toAbsolutePath().toString();
        recordExecutable(state);
        int startFd = LinuxCalls.openStartFifo(state.resolve("start.fifo").toString());
        Files.writeString(state.resolve("status"), "created");
        LinuxCalls.readStartByte(startFd);
        int execFd = LinuxCalls.openExecFifo(state.resolve("exec.fifo").toString());
        Path rootfs = config.root(bundle).toAbsolutePath();
        LinuxCalls.bindPrivate(rootfs.toString());
        boolean mountsProc = false;
        java.util.Map<String, Integer> idmaps = IdmapPlan.descriptors(System::getenv);
        java.util.Map<String, Integer> binds = BindFds.descriptors(System::getenv);
        try {
            for (OciMount mount : config.mounts()) {
                if ("/proc".equals(mount.destination())) {
                    mountsProc = true;
                }
                mountOne(rootfs, mount, idmaps, binds);
            }
        } finally {
            for (int descriptor : binds.values()) {
                LinuxCalls.closeFd(descriptor);
            }
        }
        Path oldRoot = rootfs.resolve("oldroot");
        Files.createDirectories(oldRoot);
        LinuxCalls.pivotRoot(rootfs.toString(), oldRoot.toString());
        LinuxCalls.chdir("/");
        LinuxCalls.umountDetach("/oldroot");
        if (!mountsProc) {
            LinuxCalls.mountProc("/proc");
        }
        for (DeviceNode device : config.devices()) {
            LinuxCalls.createDevice(device.path(), device.type(), device.major(), device.minor(), device.fileMode());
        }
        defaultDeviceSymlinks();
        for (String masked : config.maskedPaths()) {
            LinuxCalls.mask(masked);
        }
        for (String readonly : config.readonlyPaths()) {
            LinuxCalls.readonly(readonly);
        }
        LinuxCalls.hostname(config.hostname());
        if (!"/".equals(config.cwd())) {
            LinuxCalls.chdir(config.cwd());
        }
        runHooks(config, "createContainer", hookState(config, hookId, "running", hookPid, hookBundle));
        String[] args = config.args().toArray(String[]::new);
        if (args.length == 0) {
            throw new IllegalStateException("container has no process args");
        }
        args[0] = resolveProgram(args[0], config.env());
        if (config.apparmorProfile() != null) {
            LinuxCalls.setApparmor(config.apparmorProfile());
        }
        if (config.capabilities() != null) {
            LinuxCalls.dropBounding(config.capabilities());
        }
        LinuxCalls.applyIdentity(config.user(), config.rlimits(), config.oomScoreAdj(), config.capabilities() != null);
        if (config.capabilities() != null) {
            LinuxCalls.commitCapabilities(config.capabilities());
        }
        if (config.noNewPrivileges()) {
            LinuxCalls.noNewPrivileges();
        }
        if (config.seccomp() != null) {
            LinuxCalls.installSeccomp(config.seccomp());
        }
        runHooks(config, "startContainer", hookState(config, hookId, "running", hookPid, hookBundle));
        try {
            LinuxCalls.exec(args, config.env().toArray(String[]::new));
        } catch (RuntimeException failure) {
            if (execFd >= 0) {
                LinuxCalls.signalExecFailed(execFd);
            }
            throw failure;
        }
        return 1;
    }


    private static String executableFor(Path state, long hostUid, long hostGid) throws Exception {
        if (hostUid < 0 || hostGid < 0) {
            return Memfd.copyCurrentExecutable();
        }
        if (hostUid == Cgroups.euid(Files.readString(Path.of("/proc/self/status")))) {
            return Path.of("/proc/self/exe").toRealPath().toString();
        }
        Path copy = state.resolve("coaming");
        Path copyDirectory = ExecLocation.directory(state, Files.readString(Path.of("/proc/self/mountinfo")));
        Files.createDirectories(copyDirectory);
        copy = copyDirectory.resolve("coaming");
        Files.copy(Path.of("/proc/self/exe"), copy, StandardCopyOption.REPLACE_EXISTING);
        if (!copy.toFile().setExecutable(true, false)) {
            throw new IllegalStateException("runtime copy is not executable");
        }
        try (var paths = Files.walk(state)) {
            for (Path path : paths.toList()) {
                LinuxCalls.chown(path.toString(), hostUid, hostGid);
            }
        }
        if (!copyDirectory.equals(state)) {
            try (var paths = Files.walk(copyDirectory)) {
                for (Path path : paths.toList()) {
                    LinuxCalls.chown(path.toString(), hostUid, hostGid);
                }
            }
        }
        return copy.toString();
    }

    private static void applyResources(Path cgroup, io.github.huahill.coaming.json.Json resources) throws Exception {
        NoSuchFileException missing = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                Resources.apply(cgroup, resources);
                return;
            } catch (NoSuchFileException failure) {
                missing = failure;
                Thread.sleep(20);
            }
        }
        throw missing;
    }

    private static boolean createsUserNamespace(OciConfig config) {
        if (!config.namespacesSpecified()) {
            return false;
        }
        for (io.github.huahill.coaming.oci.Namespace namespace : config.namespaces()) {
            if ("user".equals(namespace.type()) && (namespace.path() == null || namespace.path().isBlank())) {
                return true;
            }
        }
        return false;
    }

    private static void mountOne(
            Path rootfs, OciMount mount, java.util.Map<String, Integer> idmaps, java.util.Map<String, Integer> binds)
            throws IOException {
        MountPlan.Prepared prepared = MountPlan.prepare(mount);
        String source = BindFds.source(mount.destination(), prepared.source(), binds);
        Path dest = rootfs.resolve(mount.destination().substring(1));
        boolean directory = prepared.fstype() != null || Files.isDirectory(Path.of(source));
        if (directory) {
            Files.createDirectories(dest);
        } else if (!Files.exists(dest)) {
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            Files.createFile(dest);
        }
        if (prepared.idmap() != MountPlan.Idmap.NONE) {
            Integer descriptor = idmaps.get(mount.destination());
            if (descriptor == null) {
                throw new IllegalStateException("idmap mount was not prepared: " + mount.destination());
            }
            LinuxCalls.moveMount(descriptor, dest.toString());
            return;
        }
        Integer preparedBind = binds.get(mount.destination());
        if (preparedBind != null) {
            LinuxCalls.moveMount(preparedBind, dest.toString());
            return;
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
        if (pidFile != null) {
            command.add("--pid-path");
            command.add(pidFile.toAbsolutePath().toString());
        }
        command.add("--cwd");
        command.add(spec.cwd());
        for (String variable : spec.env()) {
            command.add("--env");
            command.add(variable);
        }
        if (detach) {
            command.add("--detach");
        }
        if (spec.capabilities() != null) {
            command.add("--caps");
            command.add(spec.capabilities().encoded());
        }
        if (spec.noNewPrivileges()) {
            command.add("--no-new-privs");
        }
        if (spec.user() != null) {
            command.add("--user");
            command.add(spec.user().encoded());
        }
        if (!spec.rlimits().isEmpty()) {
            command.add("--rlimits");
            command.add(Rlimit.encoded(spec.rlimits()));
        }
        if (spec.oomScoreAdj() != null) {
            command.add("--oom");
            command.add(Integer.toString(spec.oomScoreAdj()));
        }
        if (spec.apparmorProfile() != null) {
            command.add("--apparmor");
            command.add(spec.apparmorProfile());
        }
        command.add("--");
        command.addAll(spec.args());
        Process helper = new ProcessBuilder(command).inheritIO().start();
        return helper.waitFor();
    }

    public static int start(Path root, String id) throws IOException {
        requireLinux();
        Path state = requireContainer(root, id);
        LinuxCalls.signalStart(state.resolve("start.fifo").toString());
        LinuxCalls.waitUntilExec(state.resolve("exec.fifo").toString());
        Files.writeString(state.resolve("status"), "running");
        String bundle = text(state.resolve("bundle"));
        if (!bundle.isEmpty() && Files.exists(Path.of(bundle).resolve("config.json"))) {
            OciConfig config = OciConfig.read(Path.of(bundle));
            String pid = Files.exists(state.resolve("pid")) ? Files.readString(state.resolve("pid")).trim() : "0";
            runHooks(config, "poststart", hookState(config, id, "running", pid, bundle));
        }
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
        LinuxCalls.kill(pid, Signals.number(signal));
        return 0;
    }

    public static int delete(Path root, String id, boolean force) throws IOException {
        Path state = containerDir(root, id);
        if (!Files.isDirectory(state)) {
            return 0;
        }
        String status = Files.readString(state.resolve("status")).trim();
        if (!force && "running".equals(status) && processAlive(state)) {
            throw new IllegalStateException("container is running: " + id);
        }
        if (force || "created".equals(status) || "running".equals(status)) {
            signalCreatedOrForced(root, id, state);
        }
        if (Files.exists(state.resolve("systemd-unit"))) {
            try {
                SystemdBus.stop(Files.readString(state.resolve("systemd-unit")).trim());
            } catch (RuntimeException ignored) {
                // The scope may already have stopped with its last process.
            }
        } else if (Files.exists(state.resolve("cgroup"))) {
            Cgroups.remove(Files.readString(state.resolve("cgroup")).trim());
        }
        String bundle = text(state.resolve("bundle"));
        if (!bundle.isEmpty() && Files.exists(Path.of(bundle).resolve("config.json"))) {
            OciConfig config = OciConfig.read(Path.of(bundle));
            String pid = Files.exists(state.resolve("pid")) ? Files.readString(state.resolve("pid")).trim() : "0";
            runHooks(config, "poststop", hookState(config, id, "stopped", pid, bundle));
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

    private static void requireLinux() {
        if (!linuxHost()) {
            throw new IllegalStateException("container commands require Linux");
        }
    }

    private static boolean linuxHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static void defaultDeviceSymlinks() {
        symlink("/dev/fd", "/proc/self/fd");
        symlink("/dev/stdin", "/proc/self/fd/0");
        symlink("/dev/stdout", "/proc/self/fd/1");
        symlink("/dev/stderr", "/proc/self/fd/2");
    }

    private static void symlink(String path, String target) {
        Path file = Path.of(path);
        if (Files.exists(file)) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.createSymbolicLink(file, Path.of(target));
        } catch (IOException failure) {
            throw new IllegalStateException("symlink " + path + " failed", failure);
        }
    }

    private static void runHooks(OciConfig config, String name, Map<String, String> state) {
        List<OciHook> hooks = config.hooks().getOrDefault(name, List.of());
        if (hooks.isEmpty()) {
            return;
        }
        new HookRunner(HookRunner::spawn).run(hooks, state);
    }

    private static Map<String, String> hookState(OciConfig config, String id, String status, String pid, String bundle) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("ociVersion", "1.2.0");
        state.put("id", id);
        state.put("status", status);
        state.put("pid", pid);
        state.put("bundle", bundle);
        state.put("annotations", annotationsJson(config.annotations()));
        return state;
    }

    private static String annotationsJson(Map<String, String> annotations) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : annotations.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append('"').append(':');
            json.append('"').append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
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
