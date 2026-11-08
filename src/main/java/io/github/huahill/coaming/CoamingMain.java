package io.github.huahill.coaming;

import io.github.huahill.coaming.runtime.ContainerRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CoamingMain {
    private CoamingMain() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception failure) {
            System.err.println("coaming: " + failure.getMessage());
            if (failure.getCause() != null) {
                System.err.println("coaming cause: " + failure.getCause());
            }
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        List<String> rest = new ArrayList<>(List.of(args));
        Path root = Path.of("/run/coaming");
        while (!rest.isEmpty() && rest.get(0).startsWith("--")) {
            if (rest.get(0).equals("--root") && rest.size() >= 2) {
                root = Path.of(rest.get(1));
                rest.remove(0);
                rest.remove(0);
            } else {
                throw new IllegalArgumentException("unknown option " + rest.get(0));
            }
        }
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("missing command");
        }
        String command = rest.remove(0);
        return switch (command) {
            case "probe" -> ProbeMain.run(System.getenv(), System.out);
            case "create" -> ContainerRuntime.create(root, Path.of(option(rest, "--bundle")), rest.get(rest.size() - 1));
            case "init" -> ContainerRuntime.init(Path.of(option(rest, "--state")), Path.of(option(rest, "--bundle")));
            case "start" -> ContainerRuntime.start(root, only(rest));
            case "state" -> ContainerRuntime.state(root, only(rest));
            case "kill" -> ContainerRuntime.kill(root, rest.get(0), rest.get(1));
            case "delete" -> ContainerRuntime.delete(root, only(rest));
            default -> throw new IllegalArgumentException("unknown command " + command);
        };
    }

    private static String option(List<String> args, String name) {
        for (int i = 0; i < args.size() - 1; i++) {
            if (args.get(i).equals(name)) {
                return args.get(i + 1);
            }
        }
        throw new IllegalArgumentException("missing " + name);
    }

    private static String only(List<String> args) {
        if (args.size() != 1) {
            throw new IllegalArgumentException("expected one container id");
        }
        return args.get(0);
    }
}
