package io.github.huahill.coaming.oci;

import java.util.List;

public record OciMount(String destination, String type, String source, List<String> options) {}
