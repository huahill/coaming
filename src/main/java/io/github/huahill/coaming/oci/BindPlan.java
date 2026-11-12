package io.github.huahill.coaming.oci;

import java.util.ArrayList;
import java.util.List;

public final class BindPlan {
    private static final long RDONLY = 0x1;
    private static final long NOSUID = 0x2;
    private static final long NODEV = 0x4;
    private static final long NOEXEC = 0x8;
    private static final long NOATIME = 0x10;
    private static final long STRICTATIME = 0x20;
    private static final long NODIRATIME = 0x80;

    private BindPlan() {}

    public static List<String> arguments(List<OciMount> mounts) {
        List<String> args = new ArrayList<>();
        for (OciMount mount : mounts) {
            if (mount.source() == null || !mount.source().startsWith("/")) {
                continue;
            }
            String type = mount.type() == null ? "" : mount.type();
            List<String> options = mount.options() == null ? List.of() : mount.options();
            boolean recursive = "rbind".equals(type) || options.contains("rbind");
            boolean bind = recursive || "bind".equals(type) || options.contains("bind");
            if (!bind || options.contains("idmap") || options.contains("ridmap")) {
                continue;
            }
            long attr = 0;
            boolean recursiveReadonly = false;
            for (String option : options) {
                switch (option) {
                    case "rbind" -> recursive = true;
                    case "ro" -> attr |= RDONLY;
                    case "rro" -> {
                        attr |= RDONLY;
                        recursive = true;
                        recursiveReadonly = true;
                    }
                    case "rw" -> {
                        attr &= ~RDONLY;
                        recursiveReadonly = false;
                    }
                    case "nosuid" -> attr |= NOSUID;
                    case "nodev" -> attr |= NODEV;
                    case "noexec" -> attr |= NOEXEC;
                    case "noatime" -> attr |= NOATIME;
                    case "strictatime" -> attr |= STRICTATIME;
                    case "nodiratime" -> attr |= NODIRATIME;
                    default -> {
                    }
                }
            }
            args.add("--bind");
            args.add(mount.source());
            args.add(mount.destination());
            args.add(recursive ? "1" : "0");
            args.add(Long.toString(attr));
            args.add(recursiveReadonly ? "1" : "0");
        }
        return List.copyOf(args);
    }
}
