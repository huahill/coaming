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

    static boolean limitsHold(Path cgroup, Json resources) throws IOException {
        String max = expectedMemoryMax(resources);
        if (max != null && !max.equals(trim(cgroup.resolve("memory.max")))) {
            return false;
        }
        String swap = expectedSwapMax(resources);
        Path swapFile = cgroup.resolve("memory.swap.max");
        return swap == null || !Files.exists(swapFile) || swap.equals(trim(swapFile));
    }

    static String expectedMemoryMax(Json resources) {
        String unified = unifiedValue(resources, "memory.max");
        if (unified != null) {
            return unified;
        }
        Long limit = memoryLimit(resources);
        if (limit == null) {
            return null;
        }
        return limit < 0 ? "max" : Long.toString(limit);
    }

    static String expectedSwapMax(Json resources) {
        String unified = unifiedValue(resources, "memory.swap.max");
        if (unified != null) {
            return unified;
        }
        Long limit = memoryLimit(resources);
        if (limit == null || limit <= 0) {
            return null;
        }
        Json memory = resources.optional("memory");
        Json swap = memory.optional("swap");
        if (swap == null) {
            return "0";
        }
        if (swap.longValue() < 0) {
            return "max";
        }
        return Long.toString(swap.longValue() - limit);
    }

    private static Long memoryLimit(Json resources) {
        Json memory = resources.optional("memory");
        if (memory == null || memory.optional("limit") == null) {
            return null;
        }
        return memory.optional("limit").longValue();
    }

    private static String unifiedValue(Json resources, String key) {
        Json unified = resources.optional("unified");
        if (unified instanceof Json.Obj object && object.fields().containsKey(key)) {
            return object.fields().get(key).string();
        }
        return null;
    }

    private static String trim(Path file) throws IOException {
        return Files.readString(file).trim();
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
            Json quotaValue = cpu.optional("quota");
            Json periodValue = cpu.optional("period");
            long quota = quotaValue == null ? 0 : quotaValue.longValue();
            long period = periodValue == null ? 0 : periodValue.longValue();
            // cri-o writes zeros when the pod did not ask for a CPU limit. cpu.max rejects "0 0".
            if (quota != 0 || period != 0) {
                long effectivePeriod = period > 0 ? period : 100_000;
                String max = quota > 0 ? quota + " " + effectivePeriod : "max " + effectivePeriod;
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
        try {
            Files.writeString(file, value);
        } catch (IOException failure) {
            throw new IOException(file + " = " + value + ": " + failure.getMessage(), failure);
        }
    }
}
