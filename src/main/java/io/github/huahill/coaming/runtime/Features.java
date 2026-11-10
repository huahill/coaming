package io.github.huahill.coaming.runtime;

public final class Features {
    private Features() {}

    public static String json() {
        return """
                {"ociVersionMin":"1.0.0","ociVersionMax":"1.2.0","linux":{"namespaces":["ipc","mount","network","pid","uts"],"cgroup":{"v1":false,"v2":true,"systemd":true,"systemdUser":false}}}
                """;
    }
}
