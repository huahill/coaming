package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.util.ArrayList;
import java.util.List;

public record ProcessUser(int uid, int gid, List<Integer> additionalGids) {
    public static ProcessUser from(Json process) {
        Json user = process.optional("user");
        if (user == null) {
            return null;
        }
        int uid = user.optional("uid") == null ? 0 : (int) user.optional("uid").longValue();
        int gid = user.optional("gid") == null ? 0 : (int) user.optional("gid").longValue();
        List<Integer> groups = new ArrayList<>();
        Json extra = user.optional("additionalGids");
        if (extra instanceof Json.Arr values) {
            for (Json value : values.items()) {
                groups.add((int) value.longValue());
            }
        }
        return new ProcessUser(uid, gid, List.copyOf(groups));
    }

    public String encoded() {
        StringBuilder groups = new StringBuilder();
        for (int i = 0; i < additionalGids.size(); i++) {
            if (i > 0) {
                groups.append(',');
            }
            groups.append(additionalGids.get(i));
        }
        return uid + ":" + gid + ":" + groups;
    }
}
