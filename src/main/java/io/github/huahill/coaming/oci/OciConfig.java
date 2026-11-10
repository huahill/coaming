package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record OciConfig(List<String> args, String cwd, String rootPath, String hostname, List<Namespace> namespaces, boolean namespacesSpecified, List<String> env, List<OciMount> mounts) {
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
        List<OciMount> mounts = new ArrayList<>();
        Json mountValues = root.optional("mounts");
        if (mountValues instanceof Json.Arr arr) {
            for (Json item : arr.items()) {
                String mountType = "";
                Json typeValue = item.optional("type");
                if (typeValue != null) {
                    mountType = typeValue.string();
                }
                String source = "";
                Json sourceValue = item.optional("source");
                if (sourceValue != null) {
                    source = sourceValue.string();
                }
                List<String> options = List.of();
                Json optionValue = item.optional("options");
                if (optionValue != null) {
                    options = optionValue.strings();
                }
                mounts.add(new OciMount(item.field("destination").string(), mountType, source, options));
            }
        }
        return new OciConfig(process.field("args").strings(), cwd, rootfs.field("path").string(), hostname, namespaces, namespacesSpecified, env, List.copyOf(mounts));
    }

    public Path root(Path bundle) {
        Path path = Path.of(rootPath);
        return path.isAbsolute() ? path : bundle.resolve(path);
    }
}
