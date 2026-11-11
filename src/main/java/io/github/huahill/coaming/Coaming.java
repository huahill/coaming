package io.github.huahill.coaming;

public final class Coaming {
    public static final String NAME = "coaming";
    public static final String GROUP_ID = "io.github.huahill";
    public static final String VERSION = "0.1.0";

    private Coaming() {}

    public static String version() {
        return VERSION;
    }

    public static String versionLine() {
        return "coaming version " + version();
    }
}
