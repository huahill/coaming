package io.github.huahill.coaming.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class RuntimeFifo {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
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
    private static final MethodHandle POLL = downcall("poll", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    private static final MethodHandle ERRNO_LOCATION = downcall("__errno_location", FunctionDescriptor.of(
            ValueLayout.ADDRESS));

    private static final int O_WRONLY = 1;
    private static final int O_NONBLOCK = 2048;
    private static final int O_CLOEXEC = 524288;
    private static final int O_RDWR = 2;
    private static final int POLLIN = 1;
    private static final int CREATED_TIMEOUT_MS = 5000;

    private RuntimeFifo() {}

    static void mkfifo(String path) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) MKFIFO.invokeExact(arena.allocateFrom(path), 0644);
            if (rc != 0) {
                throw new IllegalStateException("mkfifo " + path + " failed: " + errno());
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("mkfifo " + path + " failed", failure);
        }
    }

    static int openStartFifo(String fifo) {
        return open(fifo, 2);
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
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("waiting for start failed", failure);
        } finally {
            close(fd);
        }
    }

    static void signal(String fifo) {
        int fd = open(fifo, O_WRONLY);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(1);
            buf.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            long rc = (long) WRITE.invokeExact(fd, buf, 1L);
            if (rc != 1) {
                throw new IllegalStateException("writing fifo failed: " + errno());
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("writing fifo failed", failure);
        } finally {
            close(fd);
        }
    }

    static void awaitCreated(String fifo) {
        // Holding the write end avoids the POLLHUP that a read-only nonblocking open gets
        // before init arrives. The byte itself is the signal.
        int fd = open(fifo, O_RDWR | O_NONBLOCK | O_CLOEXEC);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pollfd = arena.allocate(8);
            pollfd.set(ValueLayout.JAVA_INT, 0, fd);
            pollfd.set(ValueLayout.JAVA_SHORT, 4, (short) POLLIN);
            int rc = (int) POLL.invokeExact(pollfd, 1L, CREATED_TIMEOUT_MS);
            if (rc == 0) {
                throw new IllegalStateException("timed out waiting for container creation");
            }
            if (rc < 0) {
                throw new IllegalStateException("waiting for container creation failed: " + errno());
            }
            short revents = pollfd.get(ValueLayout.JAVA_SHORT, 6);
            if ((revents & POLLIN) == 0) {
                throw new IllegalStateException("timed out waiting for container creation");
            }
            MemorySegment buf = arena.allocate(1);
            long read = (long) READ.invokeExact(fd, buf, 1L);
            if (read != 1) {
                throw new IllegalStateException("timed out waiting for container creation");
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("waiting for container creation failed", failure);
        } finally {
            close(fd);
        }
    }

    static int openExecFifo(String fifo) {
        // Read-write so opening during create does not block until start arrives.
        return open(fifo, O_RDWR | O_CLOEXEC);
    }

    static int openExecRead(String fifo) {
        return open(fifo, O_CLOEXEC);
    }

    static void signalExecFailed(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(1);
            buf.set(ValueLayout.JAVA_BYTE, 0, (byte) 'E');
            WRITE.invokeExact(fd, buf, 1L);
        } catch (Throwable ignored) {
            // The original exec error is the one that matters.
        } finally {
            close(fd);
        }
    }

    static void waitUntilExec(String fifo) {
        int fd = openExecRead(fifo);
        readExecResult(fd);
    }

    static void readExecResult(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(1);
            while (true) {
                long rc = (long) READ.invokeExact(fd, buf, 1L);
                if (rc == 0) {
                    return;
                }
                if (rc > 0) {
                    if (buf.get(ValueLayout.JAVA_BYTE, 0) == (byte) 'E') {
                        throw new IllegalStateException("container exec failed");
                    }
                    continue;
                }
                if (errno() != 4) {
                    throw new IllegalStateException("reading exec fifo failed: " + errno());
                }
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("waiting for exec failed", failure);
        } finally {
            close(fd);
        }
    }

    private static int open(String path, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            int fd = (int) OPENAT.invokeExact(-100, arena.allocateFrom(path), flags, 0);
            if (fd < 0) {
                throw new IllegalStateException("open " + path + " failed: " + errno());
            }
            return fd;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("open " + path + " failed", failure);
        }
    }

    private static void close(int fd) {
        try {
            CLOSE.invokeExact(fd);
        } catch (Throwable ignored) {
            // The operation that used the descriptor reports the real failure.
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

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIBC.find(symbol).orElseThrow(() -> new IllegalStateException("libc " + symbol)), descriptor);
    }
}
