package io.github.huahill.coaming.runtime;

import io.github.huahill.coaming.json.Json;
import java.io.IOException;
import java.nio.file.Files;
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
                Files.writeString(cgroup.resolve("memory.max"), value < 0 ? "max" : Long.toString(value));
            }
        }
        Json cpu = resources.optional("cpu");
        if (cpu != null) {
            Json shares = cpu.optional("shares");
            if (shares != null && shares.longValue() > 0) {
                Files.writeString(cgroup.resolve("cpu.weight"), Integer.toString(weightFromShares(shares.longValue())));
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
                Files.writeString(cgroup.resolve("cpu.max"), max);
            }
        }
    }
}
