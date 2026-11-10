package io.github.huahill.coaming.oci;

public record DeviceRule(boolean allow, String type, String access, boolean allDevices) {}
