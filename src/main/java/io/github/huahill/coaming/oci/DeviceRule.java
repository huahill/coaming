package io.github.huahill.coaming.oci;

public record DeviceRule(boolean allow, String type, String access, boolean allDevices, Integer major, Integer minor) {
    public DeviceRule(boolean allow, String type, String access, boolean allDevices) {
        this(allow, type, access, allDevices, null, null);
    }
}
