package io.github.huahill.coaming.runtime;

import java.nio.file.Path;

final class ExecLocation {
    private ExecLocation() {}

    static Path directory(Path state, String mountinfo) {
        if (!noexec(mountinfo, state.toString())) {
            return state;
        }
        return Path.of("/var/lib/coaming/exec").resolve(state.getFileName().toString());
    }

    static boolean noexec(String mountinfo, String path) {
        String matched = "";
        boolean denied = false;
        for (String line : mountinfo.split("\\R")) {
            String[] fields = line.split(" ");
            if (fields.length < 6) {
                continue;
            }
            String mountpoint = fields[4];
            if (!covers(mountpoint, path) || mountpoint.length() < matched.length()) {
                continue;
            }
            matched = mountpoint;
            denied = false;
            for (String option : fields[5].split(",")) {
                if ("noexec".equals(option)) {
                    denied = true;
                }
            }
        }
        return denied;
    }

    private static boolean covers(String mountpoint, String path) {
        if (mountpoint.equals(path)) {
            return true;
        }
        String prefix = mountpoint.endsWith("/") ? mountpoint : mountpoint + "/";
        return path.startsWith(prefix);
    }
}
