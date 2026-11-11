package io.github.huahill.coaming.runtime;

final class UtsHostname {
    private UtsHostname() {}

    static String nameToSet(String configured, boolean hasUtsNamespace, String hostNamespace, String currentNamespace) {
        if (configured == null || configured.isBlank() || !hasUtsNamespace) {
            return null;
        }
        if (hostNamespace != null && !hostNamespace.isBlank() && hostNamespace.equals(currentNamespace)) {
            return null;
        }
        return configured;
    }
}
