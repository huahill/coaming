package io.github.huahill.coaming;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ProbeMain {
    private ProbeMain() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv(), System.out));
    }

    static int run(Map<String, String> env, PrintStream out) {
        String rc = env.get("COAMING_PROBE_RC");
        String threads = env.get("COAMING_PROBE_THREADS");
        if (rc == null || threads == null) {
            out.println("probe=missing");
            return 4;
        }
        out.println("probe=" + rc);
        out.println("threads=" + threads);
        out.println("live_threads=" + liveThreads());
        out.println("java=1");
        return Integer.parseInt(rc);
    }

    private static int liveThreads() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("Threads:")) {
                    return Integer.parseInt(line.substring("Threads:".length()).trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
        return -1;
    }
}
