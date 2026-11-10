package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record OciConfig(List<String> args, String cwd, String rootPath, String hostname, List<Namespace> namespaces, boolean namespacesSpecified, List<String> env, List<OciMount> mounts, List<String> maskedPaths, List<String> readonlyPaths, List<DeviceNode> devices, List<DeviceRule> deviceRules, SeccompProfile seccomp, String cgroupsPath, LinuxCapabilities capabilities, boolean noNewPrivileges, ProcessUser user, List<Rlimit> rlimits, Integer oomScoreAdj) {
    public static OciConfig read(Path bundle) throws IOException {
        String text = Files.readString(bundle.resolve("config.json"));
        Json root = Json.parse(text);
        Json process = root.field("process");
        Json rootfs = root.field("root");
        List<Namespace> namespaces = new ArrayList<>();
        boolean namespacesSpecified = false;
        Json linux = root.optional("linux");
        if (linux != null) {
            Json values = linux.optional("namespaces");
            if (values instanceof Json.Arr arr) {
                namespacesSpecified = true;
                for (Json item : arr.items()) {
                    String path = null;
                    Json pathValue = item.optional("path");
                    if (pathValue != null && !pathValue.string().isBlank()) {
                        path = pathValue.string();
                    }
                    namespaces.add(new Namespace(item.field("type").string(), path));
                }
            }
        }
        String cwd = "/";
        Json cwdValue = process.optional("cwd");
        if (cwdValue != null) {
            cwd = cwdValue.string();
        }
        String hostname = "coaming";
        Json hostnameValue = root.optional("hostname");
        if (hostnameValue != null) {
            hostname = hostnameValue.string();
        }
        List<String> env = List.of();
        Json envValue = process.optional("env");
        if (envValue != null) {
            env = envValue.strings();
        }
        List<OciMount> mounts = new ArrayList<>();
        Json mountValues = root.optional("mounts");
        if (mountValues instanceof Json.Arr arr) {
            for (Json item : arr.items()) {
                String mountType = "";
                Json typeValue = item.optional("type");
                if (typeValue != null) {
                    mountType = typeValue.string();
                }
                String source = "";
                Json sourceValue = item.optional("source");
                if (sourceValue != null) {
                    source = sourceValue.string();
                }
                List<String> options = List.of();
                Json optionValue = item.optional("options");
                if (optionValue != null) {
                    options = optionValue.strings();
                }
                mounts.add(new OciMount(item.field("destination").string(), mountType, source, options));
            }
        }
        List<String> maskedPaths = List.of();
        List<String> readonlyPaths = List.of();
        if (linux != null) {
            Json masked = linux.optional("maskedPaths");
            if (masked != null) {
                maskedPaths = masked.strings();
            }
            Json readonly = linux.optional("readonlyPaths");
            if (readonly != null) {
                readonlyPaths = readonly.strings();
            }
        }
        String cgroupsPath = null;
        if (linux != null && linux.optional("cgroupsPath") != null) {
            cgroupsPath = linux.optional("cgroupsPath").string();
        }
        List<DeviceNode> devices = new ArrayList<>();
        List<DeviceRule> deviceRules = new ArrayList<>();
        SeccompProfile seccomp = null;
        if (linux != null) {
            Json deviceValues = linux.optional("devices");
            if (deviceValues instanceof Json.Arr arr) {
                for (Json item : arr.items()) {
                    int mode = 438;
                    Json modeValue = item.optional("fileMode");
                    if (modeValue != null) {
                        mode = (int) modeValue.longValue();
                    }
                    devices.add(new DeviceNode(item.field("path").string(), item.field("type").string(), item.field("major").longValue(), item.field("minor").longValue(), mode, optionalInt(item, "uid"), optionalInt(item, "gid")));
                }
            }
            Json resources = linux.optional("resources");
            if (resources != null && resources.optional("devices") instanceof Json.Arr rules) {
                for (Json item : rules.items()) {
                    boolean allow = item.optional("allow") != null && item.optional("allow").booleanValue();
                    String deviceType = item.optional("type") == null ? "a" : item.optional("type").string();
                    String access = item.optional("access") == null ? "rwm" : item.optional("access").string();
                    Integer major = wildcard(item, "major");
                    Integer minor = wildcard(item, "minor");
                    boolean all = "a".equals(deviceType) && major == null && minor == null;
                    deviceRules.add(new DeviceRule(allow, deviceType, access, all, major, minor));
                }
            }
            if (linux.optional("seccomp") != null) {
                seccomp = SeccompProfile.parse(linux.optional("seccomp"));
            }
        }
        rejectUnsupportedProfiles(process);
        LinuxCapabilities capabilities = LinuxCapabilities.from(process.optional("capabilities"));
        boolean noNewPrivileges = process.optional("noNewPrivileges") != null && process.optional("noNewPrivileges").booleanValue();
        Integer oomScoreAdj = process.optional("oomScoreAdj") == null ? null : (int) process.optional("oomScoreAdj").longValue();
        return new OciConfig(process.field("args").strings(), cwd, rootfs.field("path").string(), hostname, namespaces, namespacesSpecified, env, List.copyOf(mounts), maskedPaths, readonlyPaths, List.copyOf(devices), List.copyOf(deviceRules), seccomp, cgroupsPath, capabilities, noNewPrivileges, ProcessUser.from(process), Rlimit.list(process), oomScoreAdj);
    }

    private static void rejectUnsupportedProfiles(Json process) {
        Json apparmor = process.optional("apparmorProfile");
        if (apparmor != null && !apparmor.string().isBlank() && !"unconfined".equals(apparmor.string())) {
            throw new IllegalArgumentException("apparmor profile is not implemented: " + apparmor.string());
        }
        Json selinux = process.optional("selinuxLabel");
        if (selinux != null && !selinux.string().isBlank()) {
            throw new IllegalArgumentException("selinux label is not implemented");
        }
    }

    private static int optionalInt(Json item, String name) {
        Json value = item.optional(name);
        return value == null ? 0 : (int) value.longValue();
    }

    private static Integer wildcard(Json item, String name) {
        Json value = item.optional(name);
        if (value == null) {
            return null;
        }
        int number = (int) value.longValue();
        return number < 0 ? null : number;
    }

    public Path root(Path bundle) {
        Path path = Path.of(rootPath);
        return path.isAbsolute() ? path : bundle.resolve(path);
    }
}
