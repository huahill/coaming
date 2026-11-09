package io.github.huahill.coaming.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class RuntimeLog {
    private RuntimeLog() {}

    public static void error(Path log, String format, String message) {
        if (log == null) {
            return;
        }
        String line = "json".equals(format)
                ? "{\"level\":\"error\",\"msg\":\"" + escape(message) + "\"}\n"
                : message + "\n";
        try {
            Files.writeString(log, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // A logging failure must not hide the original command failure.
        }
    }

    private static String escape(String message) {
        return message.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
