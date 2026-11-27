// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.lookup.Matrix;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.ModScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code blanket check} with the mod's classes ({@link ModCheck}): FOLD's passes decide which uses of unsupported members
 * fail (binding-format.md example 4); a {@code requires} rule is not resolved where its feature is missing; aliased enum
 * constants have their rules checked. The game is {@link FixtureGame}; NEO is available on neoforge only. SCAN records
 * only {@code com/blanket/api/} owners, so the mod uses invented API types {@code FxPlayer} and {@code FxSide} there (no
 * class files: {@code blanket check} takes their methods from the scan); {@code validateBindings} reads the fixture
 * classes of {@code validate/fixture}.
 */
class ModCheckFoldTest implements Opcodes {
    private static final String PLAYER = "com/blanket/api/FxPlayer";
    private static final String SIDE = "com/blanket/api/FxSide";
    private static final String BLANKET = "com/blanket/api/Blanket";
    private static final String FEATURE = "com/blanket/api/Feature";

    static final String FEATURES = """
            { "schema": 1, "features": { "NEO": { "available": [ { "when": { "loader": "neoforge" } } ] } } }""";

    /** {@code armor} needs NEO; its reference exists in the fixture game. */
    static final String ARMOR_NEEDS_NEO = """
            "armor": [ { "when": {}, "requires": "NEO", "lower": { "invoke": "net.example.Player#armor()I" } } ]""";

    /** {@code armor} needs NEO; its reference exists nowhere. */
    static final String MISSING_ARMOR_NEEDS_NEO = """
            "armor": [ { "when": {}, "requires": "NEO", "lower": { "invoke": "net.example.Player#missing()I" } } ]""";

    /** {@code x} is unsupported on fabric. */
    static final String X_NOT_ON_FABRIC = """
            "x": [ { "when": { "loader": "fabric" }, "lower": { "unsupported": "no x in the fixture" } },
                   { "when": {}, "lower": { "get": "net.example.Player#x:D" } } ]""";

    @TempDir
    Path tmp;

    FixtureGame.Data data;

    @BeforeEach
    void game() throws IOException {
        data = new FixtureGame.Data(FixtureGame.clientJar(tmp, true, true));
    }

    // ---------------------------------------------------------------------------------------- fixtures

    private BindingSet bindings(Map<String, String> files, String... supported) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "bindings");
        Map<String, String> all = new LinkedHashMap<>(files);
        all.put("targets.json", ValidatorTest.targets(supported));
        all.put("features.json", FEATURES);
        for (Map.Entry<String, String> e : all.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        return BindingSet.load(dir);
    }

    /** {@code blanket check} of {@code code} on each of {@code targets}; {@code code == null} checks without the classes. */
    private Report check(Map<String, String> files, Map<String, byte[]> code, String... targets) throws IOException {
        BindingSet set = bindings(files, targets);
        List<TargetSpec> specs = new ArrayList<>();
        for (String id : targets) specs.add(Matrix.spec(set.targets(), id));
        ApiClasses api = ApiClasses.of(ModCheckFoldTest.class.getClassLoader());
        if (code == null) return ModCheck.run(set, data, api, ModScanner.scan(mod(this::unguarded)), specs);
        return ModCheck.run(set, data, api, ModScanner.scan(code), code, specs);
    }

    /** FxPlayer as {@code blanket check} sees it: {@link ValidatorTest#player} with the API name of {@link #PLAYER}. */
    private static Map<String, String> playerFile(String members) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("types/FxPlayer.json", ValidatorTest.player(members).replace("com.blanket.core.validate.fixture.FxPlayer", "com.blanket.api.FxPlayer"));
        return f;
    }

    /** {@code mod/Hud} with {@code static render(FxPlayer, Feature)V} written by {@code body}. */
    static Map<String, byte[]> mod(Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "mod/Hud", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "render", "(L" + PLAYER + ";L" + FEATURE + ";)V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("mod/Hud", cw.toByteArray());
        return out;
    }

    private void unguarded(MethodVisitor mv) {
        armor(mv);
    }

    private static void armor(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "armor", "()I", false);
        mv.visitInsn(POP);
    }

    private static void x(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "x", "()D", false);
        mv.visitInsn(POP2);
    }

    /** {@code if (Blanket.supports(<feature>)) then.run()}; the feature is a constant read, or the method's parameter. */
    private static Consumer<MethodVisitor> guarded(boolean literal, Consumer<MethodVisitor> then) {
        return mv -> {
            if (literal) mv.visitFieldInsn(GETSTATIC, FEATURE, "NEO", "L" + FEATURE + ";");
            else mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, BLANKET, "supports", "(L" + FEATURE + ";)Z", false);
            Label skip = new Label();
            mv.visitJumpInsn(IFEQ, skip);
            then.accept(mv);
            mv.visitLabel(skip);
        };
    }

    // ---------------------------------------------------------------------------------------- folding

    @Test
    void unguardedUseOfARequiresMemberIsUnsupportedWithTheFeatureRange() throws IOException {
        Report r = check(playerFile(ValidatorTest.X + ",\n" + ARMOR_NEEDS_NEO), mod(this::unguarded), "fabric:1.21.1", "neoforge:1.21.1");
        Diagnostic d = ValidatorTest.only(r);
        assertEquals(ErrorCode.UNSUPPORTED, d.code());
        assertEquals("fabric:1.21.1", d.target());
        assertEquals("FxPlayer.armor()", d.member());
        assertEquals("mod.Hud.render(L" + PLAYER + ";L" + FEATURE + ";)V calls FxPlayer.armor() which needs Feature.NEO (available on neoforge)",
                d.detail());
        assertTrue(d.nextStep().contains("Blanket.supports(Feature.NEO)"), d.nextStep());
    }

    @Test
    void guardedUseOfARequiresMemberPassesEverywhere() throws IOException {
        Report r = check(playerFile(ValidatorTest.X + ",\n" + ARMOR_NEEDS_NEO), mod(guarded(true, ModCheckFoldTest::armor)),
                "fabric:1.21.1", "neoforge:1.21.1");
        assertTrue(r.ok(), r.format());
    }

    @Test
    void explicitUnsupportedRuleFailsOnlyReachableUses() throws IOException {
        Map<String, String> f = playerFile(X_NOT_ON_FABRIC + ",\n" + ValidatorTest.ARMOR);
        Diagnostic d = ValidatorTest.only(check(f, mod(ModCheckFoldTest::x), "fabric:1.21.1", "neoforge:1.21.1"));
        assertEquals(ErrorCode.UNSUPPORTED, d.code());
        assertEquals("fabric:1.21.1", d.target());
        assertTrue(d.detail().endsWith("calls FxPlayer.x() which is unsupported on fabric:1.21.1: no x in the fixture"), d.detail());
        // NEO is false on fabric: the guarded call is dead there
        assertTrue(check(f, mod(guarded(true, ModCheckFoldTest::x)), "fabric:1.21.1").ok());
    }

    @Test
    void withoutTheClassesUnsupportedUsesAreLeftToLower() throws IOException {
        assertTrue(check(playerFile(X_NOT_ON_FABRIC + ",\n" + ARMOR_NEEDS_NEO), null, "fabric:1.21.1").ok());
    }

    @Test
    void aFeatureThatIsNotALiteralConstantIsForbidden() throws IOException {
        Report r = check(playerFile(ValidatorTest.X + ",\n" + ARMOR_NEEDS_NEO), mod(guarded(false, ModCheckFoldTest::armor)), "neoforge:1.21.1");
        Diagnostic d = ValidatorTest.only(r);
        assertEquals(ErrorCode.FORBIDDEN, d.code());
        assertTrue(d.detail().contains("not a Feature constant read"), d.detail());
    }

    // ---------------------------------------------------------------------------------------- requires and resolution

    @Test
    void requiresRuleIsNotResolvedWhereTheFeatureIsMissing() throws IOException {
        Map<String, String> f = playerFile(ValidatorTest.X + ",\n" + MISSING_ARMOR_NEEDS_NEO);
        // binding example 4: the member does not exist where the feature is missing; check and validateBindings agree
        assertTrue(check(f, mod(guarded(true, ModCheckFoldTest::armor)), "fabric:1.21.1").ok());
        assertTrue(validate(ValidatorTest.X + ",\n" + MISSING_ARMOR_NEEDS_NEO, "fabric:1.21.1").ok());

        Diagnostic d = ValidatorTest.only(check(f, mod(guarded(true, ModCheckFoldTest::armor)), "neoforge:1.21.1"));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("FxPlayer.armor", d.member());
        Diagnostic v = ValidatorTest.only(validate(ValidatorTest.X + ",\n" + MISSING_ARMOR_NEEDS_NEO, "neoforge:1.21.1"));
        assertEquals(ErrorCode.UNRESOLVED, v.code());
        assertEquals("FxPlayer.armor", v.member());
    }

    /** {@code validateBindings} on {@link ValidatorTest#baseline()} with the fixture FxPlayer's members replaced. */
    private Report validate(String playerMembers, String target) throws IOException {
        Map<String, String> all = ValidatorTest.baseline();
        all.put("types/FxPlayer.json", ValidatorTest.player(playerMembers));
        return new Validator(bindings(all, target), data, ApiClasses.of(ModCheckFoldTest.class.getClassLoader())).run();
    }

    // ---------------------------------------------------------------------------------------- enum constants

    static String sideFile(String constantRef) {
        return """
                { "schema": 1, "api": "com.blanket.api.FxSide",
                  "representation": [ { "when": {}, "alias": "net.example.Client" } ],
                  "members": { "LEFT": [ { "when": {}, "lower": { "get": "%s" } } ] } }""".formatted(constantRef);
    }

    private static Map<String, byte[]> readsLeft() {
        return mod(mv -> {
            mv.visitFieldInsn(GETSTATIC, SIDE, "LEFT", "L" + SIDE + ";");
            mv.visitInsn(POP);
        });
    }

    @Test
    void aliasEnumConstantRulesAreResolved() throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("types/FxSide.json", sideFile("net.example.Client#player:Lnet/example/Player;"));
        assertTrue(check(f, readsLeft(), "neoforge:1.21.1").ok(), check(f, readsLeft(), "neoforge:1.21.1").format());

        f.put("types/FxSide.json", sideFile("net.example.Client#missing:Lnet/example/Client;"));
        Diagnostic d = ValidatorTest.only(check(f, readsLeft(), "neoforge:1.21.1"));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("FxSide.LEFT", d.member());
        assertEquals("net.example.Client#missing:Lnet/example/Client;", d.reference());
    }

    @Test
    void aFieldWithoutARuleListIsStillUnbound() throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("types/FxSide.json", sideFile("net.example.Client#player:Lnet/example/Player;"));
        Map<String, byte[]> code = mod(mv -> {
            mv.visitFieldInsn(GETSTATIC, SIDE, "RIGHT", "L" + SIDE + ";");
            mv.visitInsn(POP);
        });
        Diagnostic d = ValidatorTest.only(check(f, code, "neoforge:1.21.1"));
        assertEquals(ErrorCode.UNBOUND, d.code());
        assertTrue(d.detail().contains("accessors only"), d.detail());
    }
}
