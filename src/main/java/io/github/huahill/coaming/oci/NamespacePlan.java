package io.github.huahill.coaming.oci;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NamespacePlan {
    private static final List<String> DEFAULTS = List.of("pid", "mount", "uts", "ipc", "network");
    private static final Set<String> SUPPORTED = Set.of("pid", "mount", "uts", "ipc", "network", "cgroup");

    private NamespacePlan() {}

    public static List<String> arguments(List<Namespace> namespaces, boolean specified) {
        if (!specified) {
            return List.of("--unshare", String.join(",", DEFAULTS));
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> unshare = new ArrayList<>();
        List<String> joins = new ArrayList<>();
        for (Namespace namespace : namespaces) {
            String type = namespace.type();
            if (!seen.add(type)) {
                throw new IllegalArgumentException("duplicate namespace " + type);
            }
            if ("user".equals(type)) {
                throw new IllegalArgumentException("user namespace is not implemented");
            }
            if (!SUPPORTED.contains(type)) {
                throw new IllegalArgumentException("unsupported namespace " + type);
            }
            String path = namespace.path();
            if (path == null || path.isBlank()) {
                unshare.add(type);
            } else {
                if (!path.startsWith("/")) {
                    throw new IllegalArgumentException("namespace path must be absolute: " + path);
                }
                joins.add(type + "=" + path);
            }
        }
        List<String> args = new ArrayList<>();
        args.add("--unshare");
        args.add(unshare.isEmpty() ? "none" : String.join(",", unshare));
        for (String join : joins) {
            args.add("--join");
            args.add(join);
        }
        return List.copyOf(args);
    }
}
