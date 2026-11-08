package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record OciConfig(List<String> args, String cwd, String rootPath, String hostname, List<String> namespaces) {
    public static OciConfig read(Path bundle) throws IOException {
        String text = Files.readString(bundle.resolve("config.json"));
        Json root = Json.parse(text);
        Json process = root.field("process");
        Json rootfs = root.field("root");
        List<String> namespaces = new ArrayList<>();
        Json linux = root.optional("linux");
        if (linux != null) {
            Json values = linux.optional("namespaces");
            if (values instanceof Json.Arr arr) {
                for (Json item : arr.items()) {
                    namespaces.add(item.field("type").string());
                }
            }
        }
        String cwd = "/";
        Json cwdValue = process.optional("cwd");
        if (cwdValue != null) {
            cwd = cwdValue.string();
        }
        String hostname = "coaming";
        Json hostnameValue = root.optional("hostname");
        if (hostnameValue != null) {
            hostname = hostnameValue.string();
        }
        return new OciConfig(process.field("args").strings(), cwd, rootfs.field("path").string(), hostname, namespaces);
    }

    public Path root(Path bundle) {
        Path path = Path.of(rootPath);
        return path.isAbsolute() ? path : bundle.resolve(path);
    }
}
