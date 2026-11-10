package io.github.huahill.coaming.oci;

import java.util.List;

public record OciMount(String destination, String type, String source, List<String> options, List<IdMap> uidMappings, List<IdMap> gidMappings) {
    public OciMount(String destination, String type, String source, List<String> options) {
        this(destination, type, source, options, List.of(), List.of());
    }
}
