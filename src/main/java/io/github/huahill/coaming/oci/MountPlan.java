package io.github.huahill.coaming.oci;

import java.util.ArrayList;
import java.util.List;

public final class MountPlan {
    public static final long RDONLY = 1;
    public static final long NOSUID = 2;
    public static final long NODEV = 4;
    public static final long NOEXEC = 8;
    public static final long REMOUNT = 32;
    public static final long NOATIME = 1024;
    public static final long NODIRATIME = 2048;
    public static final long BIND = 4096;
    public static final long REC = 16384;
    public static final long UNBINDABLE = 1L << 17;
    public static final long PRIVATE = 1L << 18;
    public static final long SLAVE = 1L << 19;
    public static final long SHARED = 1L << 20;
    public static final long RELATIME = 1L << 21;
    public static final long STRICTATIME = 1L << 24;
    public static final long LAZYTIME = 1L << 25;

    public enum Idmap { NONE, MOUNT, RECURSIVE }

    private MountPlan() {}

    public record Prepared(String source, String fstype, long flags, long remount, long propagation, String data, Idmap idmap) {}

    public static Prepared prepare(OciMount mount) {
        if (mount.destination() == null || !mount.destination().startsWith("/") || "/".equals(mount.destination())) {
            throw new IllegalArgumentException("mount destination must be an absolute path below /");
        }
        String type = mount.type() == null ? "" : mount.type();
        boolean bind = "bind".equals(type) || "rbind".equals(type);
        boolean recursive = "rbind".equals(type);
        boolean readOnly = false;
        long propagation = 0;
        boolean sawPropagation = false;
        long flags = 0;
        Idmap idmap = Idmap.NONE;
        List<String> data = new ArrayList<>();
        for (String option : mount.options()) {
            switch (option) {
                case "bind" -> bind = true;
                case "rbind" -> {
                    bind = true;
                    recursive = true;
                }
                case "ro" -> readOnly = true;
                case "rw" -> readOnly = false;
                case "defaults" -> {
                }
                case "nosuid" -> flags |= NOSUID;
                case "nodev" -> flags |= NODEV;
                case "noexec" -> flags |= NOEXEC;
                case "noatime" -> flags |= NOATIME;
                case "nodiratime" -> flags |= NODIRATIME;
                case "relatime" -> flags |= RELATIME;
                case "strictatime" -> flags |= STRICTATIME;
                case "lazytime" -> flags |= LAZYTIME;
                case "idmap" -> {
                    if (idmap == Idmap.RECURSIVE) {
                        throw new IllegalArgumentException("idmap and ridmap cannot both be set");
                    }
                    idmap = Idmap.MOUNT;
                }
                case "ridmap" -> {
                    if (idmap == Idmap.MOUNT) {
                        throw new IllegalArgumentException("idmap and ridmap cannot both be set");
                    }
                    idmap = Idmap.RECURSIVE;
                }
                case "newinstance" -> data.add(option);
                case "private" -> {
                    propagation = PRIVATE;
                    sawPropagation = true;
                }
                case "rprivate" -> {
                    propagation = REC | PRIVATE;
                    sawPropagation = true;
                }
                case "shared" -> {
                    propagation = SHARED;
                    sawPropagation = true;
                }
                case "rshared" -> {
                    propagation = REC | SHARED;
                    sawPropagation = true;
                }
                case "slave" -> {
                    propagation = SLAVE;
                    sawPropagation = true;
                }
                case "rslave" -> {
                    propagation = REC | SLAVE;
                    sawPropagation = true;
                }
                case "unbindable" -> {
                    propagation = UNBINDABLE;
                    sawPropagation = true;
                }
                case "runbindable" -> {
                    propagation = REC | UNBINDABLE;
                    sawPropagation = true;
                }
                default -> {
                    if (option.indexOf('=') >= 0) {
                        data.add(option);
                    } else {
                        throw new IllegalArgumentException("unsupported mount option " + option);
                    }
                }
            }
        }
        if (idmap != Idmap.NONE && !bind) {
            throw new IllegalArgumentException("idmap requires a bind mount");
        }
        if (bind) {
            flags |= BIND;
            if (recursive) {
                flags |= REC;
            }
            if (!sawPropagation) {
                propagation = REC | PRIVATE;
            }
        }
        long remount = 0;
        if (readOnly) {
            if (bind) {
                remount = (flags & ~REC) | REMOUNT | RDONLY;
            } else {
                flags |= RDONLY;
            }
        }
        String source = mount.source() == null ? "" : mount.source();
        if ("cgroup".equals(type) || "cgroup2".equals(type)) {
            if (bind) {
                throw new IllegalArgumentException("cgroup mount cannot be a bind");
            }
            return new Prepared("cgroup2", "cgroup2", flags, remount, propagation, String.join(",", data), Idmap.NONE);
        }
        if (bind && !source.startsWith("/")) {
            throw new IllegalArgumentException("bind source must be absolute");
        }
        String fstype = bind ? null : type;
        return new Prepared(source, fstype, flags, remount, propagation, String.join(",", data), idmap);
    }
}
