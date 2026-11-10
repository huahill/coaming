package io.github.huahill.coaming.oci;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public final class BindFds {
    private BindFds() {}

    public static Map<String, Integer> descriptors(Function<String, String> env) {
        String countText = env.apply("COAMING_BIND_COUNT");
        if (countText == null || countText.isBlank()) {
            return Map.of();
        }
        int count = Integer.parseInt(countText);
        Map<String, Integer> fds = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String destination = env.apply("COAMING_BIND_DST_" + i);
            String descriptor = env.apply("COAMING_BIND_FD_" + i);
            if (destination == null || descriptor == null) {
                throw new IllegalStateException("bind mount was not opened");
            }
            fds.put(destination, Integer.parseInt(descriptor));
        }
        return Map.copyOf(fds);
    }

    public static String source(String destination, String configured, Map<String, Integer> descriptors) {
        Integer descriptor = descriptors.get(destination);
        if (descriptor == null) {
            return configured;
        }
        return "/proc/self/fd/" + descriptor;
    }
}
