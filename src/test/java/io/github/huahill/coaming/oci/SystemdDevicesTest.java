package io.github.huahill.coaming.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SystemdDevicesTest {
    @Test
    void denyAllThenASpecificDeviceIsAStrictAllowList() {
        SystemdDevices.Program program = SystemdDevices.translate(List.of(
                new DeviceRule(false, "a", "rwm", true, null, null),
                new DeviceRule(true, "c", "mwr", false, 1, 3)));

        assertEquals("strict", program.policy());
        assertEquals(List.of(new SystemdDevices.Allow("char-1:3", "rwm")), program.allows());
    }

    @Test
    void anUnrestrictedRuleDoesNotSetDevicePolicy() {
        SystemdDevices.Program program = SystemdDevices.translate(List.of(
                new DeviceRule(true, "a", "rwm", true, null, null)));

        assertNull(program.policy());
        assertEquals(List.of(), program.allows());
    }

    @Test
    void aDenyOfAnUnlistedDeviceIsRedundant() {
        SystemdDevices.Program program = SystemdDevices.translate(List.of(
                new DeviceRule(false, "a", "rwm", true, null, null),
                new DeviceRule(true, "c", "rw", false, 1, 8),
                new DeviceRule(false, "c", "rwm", false, 10, 229)));

        assertEquals("strict", program.policy());
        assertEquals(List.of(new SystemdDevices.Allow("char-1:8", "rw")), program.allows());
    }

    @Test
    void aWildcardMinorBecomesASystemdPattern() {
        SystemdDevices.Program program = SystemdDevices.translate(List.of(
                new DeviceRule(false, "a", "rwm", true, null, null),
                new DeviceRule(true, "c", "rwm", false, 136, null)));

        assertEquals(List.of(new SystemdDevices.Allow("char-136:*", "rwm")), program.allows());
    }

    @Test
    void aDenyThatRemovesAnAllowCannotBeExpressed() {
        assertThrows(IllegalArgumentException.class, () -> SystemdDevices.translate(List.of(
                new DeviceRule(false, "a", "rwm", true, null, null),
                new DeviceRule(true, "c", "rwm", false, 1, 3),
                new DeviceRule(false, "c", "w", false, 1, 3))));
    }
}
