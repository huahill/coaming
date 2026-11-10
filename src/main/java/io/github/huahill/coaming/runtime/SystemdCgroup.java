package io.github.huahill.coaming.runtime;

public final class SystemdCgroup {
    private SystemdCgroup() {}

    public record Scope(String slice, String unit, String filesystemPath) {}

    public static Scope scope(String cgroupsPath, String id) {
        String path = cgroupsPath == null || cgroupsPath.isBlank() ? "system.slice:coaming:" + id : cgroupsPath;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split(":");
        String slice = parts.length == 1 ? "system.slice" : parts[0];
        if (!slice.endsWith(".slice")) {
            throw new IllegalArgumentException("systemd parent is not a slice: " + slice);
        }
        String unit = parts.length == 1 ? parts[0] : String.join("-", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        if (unit.isBlank()) {
            unit = "coaming-" + id;
        }
        if (!unit.endsWith(".scope") && !unit.endsWith(".service")) {
            unit = unit + ".scope";
        }
        return new Scope(slice, unit, expandSlice(slice) + "/" + unit);
    }

    static String expandSlice(String slice) {
        if (!slice.endsWith(".slice")) {
            throw new IllegalArgumentException("not a slice: " + slice);
        }
        String body = slice.substring(0, slice.length() - ".slice".length());
        StringBuilder path = new StringBuilder();
        StringBuilder prefix = new StringBuilder();
        for (String component : body.split("-", -1)) {
            if (component.isEmpty()) {
                throw new IllegalArgumentException("invalid slice: " + slice);
            }
            if (!prefix.isEmpty()) {
                prefix.append('-');
            }
            prefix.append(component);
            path.append('/').append(prefix).append(".slice");
        }
        return path.toString();
    }
}
