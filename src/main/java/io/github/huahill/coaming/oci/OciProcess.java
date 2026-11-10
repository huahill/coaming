package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.util.List;

public record OciProcess(List<String> args, String cwd, List<String> env, LinuxCapabilities capabilities, boolean noNewPrivileges, ProcessUser user, List<Rlimit> rlimits, Integer oomScoreAdj, String apparmorProfile) {
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
        return new OciProcess(
                root.field("args").strings(),
                cwd,
                env,
                LinuxCapabilities.from(root.optional("capabilities")),
                root.optional("noNewPrivileges") != null && root.optional("noNewPrivileges").booleanValue(),
                ProcessUser.from(root),
                Rlimit.list(root),
                root.optional("oomScoreAdj") == null ? null : (int) root.optional("oomScoreAdj").longValue(),
                apparmor(root));
    }

    private static String apparmor(Json process) {
        Json apparmor = process.optional("apparmorProfile");
        if (apparmor == null || apparmor.string().isBlank() || "unconfined".equals(apparmor.string())) {
            return null;
        }
        return apparmor.string();
    }
}
