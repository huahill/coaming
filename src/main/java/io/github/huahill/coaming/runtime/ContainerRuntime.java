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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import io.github.huahill.coaming.oci.BindFds;
import io.github.huahill.coaming.oci.IdMap;
import io.github.huahill.coaming.oci.IdmapPlan;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
        if (config.apparmorProfile() != null) {
            Path profiles = Path.of("/sys/kernel/security/apparmor/profiles");
            if (!Files.isRegularFile(profiles)) {
                throw new IllegalStateException("apparmor is not enabled");
            }
            ApparmorProfiles.requireLoaded(config.apparmorProfile(), Files.readString(profiles));
        }
        SystemdDevices.Program devices = null;
        if (systemd) {
            devices = SystemdDevices.translate(config.deviceRules());
        } else {
            DevicePolicy.requireEnforceable(config.deviceRules());
        }
        Files.createDirectories(state);
        Files.copy(bundle.resolve("config.json"), state.resolve("config.json"));
        Files.writeString(state.resolve("bundle"), bundle.toAbsolutePath().toString());
        Files.writeString(state.resolve("status"), "creating");
        RuntimeFifo.mkfifo(state.resolve("start.fifo").toString());
        RuntimeFifo.mkfifo(state.resolve("exec.fifo").toString());
        RuntimeFifo.mkfifo(state.resolve("created.fifo").toString());
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
        command.addAll(io.github.huahill.coaming.oci.BindPlan.arguments(config.mounts()));
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
        RuntimeFifo.awaitCreated(state.resolve("created.fifo").toString());
        String createdStatus = Files.readString(state.resolve("status")).trim();
        if ("failed".equals(createdStatus)) {
            throw new IllegalStateException("container init failed");
        }
        if (!"created".equals(createdStatus)) {
            throw new IllegalStateException("timed out waiting for container creation");
        }
        Map<String, String> created = hookState(config, id, "created", Integer.toString(pid), bundle.toAbsolutePath().toString());
        runHooks(config, "createRuntime", created);
        runHooks(config, "prestart", created);
        return 0;
    }

    public static int init(Path state, Path bundle) throws Exception {
        boolean announced = false;
        int execFd = -1;
        FileChannel errors = FileChannel.open(state.resolve("error"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        note(errors, "init");
        try {
            OciConfig config = OciConfig.read(state);
            String hookId = state.getFileName().toString();
            String hookPid = Files.exists(state.resolve("pid")) ? Files.readString(state.resolve("pid")).trim() : "0";
            String hookBundle = bundle.toAbsolutePath().toString();
            recordExecutable(state);
            int startFd = RuntimeFifo.openStartFifo(state.resolve("start.fifo").toString());
            // Hold the exec fifo before create returns. Otherwise start can observe
            // a writer-less FIFO and treat a later exec failure as success.
            execFd = RuntimeFifo.openExecFifo(state.resolve("exec.fifo").toString());
            Files.writeString(state.resolve("status"), "created");
            RuntimeFifo.signal(state.resolve("created.fifo").toString());
            announced = true;
            RuntimeFifo.readStartByte(startFd);
            Path rootfs = config.root(bundle).toAbsolutePath();
            boolean newMount = createsNamespace(config, "mount");
            if (newMount) {
                LinuxCalls.bindPrivate(rootfs.toString());
            }
            boolean mountsProc = false;
            java.util.Map<String, Integer> idmaps = IdmapPlan.descriptors(System::getenv);
            java.util.Map<String, Integer> binds = BindFds.descriptors(System::getenv);
            try {
                if (newMount) {
                    for (OciMount mount : config.mounts()) {
                        if ("/proc".equals(mount.destination())) {
                            mountsProc = true;
                        }
                        mountOne(rootfs, mount, idmaps, binds);
                    }
                }
            } finally {
                for (int descriptor : binds.values()) {
                    LinuxCalls.closeFd(descriptor);
                }
            }
            if (newMount) {
                for (DeviceNode device : config.devices()) {
                    if (!device.path().startsWith("/")) {
                        throw new IllegalArgumentException("device path must be absolute");
                    }
                    Path target = rootfs.resolve(device.path().substring(1));
                    LinuxCalls.createDevice(target.toString(), device.path(), device.type(), device.major(), device.minor(), device.fileMode());
                }
                Path oldRoot = rootfs.resolve("oldroot");
                Files.createDirectories(oldRoot);
                LinuxCalls.pivotRoot(rootfs.toString(), oldRoot.toString());
                LinuxCalls.chdir("/");
                if (config.rootfsPropagation() != null) {
                    LinuxCalls.propagation("/", MountPlan.propagation(config.rootfsPropagation()));
                }
                LinuxCalls.umountDetach("/oldroot");
                if (!mountsProc) {
                    LinuxCalls.mountProc("/proc");
                }
                for (Map.Entry<String, String> sysctl : config.sysctl().entrySet()) {
                    LinuxCalls.writeSysctl(sysctl.getKey(), sysctl.getValue());
                }
                defaultDeviceSymlinks();
                for (String masked : config.maskedPaths()) {
                    LinuxCalls.mask(masked);
                }
                for (String readonly : config.readonlyPaths()) {
                    LinuxCalls.readonly(readonly);
                }
                if (config.rootReadonly()) {
                    LinuxCalls.readonlyRoot();
                }
            }
            String hostname = hostnameToSet(config);
            if (hostname != null) {
                LinuxCalls.hostname(hostname);
            }
            if (!"/".equals(config.cwd())) {
                LinuxCalls.chdir(config.cwd());
            }
            runHooks(config, "createContainer", hookState(config, hookId, "running", hookPid, hookBundle));
            String[] args = prepareProcess(
                    config.args(), config.cwd(), config.env(), config.apparmorProfile(), config.capabilities(),
                    config.user(), config.rlimits(), config.oomScoreAdj(), config.noNewPrivileges(), config.seccomp());
            runHooks(config, "startContainer", hookState(config, hookId, "running", hookPid, hookBundle));
            note(errors, "exec");
            try {
                LinuxCalls.exec(args, config.env().toArray(String[]::new));
            } catch (RuntimeException failure) {
                throw failure;
            }
            return 1;
        } catch (Exception failure) {
            note(errors, failure.toString());
            if (execFd >= 0) {
                RuntimeFifo.signalExecFailed(execFd);
            }
            if (!announced) {
                announceFailure(state);
            }
            throw failure;
        }
    }

    private static void note(FileChannel errors, String message) {
        try {
            errors.write(ByteBuffer.wrap((message + "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
            // The container error is reported by the caller.
        }
    }


    private static String executableFor(Path state, long hostUid, long hostGid) throws Exception {
        String launcher = executableLauncher();
        if (hostUid < 0 || hostGid < 0) {
            if (launcher != null) {
                return launcher;
            }
            return Memfd.copyCurrentExecutable();
        }
        if (hostUid == Cgroups.euid(Files.readString(Path.of("/proc/self/status")))) {
            if (launcher != null) {
                return launcher;
            }
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

    private static String executableLauncher() {
        String launcher = System.getenv("COAMING_LAUNCHER");
        if (launcher == null || launcher.isBlank() || !Files.isExecutable(Path.of(launcher))) {
            return null;
        }
        return launcher;
    }

    private static boolean createsNamespace(OciConfig config, String type) {
        return namespace(config, type, true);
    }

    private static boolean hasNamespace(OciConfig config, String type) {
        return namespace(config, type, false);
    }

    private static boolean namespace(OciConfig config, String type, boolean createdOnly) {
        if (!config.namespacesSpecified()) {
            return false;
        }
        for (io.github.huahill.coaming.oci.Namespace namespace : config.namespaces()) {
            if (!type.equals(namespace.type())) {
                continue;
            }
            boolean created = namespace.path() == null || namespace.path().isBlank();
            if (!createdOnly || created) {
                return true;
            }
        }
        return false;
    }

    private static String hostnameToSet(OciConfig config) throws IOException {
        String configured = config.hostname();
        if ((configured == null || configured.isBlank()) && createsNamespace(config, "uts")) {
            configured = "coaming";
        }
        String current = "";
        Path self = Path.of("/proc/self/ns/uts");
        if (Files.exists(self)) {
            current = Files.readSymbolicLink(self).toString();
        }
        return UtsHostname.nameToSet(configured, hasNamespace(config, "uts"), System.getenv("COAMING_HOST_UTS"), current);
    }

    private static void applyResources(Path cgroup, io.github.huahill.coaming.json.Json resources) throws Exception {
        NoSuchFileException missing = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                Resources.apply(cgroup, resources);
                if (Resources.limitsHold(cgroup, resources)) {
                    return;
                }
            } catch (NoSuchFileException failure) {
                missing = failure;
            }
            Thread.sleep(20);
        }
        if (missing != null) {
            throw missing;
        }
        throw new IllegalStateException("cgroup limit did not hold");
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
            long remount = MountPlan.BIND | MountPlan.REMOUNT
                    | (prepared.flags() & (MountPlan.NOSUID | MountPlan.NODEV | MountPlan.NOEXEC | MountPlan.STRICTATIME | MountPlan.NOATIME | MountPlan.RDONLY));
            if (prepared.remount() != 0) {
                remount |= MountPlan.RDONLY;
            }
            LinuxCalls.remount(dest.toString(), remount);
            if (prepared.propagation() != 0) {
                LinuxCalls.propagation(dest.toString(), prepared.propagation());
            }
            return;
        }
        LinuxCalls.mountPrepared(prepared.source(), dest.toString(), prepared.fstype(), prepared.flags(), prepared.remount(), prepared.propagation(), prepared.data());
    }

    public static int exec(Path root, String id, Path process, Path pidFile, boolean detach, String console) throws Exception {
        requireLinux();
        Path state = requireContainer(root, id);
        int target = Integer.parseInt(Files.readString(state.resolve("pid")).trim());
        String specText = Files.readString(process);
        OciProcess spec = OciProcess.parse(specText);
        if (spec.args().isEmpty()) {
            throw new IllegalArgumentException("exec process has no args");
        }
        String configText = Files.readString(state.resolve("config.json"));
        List<String> command = new ArrayList<>();
        command.add(Path.of("/proc/self/exe").toRealPath().toString());
        command.add("exec-init");
        command.add("--pid");
        command.add(Integer.toString(target));
        if (pidFile != null) {
            command.add("--pid-path");
            command.add(pidFile.toAbsolutePath().toString());
        }
        if (console != null && !console.isBlank()) {
            command.add("--console");
            command.add(console);
        }
        if (detach) {
            command.add("--detach");
        }
        for (String variable : spec.env()) {
            command.add("--env");
            command.add(variable);
        }
        command.add("--");
        command.addAll(spec.args());
        ProcessBuilder builder = new ProcessBuilder(command);
        var env = builder.environment();
        env.put("COAMING_EXEC_CWD", spec.cwd());
        if (spec.capabilities() != null) {
            env.put("COAMING_EXEC_CAPS", spec.capabilities().encoded());
        }
        if (spec.user() != null) {
            env.put("COAMING_EXEC_USER", spec.user().encoded());
        }
        if (!spec.rlimits().isEmpty()) {
            env.put("COAMING_EXEC_RLIMITS", spec.rlimits().stream()
                    .map(limit -> limit.resource() + "=" + limit.soft() + ":" + limit.hard())
                    .reduce((left, right) -> left + "," + right).orElse(""));
        }
        if (spec.oomScoreAdj() != null) {
            env.put("COAMING_EXEC_OOM", Integer.toString(spec.oomScoreAdj()));
        }
        if (spec.apparmorProfile() != null) {
            env.put("COAMING_EXEC_APPARMOR", spec.apparmorProfile());
        }
        if (spec.noNewPrivileges()) {
            env.put("COAMING_EXEC_NNP", "1");
        }
        String rules = seccompRules(seccompFromConfig(configText));
        if (!rules.isEmpty()) {
            env.put("COAMING_EXEC_SECCOMP", rules);
        }
        builder.inheritIO();
        return builder.start().waitFor();
    }
    static String seccompRules(io.github.huahill.coaming.oci.SeccompProfile profile) {
        if (profile == null) {
            return "";
        }
        StringBuilder rules = new StringBuilder();
        rules.append("DEFAULT ").append(profile.defaultAction()).append('\n');
        for (var call : profile.syscalls()) {
            for (String name : call.names()) {
                rules.append("RULE ").append(call.action()).append(' ').append(name);
                for (var arg : call.args()) {
                    rules.append(' ').append(arg.index()).append(' ').append(arg.operation())
                            .append(' ').append(arg.value()).append(' ').append(arg.valueTwo());
                }
                rules.append('\n');
            }
        }
        return rules.toString();
    }

    public static int execInside() {
        requireLinux();
        String specText = System.getenv("COAMING_EXEC_SPEC");
        if (specText == null || specText.isBlank()) {
            throw new IllegalStateException("exec spec is missing");
        }
        OciProcess spec = OciProcess.parse(specText);
        String[] args = prepareProcess(
                spec.args(), spec.cwd(), spec.env(), spec.apparmorProfile(), spec.capabilities(),
                spec.user(), spec.rlimits(), spec.oomScoreAdj(), spec.noNewPrivileges(),
                seccompFromConfig(System.getenv("COAMING_EXEC_CONFIG")));
        LinuxCalls.exec(args, spec.env().toArray(String[]::new));
        return 1;
    }

    static io.github.huahill.coaming.oci.SeccompProfile seccompFromConfig(String configText) {
        if (configText == null || configText.isBlank()) {
            return null;
        }
        io.github.huahill.coaming.json.Json linux = io.github.huahill.coaming.json.Json.parse(configText).optional("linux");
        if (linux == null || linux.optional("seccomp") == null) {
            return null;
        }
        return io.github.huahill.coaming.oci.SeccompProfile.parse(linux.optional("seccomp"));
    }

    private static String[] prepareProcess(
            List<String> arguments, String cwd, List<String> env, String apparmor, io.github.huahill.coaming.oci.LinuxCapabilities capabilities,
            io.github.huahill.coaming.oci.ProcessUser user, List<io.github.huahill.coaming.oci.Rlimit> rlimits, Integer oom, boolean noNewPrivileges,
            io.github.huahill.coaming.oci.SeccompProfile seccomp) {
        String terminal = System.getenv("COAMING_TTY_FD");
        if (terminal != null && !terminal.isBlank()) {
            LinuxCalls.claimTerminal(Integer.parseInt(terminal));
        }
        if (cwd != null && !cwd.isBlank() && !"/".equals(cwd)) {
            LinuxCalls.chdir(cwd);
        }
        if (arguments.isEmpty()) {
            throw new IllegalStateException("container has no process args");
        }
        String[] args = arguments.toArray(String[]::new);
        args[0] = resolveProgram(args[0], env);
        if (apparmor != null) {
            LinuxCalls.setApparmor(apparmor);
        }
        if (capabilities != null) {
            LinuxCalls.dropBounding(capabilities);
        }
        LinuxCalls.applyIdentity(user, rlimits, oom, capabilities != null);
        if (capabilities != null) {
            LinuxCalls.commitCapabilities(capabilities);
        }
        if (noNewPrivileges) {
            LinuxCalls.noNewPrivileges();
        }
        if (seccomp != null) {
            LinuxCalls.installSeccomp(seccomp);
        }
        return args;
    }

    private static String containerError(Path error) {
        if (!Files.isRegularFile(error)) {
            return "";
        }
        try {
            String last = "";
            for (String line : Files.readString(error).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || "init".equals(trimmed) || "exec".equals(trimmed)) {
                    continue;
                }
                last = trimmed;
            }
            return last;
        } catch (IOException failure) {
            return "";
        }
    }

    public static int start(Path root, String id) throws Exception {
        requireLinux();
        Path state = requireContainer(root, id);
        // The read end has to be open before the payload is released. Otherwise a
        // fast exec failure closes the only fifo descriptor and the error byte is lost.
        int execRead = RuntimeFifo.openExecRead(state.resolve("exec.fifo").toString());
        RuntimeFifo.signal(state.resolve("start.fifo").toString());
        try {
            RuntimeFifo.readExecResult(execRead);
        } catch (IllegalStateException failure) {
            String detail = containerError(state.resolve("error"));
            if (detail.isBlank()) {
                throw failure;
            }
            throw new IllegalStateException(detail, failure);
        }
        String detail = containerError(state.resolve("error"));
        if (!detail.isBlank()) {
            throw new IllegalStateException(detail);
        }
        // systemd finishes the scope after create returns and resets memory.max.
        // Apply the limit again once the payload is running.
        if (Files.exists(state.resolve("cgroup")) && Files.exists(state.resolve("config.json"))) {
            // A one-shot command can exit before systemd finishes the scope. The limit was
            // already applied at create, and the cgroup file disappears with the process.
            if (processRunning(state)) {
                Path cgroup = Path.of(Files.readString(state.resolve("cgroup")).trim());
                if (Files.isDirectory(cgroup)) {
                    OciConfig config = OciConfig.read(state);
                    if (config.resources() != null) {
                        try {
                            applyResources(cgroup, config.resources());
                        } catch (NoSuchFileException failure) {
                            if (processRunning(state)) {
                                throw failure;
                            }
                        }
                    }
                }
            }
        }
        Files.writeString(state.resolve("status"), "running");
        String bundle = text(state.resolve("bundle"));
        if (Files.exists(state.resolve("config.json"))) {
            OciConfig config = OciConfig.read(state);
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
        OciConfig saved = savedConfig(state);
        if (saved != null && !bundle.isEmpty()) {
            try {
                rootfs = saved.root(Path.of(bundle)).toString();
            } catch (RuntimeException ignored) {
                rootfs = "";
            }
        }
        System.out.printf(
                "{\"ociVersion\":\"1.2.0\",\"id\":\"%s\",\"status\":\"%s\",\"pid\":%s,\"bundle\":\"%s\",\"rootfs\":\"%s\"}%n",
                escape(id), escape(status), pid, escape(bundle), escape(rootfs));
        return 0;
    }

    public static int kill(Path root, String id, String signal, boolean all) throws IOException {
        requireLinux();
        Path state = requireContainer(root, id);
        List<Integer> pids = new ArrayList<>();
        if (all) {
            pids.addAll(cgroupPids(state));
        }
        if (pids.isEmpty()) {
            pids.add(Integer.parseInt(Files.readString(state.resolve("pid")).trim()));
        }
        int number = Signals.number(signal);
        for (int pid : pids) {
            LinuxCalls.kill(pid, number);
        }
        return 0;
    }

    private static List<Integer> cgroupPids(Path state) throws IOException {
        if (!Files.isRegularFile(state.resolve("cgroup"))) {
            return List.of();
        }
        String cgroup = Files.readString(state.resolve("cgroup")).trim();
        if (cgroup.isEmpty()) {
            return List.of();
        }
        Path procs = Path.of(cgroup).resolve("cgroup.procs");
        if (!Files.isRegularFile(procs)) {
            return List.of();
        }
        List<Integer> pids = new ArrayList<>();
        for (String item : Files.readString(procs).split("\\s+")) {
            if (!item.isBlank()) {
                pids.add(Integer.parseInt(item));
            }
        }
        return pids;
    }

    public static int delete(Path root, String id, boolean force) throws IOException {
        Path state = containerDir(root, id);
        if (!Files.isDirectory(state)) {
            return 0;
        }
        String status = Files.readString(state.resolve("status")).trim();
        boolean alive = Files.exists(state.resolve("pid")) && processAlive(state);
        if (!deletionIsAllowed(status, alive, force)) {
            throw new IllegalStateException("container is not stopped: " + id);
        }
        if (!alive && ("running".equals(status) || "created".equals(status))) {
            status = "stopped";
            Files.writeString(state.resolve("status"), status);
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
        OciConfig config = savedConfig(state);
        if (config != null) {
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
            kill(root, id, "KILL", false);
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

    private static boolean processRunning(Path state) throws IOException {
        if (!processAlive(state) || !Files.exists(state.resolve("pid"))) {
            return false;
        }
        int pid = Integer.parseInt(Files.readString(state.resolve("pid")).trim());
        Path stat = Path.of("/proc").resolve(Integer.toString(pid)).resolve("stat");
        if (!Files.isRegularFile(stat)) {
            return false;
        }
        String text = Files.readString(stat);
        int end = text.lastIndexOf(')');
        if (end < 0 || end + 2 >= text.length()) {
            return false;
        }
        char mark = text.charAt(end + 2);
        return mark != 'Z' && mark != 'X';
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

    private static OciConfig savedConfig(Path state) {
        if (!Files.exists(state.resolve("config.json"))) {
            return null;
        }
        try {
            return OciConfig.read(state);
        } catch (RuntimeException | IOException ignored) {
            return null;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void announceFailure(Path state) {
        try {
            Files.writeString(state.resolve("status"), "failed");
        } catch (IOException ignored) {
            // The fifo is what unblocks create.
        }
        try {
            RuntimeFifo.signal(state.resolve("created.fifo").toString());
        } catch (RuntimeException ignored) {
            // create reports the timeout when this signal cannot be delivered.
        }
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

    static boolean deletionIsAllowed(String status, boolean alive, boolean force) {
        if (force || !alive) {
            return true;
        }
        return "stopped".equals(status);
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
