package io.github.huahill.coaming.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import io.github.huahill.coaming.oci.SystemdDevices;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class SystemdBus {
    private static final Linker LINKER = Linker.nativeLinker();
    private static SymbolLookup library;
    private static MethodHandle openSystem;
    private static MethodHandle newCall;
    private static MethodHandle appendBasic;
    private static MethodHandle openContainer;
    private static MethodHandle closeContainer;
    private static MethodHandle call;
    private static MethodHandle unrefMessage;
    private static MethodHandle unrefBus;
    private static MethodHandle freeError;

    private SystemdBus() {}

    static void startScope(String unit, String slice, int pid, SystemdDevices.Program devices) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bus = open(arena);
            MemorySegment message = method(arena, bus, "StartTransientUnit");
            appendString(arena, message, unit);
            appendString(arena, message, "fail");
            open(arena, message, 'a', "(sv)");
            property(arena, message, "Description", 's', arena.allocateFrom("coaming " + unit));
            property(arena, message, "Slice", 's', arena.allocateFrom(slice));
            property(arena, message, "Delegate", 'b', pointerToInt(arena, 1));
            if (devices.policy() != null) {
                property(arena, message, "DevicePolicy", 's', arena.allocateFrom(devices.policy()));
                deviceAllow(arena, message, devices.allows());
            }
            open(arena, message, 'r', "sv");
            appendString(arena, message, "PIDs");
            open(arena, message, 'v', "au");
            open(arena, message, 'a', "u");
            appendBasic(message, 'u', pointerToInt(arena, pid));
            close(message);
            close(message);
            close(message);
            close(message);
            open(arena, message, 'a', "(sa(sv))");
            close(message);
            invoke(arena, bus, message, "start " + unit);
            unref(bus, message);
        }
    }

    static void stop(String unit) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bus = open(arena);
            MemorySegment message = method(arena, bus, "StopUnit");
            appendString(arena, message, unit);
            appendString(arena, message, "fail");
            invoke(arena, bus, message, "stop " + unit);
            unref(bus, message);
        }
    }

    private static MemorySegment open(Arena arena) {
        init();
        MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
        check(invokeInt(openSystem, slot), "sd_bus_open_system");
        return slot.get(ValueLayout.ADDRESS, 0);
    }

    private static MemorySegment method(Arena arena, MemorySegment bus, String member) {
        MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
        check(invokeInt(newCall, bus, slot, arena.allocateFrom("org.freedesktop.systemd1"), arena.allocateFrom("/org/freedesktop/systemd1"), arena.allocateFrom("org.freedesktop.systemd1.Manager"), arena.allocateFrom(member)), member);
        return slot.get(ValueLayout.ADDRESS, 0);
    }

    private static void property(Arena arena, MemorySegment message, String name, char type, MemorySegment value) {
        open(arena, message, 'r', "sv");
        appendString(arena, message, name);
        open(arena, message, 'v', String.valueOf(type));
        appendBasic(message, type, value);
        close(message);
        close(message);
    }

    private static void deviceAllow(Arena arena, MemorySegment message, List<SystemdDevices.Allow> allows) {
        open(arena, message, 'r', "sv");
        appendString(arena, message, "DeviceAllow");
        open(arena, message, 'v', "a(ss)");
        open(arena, message, 'a', "(ss)");
        for (SystemdDevices.Allow allow : allows) {
            open(arena, message, 'r', "ss");
            appendString(arena, message, allow.device());
            appendString(arena, message, allow.access());
            close(message);
        }
        close(message);
        close(message);
        close(message);
    }

    private static void invoke(Arena arena, MemorySegment bus, MemorySegment message, String action) {
        MemorySegment error = arena.allocate(24);
        MemorySegment reply = arena.allocate(ValueLayout.ADDRESS);
        int rc = invokeInt(call, bus, message, 30_000_000L, error, reply);
        if (rc < 0) {
            throw new IllegalStateException(action + " failed: " + errorText(error));
        }
        MemorySegment replyMessage = reply.get(ValueLayout.ADDRESS, 0);
        if (replyMessage != null && replyMessage.address() != 0) {
            invokeAddress(unrefMessage, replyMessage);
        }
    }

    private static void unref(MemorySegment bus, MemorySegment message) {
        invokeAddress(unrefMessage, message);
        invokeAddress(unrefBus, bus);
    }

    private static void appendString(Arena arena, MemorySegment message, String value) {
        appendBasic(message, 's', arena.allocateFrom(value));
    }

    private static MemorySegment pointerToInt(Arena arena, int value) {
        MemorySegment pointer = arena.allocate(ValueLayout.JAVA_INT);
        pointer.set(ValueLayout.JAVA_INT, 0, value);
        return pointer;
    }

    private static void appendBasic(MemorySegment message, char type, MemorySegment value) {
        check(invokeInt(appendBasic, message, (byte) type, value), "append");
    }

    private static void open(Arena arena, MemorySegment message, char type, String contents) {
        check(invokeInt(openContainer, message, (byte) type, arena.allocateFrom(contents)), "open container");
    }

    private static void close(MemorySegment message) {
        check(invokeInt(closeContainer, message), "close container");
    }

    private static String errorText(MemorySegment error) {
        MemorySegment message = error.get(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteSize());
        if (message == null || message.address() == 0) {
            invokeVoid(freeError, error);
            return "systemd rejected the unit";
        }
        String text = message.reinterpret(512).getString(0);
        invokeVoid(freeError, error);
        return text;
    }

    private static int invokeInt(MethodHandle handle, Object... args) {
        try {
            return (int) handle.invokeWithArguments(args);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static MemorySegment invokeAddress(MethodHandle handle, Object... args) {
        try {
            return (MemorySegment) handle.invokeWithArguments(args);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void invokeVoid(MethodHandle handle, Object... args) {
        try {
            handle.invokeWithArguments(args);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void check(int rc, String action) {
        if (rc < 0) {
            throw new IllegalStateException(action + " failed: " + rc);
        }
    }

    private static void init() {
        if (library != null) {
            return;
        }
        library = SymbolLookup.libraryLookup(Path.of(libraryPath()), Arena.global());
        openSystem = down("sd_bus_open_system", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        newCall = down("sd_bus_message_new_method_call", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        appendBasic = down("sd_bus_message_append_basic", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS));
        openContainer = down("sd_bus_message_open_container", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS));
        closeContainer = down("sd_bus_message_close_container", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        call = down("sd_bus_call", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        unrefMessage = down("sd_bus_message_unref", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        unrefBus = down("sd_bus_unref", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        freeError = down("sd_bus_error_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static MethodHandle down(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(library.find(symbol).orElseThrow(() -> new IllegalStateException("libsystemd " + symbol)), descriptor);
    }

    private static String libraryPath() {
        String[] candidates = {
            "/usr/lib/x86_64-linux-gnu/libsystemd.so.0",
            "/usr/lib/aarch64-linux-gnu/libsystemd.so.0",
            "/usr/lib64/libsystemd.so.0",
            "/lib64/libsystemd.so.0"
        };
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("libsystemd is not installed");
    }
}
