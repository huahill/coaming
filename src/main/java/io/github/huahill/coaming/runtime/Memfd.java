package io.github.huahill.coaming.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class Memfd {
    private static final List<Integer> KEPT_OPEN = new ArrayList<>();

    private Memfd() {}

    static String copyCurrentExecutable() throws IOException {
        int fd = LinuxCalls.memfdCreate("coaming");
        LinuxCalls.writeFully(fd, Files.readAllBytes(Path.of("/proc/self/exe")));
        KEPT_OPEN.add(fd);
        return "/proc/" + ProcessHandle.current().pid() + "/fd/" + fd;
    }
}
