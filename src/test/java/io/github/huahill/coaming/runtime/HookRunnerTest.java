package io.github.huahill.coaming.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.huahill.coaming.oci.OciHook;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HookRunnerTest {
    @Test
    void stopsAtTheFirstFailingHook() {
        List<String> called = new ArrayList<>();
        HookRunner runner = new HookRunner((hook, state) -> {
            called.add(hook.path());
            return "/hooks/second".equals(hook.path()) ? 7 : 0;
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(List.of(
                new OciHook("/hooks/first", List.of(), List.of(), null),
                new OciHook("/hooks/second", List.of(), List.of(), null),
                new OciHook("/hooks/third", List.of(), List.of(), null)), Map.of("id", "demo")));
        assertEquals(List.of("/hooks/first", "/hooks/second"), called);
        assertTrue(failure.getMessage().contains("/hooks/second"));
        assertTrue(failure.getMessage().contains("7"));
    }

    @Test
    void reportsAHookThatCannotStart() {
        HookRunner runner = new HookRunner((hook, state) -> {
            throw new IOException("cannot start");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(
                List.of(new OciHook("/hooks/missing", List.of(), List.of(), null)), Map.of()));
        assertTrue(failure.getMessage().contains("/hooks/missing"));
    }
}
