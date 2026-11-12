package io.github.huahill.coaming.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BindPlanTest {
    @Test
    void selectsHostBindMountsAndSkipsIdmap() {
        List<String> args = BindPlan.arguments(List.of(
                new OciMount("/proc", "proc", "proc", List.of("nosuid")),
                new OciMount("/dev/shm", "bind", "/run/shm", List.of("rbind", "ro")),
                new OciMount("/etc/resolv.conf", "bind", "/var/lib/resolv.conf", List.of("rbind", "nosuid")),
                new OciMount("/data", "bind", "/home/data", List.of("bind", "idmap")),
                new OciMount("/note", "bind", "/tmp/quote\"x", List.of("bind")),
                new OciMount("/ro", "bind", "/tmp/ro", List.of("rro"))));
        assertEquals(List.of(
                "--bind", "/run/shm", "/dev/shm", "1", "1", "0",
                "--bind", "/var/lib/resolv.conf", "/etc/resolv.conf", "1", "2", "0",
                "--bind", "/tmp/quote\"x", "/note", "0", "0", "0",
                "--bind", "/tmp/ro", "/ro", "1", "1", "1"), args);
    }
}
