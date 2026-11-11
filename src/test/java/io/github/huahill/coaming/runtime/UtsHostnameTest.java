package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UtsHostnameTest {
    @Test
    void setsTheNameInAJoinedNamespace() {
        assertEquals("test-hostname", UtsHostname.nameToSet("test-hostname", true, "uts:[1]", "uts:[2]"));
    }

    @Test
    void doesNotRenameTheHostNamespace() {
        assertNull(UtsHostname.nameToSet("test-hostname", true, "uts:[1]", "uts:[1]"));
    }

    @Test
    void doesNothingWithoutAUtsNamespaceOrName() {
        assertNull(UtsHostname.nameToSet("test-hostname", false, "uts:[1]", "uts:[2]"));
        assertNull(UtsHostname.nameToSet("  ", true, "uts:[1]", "uts:[2]"));
        assertNull(UtsHostname.nameToSet(null, true, null, "uts:[2]"));
    }
}
