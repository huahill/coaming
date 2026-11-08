package io.github.huahill.coaming.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class Cgroups {
    private Cgroups() {}

    static String create(String id) throws IOException {
        Path root = Path.of("/sys/fs/cgroup");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("cgroup v2 is not mounted at /sys/fs/cgroup");
        }
        Path dir = root.resolve("coaming-" + id);
        Files.createDirectories(dir);
        return dir.toString();
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
