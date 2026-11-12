package io.github.huahill.coaming.runtime;

import io.github.huahill.coaming.oci.OciHook;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class HookRunner {
    @FunctionalInterface
    public interface Processes {
        int run(OciHook hook, Map<String, String> state) throws IOException;
    }

    private final Processes processes;

    public HookRunner(Processes processes) {
        this.processes = processes;
    }

    public void run(List<OciHook> hooks, Map<String, String> state) {
        for (OciHook hook : hooks) {
            int code;
            try {
                code = processes.run(hook, state);
            } catch (IOException failure) {
                throw new IllegalStateException("hook " + hook.path() + " failed: -1: " + failure.getMessage(), failure);
            }
            if (code != 0) {
                throw new IllegalStateException("hook " + hook.path() + " failed: " + code);
            }
        }
    }

    public static int spawn(OciHook hook, Map<String, String> state) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(commandFor(hook));
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (hook.env() != null && !hook.env().isEmpty()) {
            builder.environment().clear();
            for (String item : hook.env()) {
                int split = item.indexOf('=');
                if (split <= 0) {
                    throw new IOException("hook env " + item);
                }
                builder.environment().put(item.substring(0, split), item.substring(split + 1));
            }
        }
        Process process = builder.start();
        OutputStream stdin = process.getOutputStream();
        try {
            stdin.write(stateJson(state).getBytes(StandardCharsets.UTF_8));
            stdin.close();
        } catch (IOException ignored) {
            // A hook may exit before reading stdin. Its status is the result.
            try {
                stdin.close();
            } catch (IOException ignoredClose) {
                // The process has already closed the pipe.
            }
        }
        try {
            if (hook.timeout() == null) {
                return process.waitFor();
            }
            if (!process.waitFor(hook.timeout(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("timed out");
            }
            return process.exitValue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted", failure);
        }
    }

    static List<String> commandFor(OciHook hook) {
        if (hook.args() == null || hook.args().isEmpty()) {
            return List.of(hook.path());
        }
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/perl");
        command.add("-e");
        command.add("exec {$ARGV[0]} $ARGV[1], @ARGV[2..$#ARGV]");
        command.add(hook.path());
        command.add(hook.args().get(0));
        if (hook.args().size() > 1) {
            command.addAll(hook.args().subList(1, hook.args().size()));
        }
        return command;
    }

    private static String stateJson(Map<String, String> state) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : state.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append('"').append(':');
            if ("pid".equals(entry.getKey()) || "annotations".equals(entry.getKey())) {
                json.append(entry.getValue());
            } else {
                json.append('"').append(escape(entry.getValue())).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
