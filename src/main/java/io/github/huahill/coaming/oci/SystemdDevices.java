package io.github.huahill.coaming.oci;

import java.util.ArrayList;
import java.util.List;

public final class SystemdDevices {
    public record Allow(String device, String access) {}

    public record Program(String policy, List<Allow> allows) {}

    private SystemdDevices() {}

    public static Program translate(List<DeviceRule> rules) {
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
            String device = specifier(rule);
            String access = normalize(rule.access());
            if (!rule.allow()) {
                if (!strict || removes(allows, device, access)) {
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
        return new Program("strict", List.copyOf(allows));
    }

    private static boolean isFullDeny(DeviceRule rule) {
        return !rule.allow() && rule.allDevices() && "rwm".equals(normalize(rule.access()));
    }

    private static boolean isFullAllow(DeviceRule rule) {
        return rule.allow() && rule.allDevices() && "rwm".equals(normalize(rule.access()));
    }

    private static String specifier(DeviceRule rule) {
        String kind = switch (rule.type()) {
            case "c" -> "char";
            case "b" -> "block";
            default -> throw new IllegalArgumentException("device type cannot be expressed by systemd: " + rule.type());
        };
        String major = rule.major() == null ? "*" : Integer.toString(rule.major());
        String minor = rule.minor() == null ? "*" : Integer.toString(rule.minor());
        if ("*".equals(major) && "*".equals(minor)) {
            return kind + "-*";
        }
        return kind + "-" + major + ":" + minor;
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

    private static boolean removes(List<Allow> allows, String device, String access) {
        Pattern denied = pattern(device);
        for (Allow allow : allows) {
            if (overlaps(pattern(allow.device()), denied) && shares(allow.access(), access)) {
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

    private static Pattern pattern(String device) {
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
