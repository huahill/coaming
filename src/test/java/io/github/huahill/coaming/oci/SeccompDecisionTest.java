package io.github.huahill.coaming.oci;

import static io.github.huahill.coaming.oci.SeccompDecision.Result.ADD;
import static io.github.huahill.coaming.oci.SeccompDecision.Result.FAIL;
import static io.github.huahill.coaming.oci.SeccompDecision.Result.SKIP;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SeccompDecisionTest {
    @Test
    void unresolvedNameIsSkipped() {
        assertEquals(SKIP, SeccompDecision.decide(-1, -13));
    }

    @Test
    void resolvedRuleMustBeAdded() {
        assertEquals(ADD, SeccompDecision.decide(451, 0));
        assertEquals(FAIL, SeccompDecision.decide(451, -13));
    }
}
