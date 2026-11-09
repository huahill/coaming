package io.github.huahill.coaming.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record CommandLine(Path root, Path log, String logFormat, boolean systemdCgroup, String command, List<String> arguments) {
    public static CommandLine parse(String[] args) {
        List<String> rest = new ArrayList<>(List.of(args));
        Path root = Path.of("/run/coaming");
        Path log = null;
        String logFormat = "text";
        boolean systemdCgroup = false;
        while (!rest.isEmpty() && rest.getFirst().startsWith("-")) {
            String token = rest.removeFirst();
            String option = token;
            String inline = null;
            int equals = token.indexOf('=');
            if (equals > 0) {
                option = token.substring(0, equals);
                inline = token.substring(equals + 1);
            }
            switch (option) {
                case "--root" -> root = Path.of(value(rest, option, inline));
                case "--log" -> log = Path.of(value(rest, option, inline));
                case "--log-format" -> logFormat = value(rest, option, inline);
                case "--systemd-cgroup" -> {
                    rejectInline(option, inline);
                    systemdCgroup = true;
                }
                case "--debug" -> rejectInline(option, inline);
                case "--rootless" -> value(rest, option, inline);
                default -> throw new IllegalArgumentException("unknown option " + token);
            }
        }
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("missing command");
        }
        return new CommandLine(root, log, logFormat, systemdCgroup, rest.removeFirst(), rest);
    }

    public static String takeOption(List<String> args, String... names) {
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            for (String name : names) {
                if (token.equals(name) && i + 1 < args.size()) {
                    String value = args.get(i + 1);
                    args.remove(i);
                    args.remove(i);
                    return value;
                }
                String prefix = name + "=";
                if (token.startsWith(prefix)) {
                    args.remove(i);
                    return token.substring(prefix.length());
                }
            }
        }
        return null;
    }

    public static boolean takeFlag(List<String> args, String... names) {
        for (int i = 0; i < args.size(); i++) {
            for (String name : names) {
                if (args.get(i).equals(name)) {
                    args.remove(i);
                    return true;
                }
            }
        }
        return false;
    }

    private static String value(List<String> args, String option, String inline) {
        if (inline != null) {
            return inline;
        }
        return requireValue(args, option);
    }

    private static void rejectInline(String option, String inline) {
        if (inline != null) {
            throw new IllegalArgumentException("unexpected value for " + option);
        }
    }

    private static String requireValue(List<String> args, String option) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args.removeFirst();
    }
}
