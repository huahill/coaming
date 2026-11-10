package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;

public record LinuxCapabilities(long permitted, long inheritable, long effective, long bounding, long ambient) {
    private static final String[] NAMES = {
            "CAP_CHOWN",
            "CAP_DAC_OVERRIDE",
            "CAP_DAC_READ_SEARCH",
            "CAP_FOWNER",
            "CAP_FSETID",
            "CAP_KILL",
            "CAP_SETGID",
            "CAP_SETUID",
            "CAP_SETPCAP",
            "CAP_LINUX_IMMUTABLE",
            "CAP_NET_BIND_SERVICE",
            "CAP_NET_BROADCAST",
            "CAP_NET_ADMIN",
            "CAP_NET_RAW",
            "CAP_IPC_LOCK",
            "CAP_IPC_OWNER",
            "CAP_SYS_MODULE",
            "CAP_SYS_RAWIO",
            "CAP_SYS_CHROOT",
            "CAP_SYS_PTRACE",
            "CAP_SYS_PACCT",
            "CAP_SYS_ADMIN",
            "CAP_SYS_BOOT",
            "CAP_SYS_NICE",
            "CAP_SYS_RESOURCE",
            "CAP_SYS_TIME",
            "CAP_SYS_TTY_CONFIG",
            "CAP_MKNOD",
            "CAP_LEASE",
            "CAP_AUDIT_WRITE",
            "CAP_AUDIT_CONTROL",
            "CAP_SETFCAP",
            "CAP_MAC_OVERRIDE",
            "CAP_MAC_ADMIN",
            "CAP_SYSLOG",
            "CAP_WAKE_ALARM",
            "CAP_BLOCK_SUSPEND",
            "CAP_AUDIT_READ",
            "CAP_PERFMON",
            "CAP_BPF",
            "CAP_CHECKPOINT_RESTORE"
    };

    public static LinuxCapabilities parse(String json) {
        if (json == null) {
            return null;
        }
        return from(Json.parse(json));
    }

    public static LinuxCapabilities from(Json capabilities) {
        if (capabilities == null) {
            return null;
        }
        LinuxCapabilities parsed = new LinuxCapabilities(
                mask(capabilities, "permitted"),
                mask(capabilities, "inheritable"),
                mask(capabilities, "effective"),
                mask(capabilities, "bounding"),
                mask(capabilities, "ambient"));
        if ((parsed.ambient & ~parsed.permitted) != 0 || (parsed.ambient & ~parsed.inheritable) != 0) {
            throw new IllegalArgumentException("ambient capabilities must also be permitted and inheritable");
        }
        return parsed;
    }

    public String encoded() {
        return Long.toUnsignedString(permitted, 16) + ","
                + Long.toUnsignedString(inheritable, 16) + ","
                + Long.toUnsignedString(effective, 16) + ","
                + Long.toUnsignedString(bounding, 16) + ","
                + Long.toUnsignedString(ambient, 16);
    }

    private static long mask(Json capabilities, String name) {
        Json value = capabilities.optional(name);
        if (value == null) {
            return 0;
        }
        long bits = 0;
        for (String capability : value.strings()) {
            int index = index(capability);
            bits |= 1L << index;
        }
        return bits;
    }

    private static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown capability " + name);
    }
}
