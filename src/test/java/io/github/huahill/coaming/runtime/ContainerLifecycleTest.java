package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContainerLifecycleTest {
    @Test
    void refusesToDeleteALiveContainerUnlessForced() {
        assertFalse(ContainerRuntime.deletionIsAllowed("created", true, false));
        assertFalse(ContainerRuntime.deletionIsAllowed("running", true, false));
        assertTrue(ContainerRuntime.deletionIsAllowed("running", false, false));
        assertTrue(ContainerRuntime.deletionIsAllowed("stopped", true, false));
        assertTrue(ContainerRuntime.deletionIsAllowed("running", true, true));
    }
}
