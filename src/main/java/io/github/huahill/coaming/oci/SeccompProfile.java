package io.github.huahill.coaming.oci;

import io.github.huahill.coaming.json.Json;
import java.util.ArrayList;
import java.util.List;

public record SeccompProfile(int defaultAction, List<String> architectures, List<Syscall> syscalls) {
    public record Syscall(List<String> names, int action, List<Argument> args) {}

    public record Argument(int index, int operation, long value, long valueTwo) {}

    public static boolean ignoreAddResult(int rc) {
        return rc == -13 || rc == -14 || rc == -22 || rc == -38;
    }

    public static int action(String name, Integer errno) {
        return switch (name) {
            case "SCMP_ACT_ALLOW" -> 0x7fff0000;
            case "SCMP_ACT_ERRNO" -> 0x00050000 | (errno == null ? 1 : errno & 0xffff);
            case "SCMP_ACT_KILL", "SCMP_ACT_KILL_THREAD" -> 0;
            case "SCMP_ACT_KILL_PROCESS" -> 0x80000000;
            case "SCMP_ACT_TRAP" -> 0x00030000;
            case "SCMP_ACT_LOG" -> 0x7ffc0000;
            case "SCMP_ACT_NOTIFY" -> 0x7fc00000;
            default -> throw new IllegalArgumentException("unsupported seccomp action " + name);
        };
    }

    public static int operation(String name) {
        return switch (name) {
            case "SCMP_CMP_NE" -> 1;
            case "SCMP_CMP_LT" -> 2;
            case "SCMP_CMP_LE" -> 3;
            case "SCMP_CMP_EQ" -> 4;
            case "SCMP_CMP_GE" -> 5;
            case "SCMP_CMP_GT" -> 6;
            case "SCMP_CMP_MASKED_EQ" -> 7;
            default -> throw new IllegalArgumentException("unsupported seccomp operator " + name);
        };
    }

    public static SeccompProfile parse(Json node) {
        Integer errno = number(node.optional("errnoRet"));
        List<String> architectures = List.of();
        Json arch = node.optional("architectures");
        if (arch != null) {
            architectures = arch.strings();
        }
        List<Syscall> syscalls = new ArrayList<>();
        Json calls = node.optional("syscalls");
        if (calls instanceof Json.Arr arr) {
            for (Json item : arr.items()) {
                Integer callErrno = number(item.optional("errnoRet"));
                if (callErrno == null) {
                    callErrno = errno;
                }
                List<Argument> args = new ArrayList<>();
                Json argValues = item.optional("args");
                if (argValues instanceof Json.Arr argArr) {
                    for (Json arg : argArr.items()) {
                        long valueTwo = 0;
                        Json second = arg.optional("valueTwo");
                        if (second != null) {
                            valueTwo = second.longValue();
                        }
                        args.add(new Argument((int) arg.field("index").longValue(), operation(arg.field("op").string()), arg.field("value").longValue(), valueTwo));
                    }
                }
                syscalls.add(new Syscall(item.field("names").strings(), action(item.field("action").string(), callErrno), List.copyOf(args)));
            }
        }
        return new SeccompProfile(action(node.field("defaultAction").string(), errno), architectures, List.copyOf(syscalls));
    }

    private static Integer number(Json node) {
        return node == null ? null : (int) node.longValue();
    }
}
