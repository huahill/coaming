package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.util.List;

public record OciProcess(List<String> args, String cwd, List<String> env) {
    public static OciProcess parse(String text) {
        Json root = Json.parse(text);
        String cwd = "/";
        Json cwdValue = root.optional("cwd");
        if (cwdValue != null) {
            cwd = cwdValue.string();
        }
        List<String> env = List.of();
        Json envValue = root.optional("env");
        if (envValue != null) {
            env = envValue.strings();
        }
        return new OciProcess(root.field("args").strings(), cwd, env);
    }
}
