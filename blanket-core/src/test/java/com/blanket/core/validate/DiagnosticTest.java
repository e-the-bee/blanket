// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticTest {

    @Test
    void formatIsOneLineEndingWithTheNextStep() {
        Diagnostic d = new Diagnostic(ErrorCode.UNRESOLVED, "forge:1.12.2", "Player.x", "net.minecraft.world.entity.Entity#getX()D", "pg:1.21.1|named|obf",
                "C2 resolution: no row\nin the file", "try an `lf:` id,\n see lookup");
        String line = d.format();
        assertEquals(
                "UNRESOLVED forge:1.12.2 Player.x ref net.minecraft.world.entity.Entity#getX()D at pg:1.21.1|named|obf: "
                        + "C2 resolution: no row in the file; next step: try an `lf:` id, see lookup",
                line);
        assertTrue(line.indexOf('\n') < 0);
    }

    @Test
    void optionalPartsAreLeftOut() {
        Diagnostic d = new Diagnostic(ErrorCode.UNBOUND, "*", "Player.armor", null, null, "C1 coverage: no rule list", "add one");
        assertEquals("UNBOUND * Player.armor: C1 coverage: no rule list; next step: add one", d.format());
    }

    @Test
    void aDiagnosticWithoutANextStepCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new Diagnostic(ErrorCode.SHAPE, "t", "m", null, null, "d", " "));
        assertThrows(IllegalArgumentException.class, () -> new Diagnostic(ErrorCode.SHAPE, "t", "m", null, null, "d", null));
    }

    @Test
    void reportFormatCountsErrorsAndWarnings() {
        Diagnostic d = new Diagnostic(ErrorCode.ORDER, "t", "m", null, null, "d", "n");
        Report r = new Report(java.util.List.of(d), java.util.List.of("w1", "w2"));
        assertEquals("ORDER t m: d; next step: n\nwarning: w1\nwarning: w2\n1 error, 2 warnings\n", r.format());
    }
}
