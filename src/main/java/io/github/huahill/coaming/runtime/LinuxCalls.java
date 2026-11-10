package io.github.huahill.coaming.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import io.github.huahill.coaming.oci.LinuxCapabilities;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

final class LinuxCalls {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final SymbolLookup SECCOMP = openSeccomp();
    private static final MethodHandle MOUNT = downcall("mount", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    private static final MethodHandle UMOUNT2 = downcall("umount2", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle SYSCALL = downcall("syscall", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SETHOSTNAME = downcall("sethostname", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle MKFIFO = downcall("mkfifo", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle OPENAT = downcall("openat", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle READ = downcall("read", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle WRITE = downcall("write", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle CLOSE = downcall("close", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle CHDIR = downcall("chdir", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle MKDIR = downcall("mkdir", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle EXECVE = downcall("execve", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle KILL = downcall("kill", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle PRCTL = downcall("prctl", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    private static final MethodHandle CAPSET = downcall("capset", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle ERRNO_LOCATION = downcall("__errno_location", FunctionDescriptor.of(
            ValueLayout.ADDRESS));
    private static final MethodHandle MEMFD_CREATE = downcall("memfd_create", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MKNOD = downcall("mknod", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    private static final MethodHandle SECCOMP_INIT = seccomp("seccomp_init", FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle SECCOMP_RESOLVE = seccomp("seccomp_syscall_resolve_name", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle SECCOMP_RULE = seccomp("seccomp_rule_add_array", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle SECCOMP_ATTR = seccomp("seccomp_attr_set", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle SECCOMP_LOAD = seccomp("seccomp_load", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle SECCOMP_RELEASE = seccomp("seccomp_release", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final int MS_REC = 16384;
    private static final int MS_PRIVATE = 1 << 18;
    private static final int MS_BIND = 4096;
    private static final int MNT_DETACH = 2;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_RDWR = 2;

    private LinuxCalls() {}

    static void bindPrivate(String path) {
        check(mount(null, "/", null, MS_REC | MS_PRIVATE, null), "make / private");
        check(mount(path, path, null, MS_BIND, null), "bind " + path);
        check(mount(null, path, null, MS_REC | MS_PRIVATE, null), "make private " + path);
    }

    static void mountProc(String path) {
        mkdir(path);
        check(mount("proc", path, "proc", 0, null), "mount proc");
    }

    static void mask(String path) {
        Path target = Path.of(path);
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalStateException("refusing to mask symlink " + path);
        }
        ensureDevNull();
        int rc = mount("/dev/null", path, null, MS_BIND, null);
        if (rc == 0) {
            return;
        }
        int err = errno();
        if (err == 20) {
            check(mount("tmpfs", path, "tmpfs", 1, "mode=000"), "mask " + path);
            return;
        }
        throw new IllegalStateException("mask " + path + " failed: " + err);
    }

    static void readonly(String path) {
        Path target = Path.of(path);
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalStateException("refusing to make symlink read-only " + path);
        }
        check(mount(path, path, null, MS_BIND | MS_REC, null), "readonly bind " + path);
        check(mount(path, path, null, MS_BIND | 32 | 1, null), "readonly remount " + path);
    }

    static void mountPrepared(String source, String target, String fstype, long flags, long remount, long propagation, String data) {
        String payload = data == null || data.isEmpty() ? null : data;
        check(mount(source, target, fstype, flags, payload), "mount " + target);
        if (remount != 0) {
            check(mount(null, target, null, remount, null), "remount " + target);
        }
        if (propagation != 0) {
            check(mount(null, target, null, propagation, null), "propagation " + target);
        }
    }

    static void pivotRoot(String newRoot, String putOld) {
        try (Arena arena = Arena.ofConfined()) {
            long rc = (long) SYSCALL.invokeExact(
                    (long) pivotRootNumber(),
                    arena.allocateFrom(newRoot),
                    arena.allocateFrom(putOld));
            if (rc != 0) {
                throw new IllegalStateException("pivot_root failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("pivot_root failed", failure);
        }
    }

    static void chdir(String path) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CHDIR.invokeExact(arena.allocateFrom(path));
            if (rc != 0) {
                throw new IllegalStateException("chdir " + path + " failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("chdir " + path + " failed", failure);
        }
    }

    static void umountDetach(String path) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) UMOUNT2.invokeExact(arena.allocateFrom(path), MNT_DETACH);
            if (rc != 0) {
                throw new IllegalStateException("umount " + path + " failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("umount " + path + " failed", failure);
        }
    }

    static void hostname(String name) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) SETHOSTNAME.invokeExact(arena.allocateFrom(name), (long) name.length());
            if (rc != 0) {
                throw new IllegalStateException("sethostname failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("sethostname failed", failure);
        }
    }

    static void mkfifo(String path) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) MKFIFO.invokeExact(arena.allocateFrom(path), 0644);
            if (rc != 0) {
                throw new IllegalStateException("mkfifo " + path + " failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("mkfifo " + path + " failed", failure);
        }
    }

    static int openStartFifo(String fifo) {
        return open(fifo, O_RDWR);
    }

    static void readStartByte(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(1);
            while (true) {
                long rc = (long) READ.invokeExact(fd, buf, 1L);
                if (rc > 0) {
                    return;
                }
                if (rc < 0 && errno() != 4) {
                    throw new IllegalStateException("reading start fifo failed: " + errno());
                }
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("waiting for start failed", failure);
        } finally {
            close(fd);
        }
    }

    static void signalStart(String fifo) {
        int fd = open(fifo, O_WRONLY);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(1);
            buf.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            long rc = (long) WRITE.invokeExact(fd, buf, 1L);
            if (rc != 1) {
                throw new IllegalStateException("writing start fifo failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("writing start fifo failed", failure);
        } finally {
            close(fd);
        }
    }

    static void kill(int pid, int signal) {
        try {
            int rc = (int) KILL.invokeExact(pid, signal);
            if (rc != 0) {
                throw new IllegalStateException("kill " + pid + " failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("kill " + pid + " failed", failure);
        }
    }

    static void applyCapabilities(LinuxCapabilities caps) {
        check(prctl(47, 4, 0, 0, 0), "clear ambient capabilities");
        for (int cap = 0; cap < 64; cap++) {
            boolean want = ((caps.bounding() >>> cap) & 1L) == 1L;
            int has = prctl(23, cap, 0, 0, 0);
            if (has < 0) {
                if (want) {
                    throw new IllegalStateException("capability is not supported: " + cap);
                }
                continue;
            }
            if (has == 0 && want) {
                throw new IllegalStateException("capability is not in the bounding set: " + cap);
            }
            if (has == 1 && !want) {
                check(prctl(24, cap, 0, 0, 0), "drop bounding capability " + cap);
            }
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment header = arena.allocate(8);
            header.set(ValueLayout.JAVA_INT, 0, 0x20080522);
            header.set(ValueLayout.JAVA_INT, 4, 0);
            MemorySegment data = arena.allocate(24);
            writeCap(data, 0, caps.effective(), caps.permitted(), caps.inheritable());
            writeCap(data, 12, caps.effective() >>> 32, caps.permitted() >>> 32, caps.inheritable() >>> 32);
            check((int) CAPSET.invokeExact(header, data), "capset");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("capset failed", failure);
        }
        for (int cap = 0; cap < 64; cap++) {
            if (((caps.ambient() >>> cap) & 1L) == 1L) {
                check(prctl(47, 2, cap, 0, 0), "raise ambient capability " + cap);
            }
        }
    }

    static void noNewPrivileges() {
        check(prctl(38, 1, 0, 0, 0), "no new privileges");
    }

    private static void writeCap(MemorySegment data, int offset, long effective, long permitted, long inheritable) {
        data.set(ValueLayout.JAVA_INT, offset, (int) effective);
        data.set(ValueLayout.JAVA_INT, offset + 4, (int) permitted);
        data.set(ValueLayout.JAVA_INT, offset + 8, (int) inheritable);
    }

    private static int prctl(int option, long arg2, long arg3, long arg4, long arg5) {
        try {
            return (int) PRCTL.invokeExact(option, arg2, arg3, arg4, arg5);
        } catch (Throwable failure) {
            throw new IllegalStateException("prctl failed", failure);
        }
    }

    static void exec(String[] args) {
        exec(args, new String[0]);
    }

    static void exec(String[] args, String[] env) {
        Arena arena = Arena.global();
        MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, args.length + 1L);
        for (int i = 0; i < args.length; i++) {
            argv.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(args[i]));
        }
        argv.setAtIndex(ValueLayout.ADDRESS, args.length, MemorySegment.NULL);
        MemorySegment envp = arena.allocate(ValueLayout.ADDRESS, env.length + 1L);
        for (int i = 0; i < env.length; i++) {
            envp.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(env[i]));
        }
        envp.setAtIndex(ValueLayout.ADDRESS, env.length, MemorySegment.NULL);
        int rc;
        try {
            rc = (int) EXECVE.invokeExact(arena.allocateFrom(args[0]), argv, envp);
        } catch (Throwable failure) {
            throw new IllegalStateException("execve failed: " + failure, failure);
        }
        throw new IllegalStateException("execve " + args[0] + " returned " + rc + " errno=" + errno());
    }

    static int memfdCreate(String name) {
        int fd;
        try (Arena arena = Arena.ofConfined()) {
            try {
                fd = (int) MEMFD_CREATE.invokeExact(arena.allocateFrom(name), 1);
            } catch (Throwable failure) {
                throw new IllegalStateException("memfd_create failed", failure);
            }
        }
        if (fd < 0) {
            throw new IllegalStateException("memfd_create failed: " + errno());
        }
        return fd;
    }

    static void writeFully(int fd, byte[] bytes) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            long offset = 0;
            while (offset < bytes.length) {
                long wrote = (long) WRITE.invokeExact(fd, data.asSlice(offset), bytes.length - offset);
                if (wrote < 0) {
                    throw new IllegalStateException("write failed: " + errno());
                }
                offset += wrote;
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("write failed", failure);
        }
    }

    static void mkdir(String path) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) MKDIR.invokeExact(arena.allocateFrom(path), 0755);
            if (rc != 0 && errno() != 17) {
                throw new IllegalStateException("mkdir " + path + " failed: " + errno());
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("mkdir " + path + " failed", failure);
        }
    }


    static void createDevice(String path, String type, long major, long minor, int fileMode) {
        int kind = switch (type) {
            case "c", "u" -> 8192;
            case "b" -> 24576;
            case "p" -> 4096;
            default -> throw new IllegalArgumentException("unsupported device type " + type);
        };
        Path file = Path.of(path);
        if (file.getParent() != null) {
            try {
                Files.createDirectories(file.getParent());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("mkdir " + file.getParent() + " failed", failure);
            }
        }
        if (Files.exists(file)) {
            return;
        }
        long dev = ((major & 0xfff) << 8) | ((major & 0xfffff000L) << 32) | (minor & 0xff) | ((minor & 0xffffff00L) << 12);
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) MKNOD.invokeExact(arena.allocateFrom(path), kind | (fileMode & 07777), dev);
            if (rc != 0 && errno() != 17) {
                throw new IllegalStateException("mknod " + path + " failed: " + errno());
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("mknod " + path + " failed", failure);
        }
    }

    static void installSeccomp(io.github.huahill.coaming.oci.SeccompProfile profile) {
        MemorySegment context;
        try {
            context = (MemorySegment) SECCOMP_INIT.invokeExact(profile.defaultAction());
        } catch (Throwable failure) {
            throw new IllegalStateException("seccomp_init failed", failure);
        }
        if (context == null || context.address() == 0) {
            throw new IllegalStateException("seccomp_init failed");
        }
        try (Arena arena = Arena.ofConfined()) {
            for (var syscall : profile.syscalls()) {
                MemorySegment arguments = MemorySegment.NULL;
                if (!syscall.args().isEmpty()) {
                    arguments = arena.allocate(24L * syscall.args().size());
                    int offset = 0;
                    for (var argument : syscall.args()) {
                        arguments.set(ValueLayout.JAVA_INT, offset, argument.index());
                        arguments.set(ValueLayout.JAVA_INT, offset + 4, argument.operation());
                        arguments.set(ValueLayout.JAVA_LONG, offset + 8, argument.value());
                        arguments.set(ValueLayout.JAVA_LONG, offset + 16, argument.valueTwo());
                        offset += 24;
                    }
                }
                for (String name : syscall.names()) {
                    int number;
                    try {
                        number = (int) SECCOMP_RESOLVE.invokeExact(arena.allocateFrom(name));
                    } catch (Throwable failure) {
                        throw new IllegalStateException("seccomp resolve failed", failure);
                    }
                    if (number < 0) {
                        throw new IllegalArgumentException("unknown syscall " + name);
                    }
                    int rc;
                    try {
                        rc = (int) SECCOMP_RULE.invokeExact(context, syscall.action(), number, syscall.args().size(), arguments);
                    } catch (Throwable failure) {
                        throw new IllegalStateException("seccomp rule failed", failure);
                    }
                    if (rc != 0) {
                        throw new IllegalStateException("seccomp rule " + name + " failed: " + rc);
                    }
                }
            }
            int attr;
            int loaded;
            try {
                attr = (int) SECCOMP_ATTR.invokeExact(context, 4, 1);
                loaded = (int) SECCOMP_LOAD.invokeExact(context);
            } catch (Throwable failure) {
                throw new IllegalStateException("seccomp load failed", failure);
            }
            if (attr != 0 || loaded != 0) {
                throw new IllegalStateException("seccomp load failed: " + attr + "/" + loaded);
            }
        } finally {
            try {
                SECCOMP_RELEASE.invokeExact(context);
            } catch (Throwable ignored) {
                // The load error is the one that matters.
            }
        }
    }

    private static void ensureDevNull() {
        if (Files.exists(Path.of("/dev/null"))) {
            return;
        }
        mkdir("/dev");
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) MKNOD.invokeExact(arena.allocateFrom("/dev/null"), 8192 | 438, 259L);
            if (rc != 0 && errno() != 17) {
                throw new IllegalStateException("mknod /dev/null failed: " + errno());
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("mknod /dev/null failed", failure);
        }
    }

    private static int mount(String source, String target, String type, long flags, String data) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) MOUNT.invokeExact(
                    cstring(arena, source), cstring(arena, target), cstring(arena, type), flags, cstring(arena, data));
        } catch (Throwable failure) {
            throw new IllegalStateException("mount failed", failure);
        }
    }

    private static MemorySegment cstring(Arena arena, String value) {
        return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
    }

    private static int open(String path, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            int fd = (int) OPENAT.invokeExact(-100, arena.allocateFrom(path), flags, 0);
            if (fd < 0) {
                throw new IllegalStateException("open " + path + " failed: " + errno());
            }
            return fd;
        } catch (Throwable failure) {
            throw new IllegalStateException("open " + path + " failed", failure);
        }
    }

    private static void close(int fd) {
        int rc;
        try {
            rc = (int) CLOSE.invokeExact(fd);
        } catch (Throwable ignored) {
            return;
        }
        if (rc != 0) {
            // The operation that used the descriptor reports the real failure.
        }
    }

    private static void check(int rc, String action) {
        if (rc != 0) {
            throw new IllegalStateException(action + " failed: " + errno());
        }
    }

    private static int errno() {
        try {
            MemorySegment location = (MemorySegment) ERRNO_LOCATION.invokeExact();
            return location.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable failure) {
            return -1;
        }
    }

    private static int pivotRootNumber() {
        return switch (System.getProperty("os.arch")) {
            case "aarch64" -> 41;
            case "amd64", "x86_64" -> 155;
            default -> throw new IllegalStateException("no pivot_root syscall for " + System.getProperty("os.arch"));
        };
    }

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIBC.find(symbol).orElseThrow(() -> new IllegalStateException("libc " + symbol)), descriptor);
    }

    private static MethodHandle seccomp(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(SECCOMP.find(symbol).orElseThrow(() -> new IllegalStateException("libseccomp " + symbol)), descriptor);
    }

    private static SymbolLookup openSeccomp() {
        String[] candidates = {
            "/usr/lib/aarch64-linux-gnu/libseccomp.so.2",
            "/lib/aarch64-linux-gnu/libseccomp.so.2",
            "/usr/lib/x86_64-linux-gnu/libseccomp.so.2",
            "/lib/x86_64-linux-gnu/libseccomp.so.2",
            "/usr/lib64/libseccomp.so.2",
            "/lib64/libseccomp.so.2"
        };
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return SymbolLookup.libraryLookup(Path.of(candidate), Arena.global());
            }
        }
        throw new IllegalStateException("libseccomp is not installed");
    }
}
