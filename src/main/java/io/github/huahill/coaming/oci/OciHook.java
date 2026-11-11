package io.github.huahill.coaming.oci;

import java.util.List;

public record OciHook(String path, List<String> args, List<String> env, Integer timeout) {}
