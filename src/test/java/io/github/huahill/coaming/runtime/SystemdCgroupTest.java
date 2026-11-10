package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SystemdCgroupTest {
    @Test
    void convertsTheContainerdScopePath() {
        SystemdCgroup.Scope scope = SystemdCgroup.scope("kubepods.slice:cri-containerd:abc", "ignored");

        assertEquals("kubepods.slice", scope.slice());
        assertEquals("cri-containerd-abc.scope", scope.unit());
        assertEquals("/kubepods.slice/cri-containerd-abc.scope", scope.filesystemPath());
    }

    @Test
    void expandsNestedSlices() {
        SystemdCgroup.Scope scope = SystemdCgroup.scope("kubepods-besteffort-poduid.slice:cri-containerd:abc", "abc");

        assertEquals("/kubepods.slice/kubepods-besteffort.slice/kubepods-besteffort-poduid.slice/cri-containerd-abc.scope", scope.filesystemPath());
    }

    @Test
    void rejectsAParentThatIsNotASlice() {
        assertThrows(IllegalArgumentException.class, () -> SystemdCgroup.scope("system.service:coaming:demo", "demo"));
    }
}
