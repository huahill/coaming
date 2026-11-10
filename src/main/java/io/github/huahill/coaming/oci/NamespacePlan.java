package io.github.huahill.coaming.oci;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NamespacePlan {
    private static final List<String> DEFAULTS = List.of("pid", "mount", "uts", "ipc", "network");
    private static final Set<String> SUPPORTED = Set.of("pid", "mount", "uts", "ipc", "network", "cgroup", "user");

    private NamespacePlan() {}

    public static List<String> arguments(List<Namespace> namespaces, boolean specified) {
        return arguments(namespaces, specified, List.of(), List.of());
    }

    public static List<String> arguments(List<Namespace> namespaces, boolean specified, List<IdMap> uidMappings, List<IdMap> gidMappings) {
        if (!specified) {
            if (!uidMappings.isEmpty() || !gidMappings.isEmpty()) {
                throw new IllegalArgumentException("id mappings require a user namespace");
            }
            return List.of("--unshare", String.join(",", DEFAULTS));
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> unshare = new ArrayList<>();
        List<String> joins = new ArrayList<>();
        boolean createUser = false;
        for (Namespace namespace : namespaces) {
            String type = namespace.type();
            if (!seen.add(type)) {
                throw new IllegalArgumentException("duplicate namespace " + type);
            }
            if (!SUPPORTED.contains(type)) {
                throw new IllegalArgumentException("unsupported namespace " + type);
            }
            String path = namespace.path();
            if ("user".equals(type) && (path == null || path.isBlank())) {
                createUser = true;
                continue;
            }
            if (path == null || path.isBlank()) {
                unshare.add(type);
            } else {
                if (!path.startsWith("/")) {
                    throw new IllegalArgumentException("namespace path must be absolute: " + path);
                }
                joins.add(type + "=" + path);
            }
        }
        if (createUser && (uidMappings.isEmpty() || gidMappings.isEmpty())) {
            throw new IllegalArgumentException("user namespace requires uid and gid mappings");
        }
        if (!createUser && (!uidMappings.isEmpty() || !gidMappings.isEmpty())) {
            boolean joinsUser = joins.stream().anyMatch(join -> join.startsWith("user="));
            if (!joinsUser) {
                throw new IllegalArgumentException("id mappings require a user namespace");
            }
        }
        if (createUser) {
            unshare.add(0, "user");
        }
        List<String> args = new ArrayList<>();
        args.add("--unshare");
        args.add(unshare.isEmpty() ? "none" : String.join(",", unshare));
        for (String join : joins) {
            args.add("--join");
            args.add(join);
        }
        if (createUser) {
            args.add("--uid-map");
            args.add(IdMap.encode(uidMappings));
            args.add("--gid-map");
            args.add(IdMap.encode(gidMappings));
        }
        return List.copyOf(args);
    }
}
