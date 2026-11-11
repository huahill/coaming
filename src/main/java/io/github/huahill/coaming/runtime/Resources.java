package io.github.huahill.coaming.runtime;

import io.github.huahill.coaming.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

final class Resources {
    private Resources() {}

    static int weightFromShares(long shares) {
        long weight = 1 + (shares - 2) * 9999 / 262142;
        if (weight < 1) {
            return 1;
        }
        if (weight > 10000) {
            return 10000;
        }
        return (int) weight;
    }

    static void apply(Path cgroup, Json resources) throws IOException {
        Json memory = resources.optional("memory");
        if (memory != null) {
            Json limit = memory.optional("limit");
            if (limit != null) {
                long value = limit.longValue();
                write(cgroup.resolve("memory.max"), value < 0 ? "max" : Long.toString(value));
                if (value > 0) {
                    writeSwap(cgroup, memory, resources.optional("unified"), value);
                }
            }
        }
        Json pids = resources.optional("pids");
        if (pids != null && pids.optional("limit") != null) {
            long limit = pids.optional("limit").longValue();
            write(cgroup.resolve("pids.max"), limit < 0 ? "max" : Long.toString(limit));
        }
        Json cpu = resources.optional("cpu");
        if (cpu != null) {
            Json shares = cpu.optional("shares");
            if (shares != null && shares.longValue() > 0) {
                write(cgroup.resolve("cpu.weight"), Integer.toString(weightFromShares(shares.longValue())));
            }
            Json quota = cpu.optional("quota");
            if (quota != null) {
                long period = 100000;
                Json periodValue = cpu.optional("period");
                if (periodValue != null) {
                    period = periodValue.longValue();
                }
                long value = quota.longValue();
                String max = value < 0 ? "max " + period : value + " " + period;
                write(cgroup.resolve("cpu.max"), max);
            }
        }
        Json unified = resources.optional("unified");
        if (unified instanceof Json.Obj object) {
            for (var entry : object.fields().entrySet()) {
                String key = entry.getKey();
                if (!key.matches("[A-Za-z0-9_.]+")) {
                    throw new IllegalArgumentException("unsupported cgroup key: " + key);
                }
                write(cgroup.resolve(key), entry.getValue().string());
            }
        }
    }

    private static void writeSwap(Path cgroup, Json memory, Json unified, long limit) throws IOException {
        if (unified instanceof Json.Obj object && object.fields().containsKey("memory.swap.max")) {
            return;
        }
        Json swap = memory.optional("swap");
        String value;
        if (swap == null) {
            // An unset swap must not turn the memory limit into memory plus host swap.
            value = "0";
        } else if (swap.longValue() < 0) {
            value = "max";
        } else {
            long swapOnly = swap.longValue() - limit;
            if (swapOnly < 0) {
                throw new IllegalArgumentException("memory swap is smaller than the memory limit");
            }
            value = Long.toString(swapOnly);
        }
        write(cgroup.resolve("memory.swap.max"), value);
    }

    private static void write(Path file, String value) throws IOException {
        if (!Files.exists(file)) {
            throw new NoSuchFileException(file.toString());
        }
        Files.writeString(file, value);
    }
}
