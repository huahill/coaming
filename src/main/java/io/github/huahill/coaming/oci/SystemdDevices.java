package io.github.huahill.coaming.oci;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SystemdDevices {
    public record Allow(String device, String access) {}

    public record Program(String policy, List<Allow> allows) {}

    private SystemdDevices() {}

    public static Program translate(List<DeviceRule> rules) {
        return translate(rules, null);
    }

    static Program translate(List<DeviceRule> rules, Integer ptsMajor) {
        boolean strict = false;
        List<Allow> allows = new ArrayList<>();
        for (DeviceRule rule : rules) {
            if (isFullDeny(rule)) {
                strict = true;
                allows.clear();
                continue;
            }
            if (isFullAllow(rule)) {
                strict = false;
                allows.clear();
                continue;
            }
            String device = specifier(rule, ptsMajor);
            String access = normalize(rule.access());
            if (!rule.allow()) {
                if (!strict || removes(allows, device, access, ptsMajor)) {
                    throw new IllegalArgumentException("device deny cannot be expressed by systemd: " + device);
                }
                continue;
            }
            if (strict) {
                grant(allows, device, access);
            }
        }
        if (!strict) {
            return new Program(null, List.of());
        }
        if (allows.isEmpty()) {
            // A bare deny-all still leaves the devices a container needs to run.
            grant(allows, "/dev/char/1:3", "rwm");
            grant(allows, "/dev/char/1:5", "rwm");
            grant(allows, "/dev/char/1:7", "rwm");
            grant(allows, "/dev/char/1:8", "rwm");
            grant(allows, "/dev/char/1:9", "rwm");
            grant(allows, "/dev/char/5:0", "rwm");
            grant(allows, "char-pts", "rwm");
        }
        return new Program("strict", List.copyOf(allows));
    }

    private static boolean isFullDeny(DeviceRule rule) {
        return !rule.allow() && rule.allDevices() && "rwm".equals(normalize(rule.access()));
    }

    private static boolean isFullAllow(DeviceRule rule) {
        return rule.allow() && rule.allDevices() && "rwm".equals(normalize(rule.access()));
    }

    private static String specifier(DeviceRule rule, Integer ptsMajor) {
        String kind = switch (rule.type()) {
            case "c" -> "char";
            case "b" -> "block";
            default -> throw new IllegalArgumentException("device type cannot be expressed by systemd: " + rule.type());
        };
        if (rule.major() == null && rule.minor() == null) {
            return kind + "-*";
        }
        if (rule.major() != null && rule.minor() != null) {
            String directory = "char".equals(kind) ? "/dev/char/" : "/dev/block/";
            return directory + rule.major() + ":" + rule.minor();
        }
        if ("char".equals(kind) && rule.minor() == null && rule.major() != null && rule.major() == pts(ptsMajor)) {
            return "char-pts";
        }
        throw new IllegalArgumentException("device wildcard cannot be expressed by systemd: " + kind + " " + rule.major() + ":" + rule.minor());
    }

    private static int pts(Integer supplied) {
        if (supplied != null) {
            return supplied;
        }
        try {
            for (String line : Files.readAllLines(Path.of("/proc/devices"))) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && "pts".equals(parts[1])) {
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("pts device major is not available");
        }
        throw new IllegalArgumentException("pts device major is not available");
    }

    private static String normalize(String access) {
        String raw = access == null ? "" : access;
        StringBuilder normalized = new StringBuilder();
        if (raw.indexOf('r') >= 0) {
            normalized.append('r');
        }
        if (raw.indexOf('w') >= 0) {
            normalized.append('w');
        }
        if (raw.indexOf('m') >= 0) {
            normalized.append('m');
        }
        if (normalized.isEmpty() || raw.chars().anyMatch(letter -> letter != 'r' && letter != 'w' && letter != 'm')) {
            throw new IllegalArgumentException("device access cannot be expressed by systemd: " + raw);
        }
        return normalized.toString();
    }

    private static void grant(List<Allow> allows, String device, String access) {
        for (int i = 0; i < allows.size(); i++) {
            if (allows.get(i).device().equals(device)) {
                allows.set(i, new Allow(device, union(allows.get(i).access(), access)));
                return;
            }
        }
        allows.add(new Allow(device, access));
    }

    private static boolean removes(List<Allow> allows, String device, String access, Integer ptsMajor) {
        Pattern denied = pattern(device, ptsMajor);
        for (Allow allow : allows) {
            if (overlaps(pattern(allow.device(), ptsMajor), denied) && shares(allow.access(), access)) {
                return true;
            }
        }
        return false;
    }

    private static String union(String left, String right) {
        return normalize(left + right);
    }

    private static boolean shares(String left, String right) {
        for (int i = 0; i < left.length(); i++) {
            if (right.indexOf(left.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private record Pattern(String kind, String major, String minor) {}

    private static Pattern pattern(String device, Integer ptsMajor) {
        if (device.startsWith("/dev/char/") || device.startsWith("/dev/block/")) {
            String kind = device.startsWith("/dev/char/") ? "char" : "block";
            String[] parts = device.substring(device.lastIndexOf('/') + 1).split(":", -1);
            return new Pattern(kind, parts[0], parts[1]);
        }
        if ("char-pts".equals(device)) {
            return new Pattern("char", Integer.toString(pts(ptsMajor)), "*");
        }
        int dash = device.indexOf('-');
        String kind = device.substring(0, dash);
        String body = device.substring(dash + 1);
        if (!body.contains(":")) {
            return new Pattern(kind, "*", "*");
        }
        String[] parts = body.split(":", -1);
        return new Pattern(kind, parts[0], parts[1]);
    }

    private static boolean overlaps(Pattern left, Pattern right) {
        return left.kind.equals(right.kind)
                && ("*".equals(left.major) || "*".equals(right.major) || left.major.equals(right.major))
                && ("*".equals(left.minor) || "*".equals(right.minor) || left.minor.equals(right.minor));
    }
}
