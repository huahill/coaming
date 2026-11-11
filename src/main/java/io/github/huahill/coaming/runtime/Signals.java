package io.github.huahill.coaming.runtime;

import java.util.Locale;

public final class Signals {
    private Signals() {}

    public static int number(String signal) {
        String name = signal.toUpperCase(Locale.ROOT);
        if (name.startsWith("SIG")) {
            name = name.substring(3);
        }
        return switch (name) {
            case "HUP" -> 1;
            case "INT" -> 2;
            case "QUIT" -> 3;
            case "ILL" -> 4;
            case "TRAP" -> 5;
            case "ABRT" -> 6;
            case "BUS" -> 7;
            case "FPE" -> 8;
            case "KILL" -> 9;
            case "USR1" -> 10;
            case "SEGV" -> 11;
            case "USR2" -> 12;
            case "PIPE" -> 13;
            case "ALRM" -> 14;
            case "TERM" -> 15;
            case "CHLD" -> 17;
            case "CONT" -> 18;
            case "STOP" -> 19;
            case "TSTP" -> 20;
            case "TTIN" -> 21;
            case "TTOU" -> 22;
            case "URG" -> 23;
            case "XCPU" -> 24;
            case "XFSZ" -> 25;
            case "VTALRM" -> 26;
            case "PROF" -> 27;
            case "WINCH" -> 28;
            case "IO" -> 29;
            case "PWR" -> 30;
            case "SYS" -> 31;
            default -> Integer.parseInt(signal);
        };
    }
}
