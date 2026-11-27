// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.lookup.Matrix;
import com.blanket.core.resolve.TargetSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 beyond the type files: every public class of the blanket-api jar without {@code @Owned} needs a type file, an
 * alias enum's constants need rule lists, and pass-through members ({@code values()}, {@code valueOf(String)}) need none.
 */
class CoverageApiClassesTest {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final ClassLoader LOADER = CoverageApiClassesTest.class.getClassLoader();

    @TempDir
    Path tmp;

    @Test
    void theApiJarIsListedWithItsOwnedAndEnumFacts() {
        ApiClasses api = ApiClasses.withApiJar(LOADER);
        List<String> classes = api.apiJarClasses();
        for (String c : List.of("com/blanket/api/Player", "com/blanket/api/Direction", "com/blanket/api/Owned", "com/blanket/api/render/Canvas2D")) {
            assertTrue(classes.contains(c), c + " in " + classes);
        }
        assertTrue(classes.stream().allMatch(c -> c.startsWith("com/blanket/api/")), classes.toString());
        assertTrue(api.classInfo("com/blanket/api/Input").orElseThrow().owned(), "Input is an owned facade");
        assertFalse(api.classInfo("com/blanket/api/Player").orElseThrow().owned(), "Player is an alias stub");
        ApiClasses.ClassInfo direction = api.classInfo("com/blanket/api/Direction").orElseThrow();
        assertTrue(direction.isEnum());
        assertEquals(List.of("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"), direction.enumConstants());
        assertTrue(ApiClasses.of(LOADER).apiJarClasses().isEmpty(), "of() lists nothing, so fixture bindings need no type file per API class");
    }

    @Test
    void anAliasApiClassWithoutATypeFileIsUnbound() throws IOException {
        Path dir = Files.createTempDirectory(tmp, "bindings");
        Files.writeString(dir.resolve("targets.json"), ValidatorTest.targets("neoforge:1.21.1"), StandardCharsets.UTF_8);
        List<Diagnostic> errors = coverage(dir, ApiClasses.withApiJar(LOADER), new ArrayList<>());
        Diagnostic vec3 = errors.stream().filter(d -> d.member().equals("Vec3")).findFirst().orElseThrow(() -> new AssertionError(errors.toString()));
        assertEquals(ErrorCode.UNBOUND, vec3.code());
        assertEquals("C1 coverage: the public API class com.blanket.api.Vec3 is not @Owned and has no type file", vec3.detail());
        assertTrue(vec3.nextStep().contains("types/Vec3.json"), vec3.nextStep());
        for (String owned : List.of("Input", "Screen", "Feature", "Owned", "Blanket", "Canvas2D")) {
            assertTrue(errors.stream().noneMatch(d -> d.member().equals(owned)), owned + " is @Owned: " + errors);
        }
    }

    @Test
    void theRealBindingsCoverEveryApiClassAndDirectionsConstantsAreNotStale() throws IOException {
        List<String> warnings = new ArrayList<>();
        List<Diagnostic> errors = coverage(BINDINGS, ApiClasses.withApiJar(LOADER), warnings);
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Direction") && w.contains("stale")), warnings.toString());
    }

    @Test
    void enumPassThroughMembersNeedNoRuleListButConstantsDo() throws IOException {
        List<EnumOrderTest.GameConstant> game = List.of(new EnumOrderTest.GameConstant("a", "LEFT", 0), new EnumOrderTest.GameConstant("b", "RIGHT", 1));
        ApiClasses api = ApiClasses.of(LOADER);
        // values() and valueOf(String) have no rule list: pass-through
        Report green = EnumOrderTest.run(tmp, Map.of("types/FxSide.json", EnumOrderTest.side(
                EnumOrderTest.constant("LEFT", "field_1") + ", " + EnumOrderTest.constant("RIGHT", "field_2"))), game, api);
        assertTrue(green.ok(), green.format());
        assertTrue(green.warnings().stream().noneMatch(w -> w.contains("stale")), green.format());

        // a constant without a rule list is unbound
        Report missing = EnumOrderTest.run(tmp, Map.of("types/FxSide.json", EnumOrderTest.side(EnumOrderTest.constant("LEFT", "field_1"))), game, api);
        Diagnostic d = ValidatorTest.only(missing);
        assertEquals(ErrorCode.UNBOUND, d.code());
        assertEquals("FxSide.RIGHT", d.member());
        assertTrue(d.detail().startsWith("C1 coverage: public member FxSide.RIGHT has no rule list"), d.detail());

        // a rule list for a pass-through member is still a known key (not stale) and is checked like any other
        Report listed = EnumOrderTest.run(tmp, Map.of("types/FxSide.json", EnumOrderTest.side(
                EnumOrderTest.constant("LEFT", "field_1") + ", " + EnumOrderTest.constant("RIGHT", "field_2")
                        + ", \"values\": [ { \"when\": {}, \"lower\": { \"unsupported\": \"not needed\" } } ]")), game, api);
        assertTrue(listed.ok(), listed.format());
        assertTrue(listed.warnings().stream().noneMatch(w -> w.contains("stale")), listed.format());
    }

    private static List<Diagnostic> coverage(Path dir, ApiClasses api, List<String> warnings) throws IOException {
        BindingSet bindings = BindingSet.load(dir);
        List<TargetSpec> specs = new ArrayList<>();
        for (String id : bindings.targets().supported()) specs.add(Matrix.spec(bindings.targets(), id));
        List<Diagnostic> errors = new ArrayList<>();
        new Coverage(bindings, specs, api, errors, warnings).run();
        return errors;
    }
}
