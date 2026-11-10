package io.github.huahill.coaming.oci;

public record DeviceNode(String path, String type, long major, long minor, int fileMode, int uid, int gid) {}
