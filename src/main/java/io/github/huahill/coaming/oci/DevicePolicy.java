package io.github.huahill.coaming.oci;

import java.util.List;

public final class DevicePolicy {
    private DevicePolicy() {}

    public static void requireEnforceable(List<DeviceRule> rules) {
        for (DeviceRule rule : rules) {
            boolean allowEverything = rule.allow() && rule.allDevices()
                    && rule.access().contains("r") && rule.access().contains("w") && rule.access().contains("m");
            if (!allowEverything) {
                throw new IllegalArgumentException("device cgroup policy requires systemd and is not implemented");
            }
        }
    }
}
