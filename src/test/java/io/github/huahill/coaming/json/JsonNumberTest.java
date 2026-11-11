package io.github.huahill.coaming.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JsonNumberTest {
    @Test
    void keepsIntegersIncludingNegatives() {
        Json value = Json.parse("{\"limit\":-1,\"shares\":1024,\"unused\":1.5}");

        assertEquals(-1, value.field("limit").longValue());
        assertEquals(1024, value.field("shares").longValue());
    }

    @Test
    void decodesUnicodeEscapes() {
        assertEquals(">", Json.parse("\"\\u003e\"").string());
    }
}
