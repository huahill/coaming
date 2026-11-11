package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApparmorProfilesTest {
    @Test
    void rejectsAProfileThatIsNotLoaded() {
        String listing = "cri-containerd.apparmor.d (enforce)\n";

        assertDoesNotThrow(() -> ApparmorProfiles.requireLoaded("cri-containerd.apparmor.d", listing));
        assertDoesNotThrow(() -> ApparmorProfiles.requireLoaded("unconfined", listing));
        assertThrows(IllegalStateException.class, () -> ApparmorProfiles.requireLoaded("localhost/non-existent-profile", listing));
    }
}
