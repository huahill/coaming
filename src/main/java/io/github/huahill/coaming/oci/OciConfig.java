package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record OciConfig(List<String> args, String cwd, String rootPath, String hostname, List<Namespace> namespaces, boolean namespacesSpecified, List<String> env) {
    public static OciConfig read(Path bundle) throws IOException {
        String text = Files.readString(bundle.resolve("config.json"));
        Json root = Json.parse(text);
        Json process = root.field("process");
        Json rootfs = root.field("root");
        List<Namespace> namespaces = new ArrayList<>();
        boolean namespacesSpecified = false;
        Json linux = root.optional("linux");
        if (linux != null) {
            Json values = linux.optional("namespaces");
            if (values instanceof Json.Arr arr) {
                namespacesSpecified = true;
                for (Json item : arr.items()) {
                    String path = null;
                    Json pathValue = item.optional("path");
                    if (pathValue != null && !pathValue.string().isBlank()) {
                        path = pathValue.string();
                    }
                    namespaces.add(new Namespace(item.field("type").string(), path));
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
        List<String> env = List.of();
        Json envValue = process.optional("env");
        if (envValue != null) {
            env = envValue.strings();
        }
        return new OciConfig(process.field("args").strings(), cwd, rootfs.field("path").string(), hostname, namespaces, namespacesSpecified, env);
    }

    public Path root(Path bundle) {
        Path path = Path.of(rootPath);
        return path.isAbsolute() ? path : bundle.resolve(path);
    }
}
