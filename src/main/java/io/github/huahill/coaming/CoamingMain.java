package io.github.huahill.coaming;

import io.github.huahill.coaming.runtime.CommandLine;
import io.github.huahill.coaming.runtime.ContainerRuntime;
import io.github.huahill.coaming.runtime.Features;
import io.github.huahill.coaming.runtime.RuntimeLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CoamingMain {
    private CoamingMain() {}

    public static void main(String[] args) {
        int code = 1;
        try {
            code = run(args);
        } catch (Exception failure) {
            System.err.println("coaming: " + failure.getMessage());
            if (failure.getCause() != null) {
                System.err.println("coaming cause: " + failure.getCause());
            }
        }
        System.exit(code);
    }

    public static int run(String[] args) throws Exception {
        CommandLine line = CommandLine.parse(args);
        try {
            return dispatch(line);
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            RuntimeLog.error(line.log(), line.logFormat(), message);
            throw failure;
        }
    }

    private static int dispatch(CommandLine line) throws Exception {
        List<String> rest = new ArrayList<>(line.arguments());
        return switch (line.command()) {
            case "version" -> {
                System.out.println(Coaming.versionLine());
                yield 0;
            }
            case "probe" -> ProbeMain.run(System.getenv(), System.out);
            case "features" -> {
                System.out.println(Features.json().strip());
                yield 0;
            }
            case "create" -> {
                String bundle = required(CommandLine.takeOption(rest, "--bundle", "-b"), "--bundle");
                String pidFile = CommandLine.takeOption(rest, "--pid-file");
                acceptUnused(rest);
                yield ContainerRuntime.create(
                        line.root(), Path.of(bundle), only(rest), pidFile == null ? null : Path.of(pidFile), line.systemdCgroup());
            }
            case "init" -> ContainerRuntime.init(
                    Path.of(required(CommandLine.takeOption(rest, "--state"), "--state")),
                    Path.of(required(CommandLine.takeOption(rest, "--bundle"), "--bundle")));
            case "start" -> ContainerRuntime.start(line.root(), only(rest));
            case "state" -> ContainerRuntime.state(line.root(), only(rest));
            case "kill" -> {
                CommandLine.takeFlag(rest, "--all");
                if (rest.size() != 2) {
                    throw new IllegalArgumentException("expected container id and signal");
                }
                yield ContainerRuntime.kill(line.root(), rest.get(0), rest.get(1));
            }
            case "delete" -> {
                boolean force = CommandLine.takeFlag(rest, "--force");
                yield ContainerRuntime.delete(line.root(), only(rest), force);
            }
            case "exec" -> {
                String process = required(CommandLine.takeOption(rest, "--process"), "--process");
                String pidFile = CommandLine.takeOption(rest, "--pid-file");
                boolean detach = CommandLine.takeFlag(rest, "--detach");
                acceptUnused(rest);
                yield ContainerRuntime.exec(
                        line.root(), only(rest), Path.of(process), pidFile == null ? null : Path.of(pidFile), detach);
            }
            case "update" -> {
                String resources = required(CommandLine.takeOption(rest, "--resources"), "--resources");
                yield ContainerRuntime.update(line.root(), only(rest), resources, System.in);
            }
            case "events" -> {
                boolean stats = CommandLine.takeFlag(rest, "--stats");
                String interval = CommandLine.takeOption(rest, "--interval");
                yield ContainerRuntime.events(line.root(), only(rest), interval, stats);
            }
            case "ps" -> {
                CommandLine.takeOption(rest, "--format");
                yield ContainerRuntime.ps(line.root(), only(rest));
            }
            default -> throw new IllegalArgumentException("unknown command " + line.command());
        };
    }

    private static void acceptUnused(List<String> args) {
        CommandLine.takeOption(args, "--console-socket", "--pidfd-socket", "--preserve-fds");
        CommandLine.takeFlag(args, "--no-pivot", "--no-new-keyring", "--detach");
    }

    private static String required(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value;
    }

    private static String only(List<String> args) {
        if (args.size() != 1) {
            throw new IllegalArgumentException("expected one container id");
        }
        return args.get(0);
    }
}
