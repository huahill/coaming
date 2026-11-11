package io.github.huahill.coaming.runtime;

final class ApparmorProfiles {
    private ApparmorProfiles() {}

    static void requireLoaded(String profile, String listing) {
        if (profile == null || profile.isBlank() || "unconfined".equals(profile)) {
            return;
        }
        for (String line : listing.split("\\R")) {
            String name = line.trim();
            int space = name.lastIndexOf(' ');
            if (space > 0 && name.substring(space + 1).startsWith("(")) {
                name = name.substring(0, space);
            }
            if (name.equals(profile)) {
                return;
            }
        }
        throw new IllegalStateException("apparmor profile " + profile + " is not loaded");
    }
}
