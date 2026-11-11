package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.util.ArrayList;
import java.util.List;

public record Rlimit(String type, long soft, long hard) {
    public static List<Rlimit> list(Json process) {
        Json values = process.optional("rlimits");
        if (!(values instanceof Json.Arr limits)) {
            return List.of();
        }
        List<Rlimit> parsed = new ArrayList<>();
        for (Json limit : limits.items()) {
            Rlimit parsedLimit = new Rlimit(limit.field("type").string(), limit.field("soft").longValue(), limit.field("hard").longValue());
            parsedLimit.resource();
            parsed.add(parsedLimit);
        }
        return List.copyOf(parsed);
    }

    public int resource() {
        return switch (type) {
            case "RLIMIT_CPU" -> 0;
            case "RLIMIT_FSIZE" -> 1;
            case "RLIMIT_DATA" -> 2;
            case "RLIMIT_STACK" -> 3;
            case "RLIMIT_CORE" -> 4;
            case "RLIMIT_RSS" -> 5;
            case "RLIMIT_NPROC" -> 6;
            case "RLIMIT_NOFILE" -> 7;
            case "RLIMIT_MEMLOCK" -> 8;
            case "RLIMIT_AS" -> 9;
            case "RLIMIT_LOCKS" -> 10;
            case "RLIMIT_SIGPENDING" -> 11;
            case "RLIMIT_MSGQUEUE" -> 12;
            case "RLIMIT_NICE" -> 13;
            case "RLIMIT_RTPRIO" -> 14;
            case "RLIMIT_RTTIME" -> 15;
            default -> throw new IllegalArgumentException("unknown rlimit " + type);
        };
    }

    public static String encoded(List<Rlimit> limits) {
        StringBuilder text = new StringBuilder();
        for (Rlimit limit : limits) {
            if (!text.isEmpty()) {
                text.append(',');
            }
            text.append(limit.resource()).append('=').append(limit.soft()).append(':').append(limit.hard());
        }
        return text.toString();
    }
}
