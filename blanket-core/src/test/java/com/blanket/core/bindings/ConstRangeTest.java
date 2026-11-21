// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

/** An integral constant must lie in the range of the byte, short, char or int parameter it feeds. */
class ConstRangeTest {
    private static boolean fits(long v, String type) {
        return Lowering.constFits(new JsonPrimitive(v), type);
    }

    @Test
    void byteRangeBoundaries() {
        assertTrue(fits(-128, "B") && fits(127, "B"));
        assertFalse(fits(-129, "B") || fits(128, "B"));
        assertFalse(fits(300, "B"));
    }

    @Test
    void shortRangeBoundaries() {
        assertTrue(fits(-32768, "S") && fits(32767, "S"));
        assertFalse(fits(-32769, "S") || fits(32768, "S"));
    }

    @Test
    void charRangeBoundaries() {
        assertTrue(fits(0, "C") && fits(65535, "C"));
        assertFalse(fits(-1, "C") || fits(65536, "C"));
    }

    @Test
    void intRangeBoundariesAndWiderTypes() {
        assertTrue(fits(Integer.MIN_VALUE, "I") && fits(Integer.MAX_VALUE, "I"));
        assertFalse(fits(Integer.MAX_VALUE + 1L, "I"));
        assertTrue(fits(Integer.MAX_VALUE + 1L, "J") && fits(300, "F") && fits(300, "D"));
    }

    @Test
    void chainParserUsesTheSameRule() {
        String ok = "{\"chain\":[{\"cast\":\"a.B\"},{\"const\":127},{\"invoke\":\"a.B#m(B)V\"}]}";
        String bad = "{\"chain\":[{\"cast\":\"a.B\"},{\"const\":128},{\"invoke\":\"a.B#m(B)V\"}]}";
        Lowering.parse(JsonParser.parseString(ok), "t", "$");
        assertThrows(BindingException.class, () -> Lowering.parse(JsonParser.parseString(bad), "t", "$"));
    }
}
