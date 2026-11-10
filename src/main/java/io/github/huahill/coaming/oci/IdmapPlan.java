package io.github.huahill.coaming.oci;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class IdmapPlan {
    static final long MOUNT_ATTR_IDMAP = 0x00100000;

    private IdmapPlan() {}

    public static List<String> arguments(List<OciMount> mounts, List<IdMap> uidMappings, List<IdMap> gidMappings, boolean createUser) {
        List<String> args = new ArrayList<>();
        for (OciMount mount : mounts) {
            MountPlan.Prepared prepared = MountPlan.prepare(mount);
            if (prepared.idmap() == MountPlan.Idmap.NONE) {
                continue;
            }
            if (!createUser || uidMappings.isEmpty() || gidMappings.isEmpty()) {
                throw new IllegalArgumentException("idmap requires a new user namespace");
            }
            if (mount.uidMappings().isEmpty() != mount.gidMappings().isEmpty()) {
                throw new IllegalArgumentException("mount uidMappings and gidMappings must both be set");
            }
            if (!mount.uidMappings().isEmpty() && (!mount.uidMappings().equals(uidMappings) || !mount.gidMappings().equals(gidMappings))) {
                throw new IllegalArgumentException("mount id mappings must match the container user namespace");
            }
            args.add("--idmap");
            args.add(mount.source());
            args.add(mount.destination());
            args.add((prepared.flags() & MountPlan.REC) != 0 ? "1" : "0");
            args.add(prepared.idmap() == MountPlan.Idmap.RECURSIVE ? "1" : "0");
            args.add(Long.toString(attributes(prepared)));
            args.add(Long.toString(prepared.propagation()));
        }
        return List.copyOf(args);
    }

    public static Map<String, Integer> descriptors(Function<String, String> env) {
        String countText = env.apply("COAMING_IDMAP_COUNT");
        if (countText == null || countText.isBlank()) {
            return Map.of();
        }
        int count = Integer.parseInt(countText);
        Map<String, Integer> fds = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String destination = env.apply("COAMING_IDMAP_DST_" + i);
            String descriptor = env.apply("COAMING_IDMAP_FD_" + i);
            if (destination == null || descriptor == null) {
                throw new IllegalStateException("idmap mount was not prepared");
            }
            fds.put(destination, Integer.parseInt(descriptor));
        }
        return Map.copyOf(fds);
    }

    private static long attributes(MountPlan.Prepared prepared) {
        long flags = prepared.flags() | prepared.remount();
        long attr = MOUNT_ATTR_IDMAP;
        if ((flags & MountPlan.RDONLY) != 0) {
            attr |= 0x1;
        }
        if ((flags & MountPlan.NOSUID) != 0) {
            attr |= 0x2;
        }
        if ((flags & MountPlan.NODEV) != 0) {
            attr |= 0x4;
        }
        if ((flags & MountPlan.NOEXEC) != 0) {
            attr |= 0x8;
        }
        if ((flags & MountPlan.NOATIME) != 0) {
            attr |= 0x10;
        }
        if ((flags & MountPlan.STRICTATIME) != 0) {
            attr |= 0x20;
        }
        if ((flags & MountPlan.NODIRATIME) != 0) {
            attr |= 0x80;
        }
        return attr;
    }
}
