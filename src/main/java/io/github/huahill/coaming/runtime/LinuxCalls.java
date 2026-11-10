package io.github.huahill.coaming.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class LinuxCalls {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
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
    private static final MethodHandle ERRNO_LOCATION = downcall("__errno_location", FunctionDescriptor.of(
            ValueLayout.ADDRESS));
    private static final MethodHandle MEMFD_CREATE = downcall("memfd_create", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final int MS_REC = 16384;
    private static final int MS_PRIVATE = 1 << 18;
    private static final int MS_BIND = 4096;
    private static final int MNT_DETACH = 2;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_RDWR = 2;

    private LinuxCalls() {}

    static void bindPrivate(String path) {
        check(mount(path, path, null, MS_BIND, null), "bind " + path);
        check(mount(null, path, null, MS_REC | MS_PRIVATE, null), "make private " + path);
    }

    static void mountProc(String path) {
        mkdir(path);
        check(mount("proc", path, "proc", 0, null), "mount proc");
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
}
