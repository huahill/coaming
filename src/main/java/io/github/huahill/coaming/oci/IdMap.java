package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.util.ArrayList;
import java.util.List;

public record IdMap(long containerID, long hostID, long size) {
    public IdMap {
        if (containerID < 0 || hostID < 0 || size <= 0) {
            throw new IllegalArgumentException("id map is outside the valid range");
        }
        if (containerID > Long.MAX_VALUE - size || hostID > Long.MAX_VALUE - size) {
            throw new IllegalArgumentException("id map overflows");
        }
    }

    public static List<IdMap> list(Json linux, String name) {
        if (linux == null || !(linux.optional(name) instanceof Json.Arr values)) {
            return List.of();
        }
        List<IdMap> maps = new ArrayList<>();
        for (Json item : values.items()) {
            maps.add(new IdMap(item.field("containerID").longValue(), item.field("hostID").longValue(), item.field("size").longValue()));
        }
        return List.copyOf(maps);
    }

    public static String encode(List<IdMap> maps) {
        StringBuilder encoded = new StringBuilder();
        for (IdMap map : maps) {
            if (!encoded.isEmpty()) {
                encoded.append(',');
            }
            encoded.append(map.containerID).append(':').append(map.hostID).append(':').append(map.size);
        }
        return encoded.toString();
    }

    public static long hostIdFor(List<IdMap> maps, long containerId) {
        for (IdMap map : maps) {
            long offset = containerId - map.containerID();
            if (offset >= 0 && offset < map.size()) {
                return map.hostID() + offset;
            }
        }
        return -1;
    }

    public static int bits(long id) {
        if (id < 0 || id > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("id mapping does not fit chown");
        }
        return (int) id;
    }
}
