package io.github.huahill.coaming.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class Cgroups {
    private static final List<String> CONTROLLERS = List.of("cpu", "memory", "pids");

    private Cgroups() {}

    static String create(String id) throws IOException {
        Path root = Path.of("/sys/fs/cgroup");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("cgroup v2 is not mounted at /sys/fs/cgroup");
        }
        enable(root);
        Path dir = root.resolve("coaming-" + id);
        Files.createDirectories(dir);
        return dir.toString();
    }

    static String enableLine(String available, String current) {
        StringBuilder line = new StringBuilder();
        for (String name : CONTROLLERS) {
            if (contains(available, name) && !contains(current, name)) {
                if (!line.isEmpty()) {
                    line.append(' ');
                }
                line.append('+').append(name);
            }
        }
        return line.toString();
    }

    private static void enable(Path root) throws IOException {
        Path control = root.resolve("cgroup.subtree_control");
        Path available = root.resolve("cgroup.controllers");
        if (!Files.exists(control) || !Files.exists(available)) {
            return;
        }
        String line = enableLine(Files.readString(available), Files.readString(control));
        if (line.isEmpty()) {
            return;
        }
        try {
            Files.writeString(control, line + "\n");
        } catch (IOException first) {
            moveToInit(root);
            Files.writeString(control, line + "\n");
        }
    }

    private static void moveToInit(Path root) throws IOException {
        Path init = root.resolve("coaming-init");
        Files.createDirectories(init);
        String procs = Files.readString(root.resolve("cgroup.procs"));
        for (String pid : procs.split("\\s+")) {
            if (pid.isBlank()) {
                continue;
            }
            try {
                Files.writeString(init.resolve("cgroup.procs"), pid + "\n");
            } catch (IOException ignored) {
                // A process may exit while it is being moved.
            }
        }
    }

    private static boolean contains(String text, String name) {
        for (String token : text.split("\\s+")) {
            if (token.equals(name)) {
                return true;
            }
        }
        return false;
    }

    static void add(String cgroup, int pid) throws IOException {
        Files.writeString(Path.of(cgroup, "cgroup.procs"), Integer.toString(pid));
    }

    static void remove(String cgroup) {
        try {
            Files.deleteIfExists(Path.of(cgroup));
        } catch (IOException ignored) {
            // A busy cgroup is removed when its last process exits.
        }
    }
}
