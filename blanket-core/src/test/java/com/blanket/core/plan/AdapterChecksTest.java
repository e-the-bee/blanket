// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.validate.ApiClasses;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import com.blanket.core.validate.Report;
import com.blanket.core.validate.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 adapters and C6 overrides through {@code validateBindings} ({@link Validator}) on the invented obfuscated game of
 * {@link ObfGameFixture}, with a built set {@code modern} in intermediary shipping names, on an intermediary target
 * ({@code fabric:1.21.1}) and an srgmoj target ({@code forge:1.20.1}). The fake API class {@code com/blanket/api/Client}
 * is an alias of the game's {@code Game} ({@code class_4}); its members {@code open()} and {@code size()} are adapters.
 */
class AdapterChecksTest implements Opcodes {
    private static final String PKG = "com/blanket/adapters/modern/";
    private static final String SCREENS = PKG + "Screens";
    private static final String TARGETS = """
            { "schema": 1, "anchor": "1.21.1", "gamePackages": ["net/example/"], "obfuscatedClass": "[a-z][a-z0-9]{0,3}(\\\\$.*)?",
              "loaders": {
                "fabric": [ { "when": { "mc": "1.14..1.21.11" }, "runtime": "intermediary", "emitter": "fabric" } ],
                "forge": [ { "when": { "mc": "1.17..1.20.4" }, "runtime": "srgmoj", "emitter": "forge-modern" } ] },
              "versions": {
                "1.20.1": { "java": 17, "mappings": { "mojang": true, "intermediary": "net.fabricmc:intermediary:1.20.1:v2", "srg": "de.oceanlabs.mcp:mcp_config:1.20.1" }, "adapters": "modern" },
                "1.21.1": { "java": 21, "mappings": { "mojang": true, "intermediary": "net.fabricmc:intermediary:1.21.1:v2" }, "adapters": "modern" } },
              "supported": [ "fabric:1.21.1", "forge:1.20.1" ] }""";
    private static final String OVERRIDES = "\"com/blanket/adapters/modern/Host#method_10(Lnet/example/class_2;)V\": \"net/example/class_1\"";

    @TempDir
    Path tmp;

    ObfGameFixture game;
    Path bindings;
    Path built;
    /** The API descriptor of {@code Client.size}. */
    String sizeDesc = "()I";

    @BeforeEach
    void setUp() throws IOException {
        game = new ObfGameFixture(tmp);
        bindings = tmp.resolve("bindings");
        built = bindings.resolve("build/adapters/modern");
        Files.createDirectories(bindings.resolve("types"));
        Files.writeString(bindings.resolve("targets.json"), TARGETS, StandardCharsets.UTF_8);
        client("modern:com.blanket.adapters.Screens#open", "modern:com.blanket.adapters.Screens#size");
        put(SCREENS, screens("Screens", "Host"));
        put(PKG + "Host", host("Host", "net/example/class_1", "method_10"));
        refs(OVERRIDES);
    }

    // ---------------------------------------------------------------------------------------- fixtures

    /** An adapter class with {@code open(Game)}: {@code game.setScreen(new <host>())}, and {@code size(Game)I}. */
    private static byte[] screens(String simple, String host) {
        return ObfGameFixture.cls(PKG + simple, "java/lang/Object", ACC_PUBLIC | ACC_FINAL, w -> {
            code(w, ACC_PUBLIC | ACC_STATIC, "open", "(Lnet/example/class_4;)V", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitTypeInsn(NEW, PKG + host);
                m.visitInsn(DUP);
                m.visitMethodInsn(INVOKESPECIAL, PKG + host, "<init>", "()V", false);
                m.visitMethodInsn(INVOKEVIRTUAL, "net/example/class_4", "method_12", "(Lnet/example/class_1;)V", false);
                m.visitInsn(RETURN);
            });
            code(w, ACC_PUBLIC | ACC_STATIC, "size", "(Lnet/example/class_4;)I", m -> {
                m.visitInsn(ICONST_0);
                m.visitInsn(IRETURN);
            });
            code(w, ACC_PUBLIC | ACC_STATIC, "flush", "(Ljava/lang/Object;)V", m -> m.visitInsn(RETURN));
        });
    }

    private void client(String open, String size) throws IOException {
        Files.writeString(bindings.resolve("types/Client.json"), """
                { "schema": 1, "api": "com.blanket.api.Client",
                  "representation": [ { "when": {}, "alias": "intermediary:net/example/class_4" } ],
                  "members": {
                    "open": [ { "when": {}, "lower": { "adapter": "%s" } } ],
                    "size": [ { "when": {}, "lower": { "adapter": "%s" } } ] } }""".formatted(open, size), StandardCharsets.UTF_8);
    }

    /** A host class extending {@code sup} that declares {@code method(Painter)V}, as an override would. */
    private static byte[] host(String simple, String sup, String method) {
        return ObfGameFixture.cls(PKG + simple, sup, ACC_PUBLIC | ACC_FINAL, w -> {
            code(w, ACC_PUBLIC, "<init>", "()V", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKESPECIAL, sup, "<init>", "()V", false);
                m.visitInsn(RETURN);
            });
            code(w, ACC_PUBLIC, method, "(Lnet/example/class_2;)V", m -> m.visitInsn(RETURN));
        });
    }

    private static void code(ClassWriter w, int access, String name, String desc, Consumer<MethodVisitor> body) {
        MethodVisitor m = w.visitMethod(access, name, desc, null, null);
        m.visitCode();
        body.accept(m);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private void put(String name, byte[] bytes) throws IOException {
        Path p = built.resolve(name + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    private void refs(String overrides) throws IOException {
        Files.createDirectories(built);
        Files.writeString(built.resolve("adapter-refs.json"), """
                { "schema": 1, "set": "modern", "namespace": "intermediary", "declaring": {}, "overrides": { %s } }""".formatted(overrides),
                StandardCharsets.UTF_8);
    }

    private Report run() throws IOException {
        ApiClasses api = owner -> owner.equals("com/blanket/api/Client")
                ? Optional.of(List.of(new ApiClasses.Method("open", "()V", false), new ApiClasses.Method("size", sizeDesc, false)))
                : owner.equals("com/blanket/runtime/glue/GlueGui")
                ? Optional.of(List.of(new ApiClasses.Method("flush", "(Ljava/lang/Object;)V", true)))
                : Optional.empty();
        return new Validator(BindingSet.load(bindings), game.data(true, false), api).run();
    }

    /** The errors of a report, one per supported target, after checking they are the same failure on both. */
    private static List<Diagnostic> onBothTargets(Report r) {
        assertEquals(2, r.errors().size(), r.format());
        assertEquals(List.of("fabric:1.21.1", "forge:1.20.1"), r.errors().stream().map(Diagnostic::target).sorted().toList(), r.format());
        return r.errors();
    }

    // ---------------------------------------------------------------------------------------- tests

    @Test
    void aBuiltSetThatResolvesAndOverridesIsGreenOnBothTargets() throws IOException {
        Report r = run();
        assertTrue(r.ok(), r.format());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("unused adapter")), r.format());
    }

    private byte[] broken() {
        return ObfGameFixture.cls(PKG + "Broken", "java/lang/Object", ACC_PUBLIC | ACC_FINAL, w ->
                code(w, ACC_PUBLIC | ACC_STATIC, "missing", "(Lnet/example/class_4;)V", m -> {
                    m.visitVarInsn(ALOAD, 0);
                    m.visitMethodInsn(INVOKEVIRTUAL, "net/example/class_4", "method_99", "()V", false);
                    m.visitInsn(RETURN);
                }));
    }

    @Test
    void aGameMemberMissingFromTheTargetIsC4WhereARuleReachesIt() throws IOException {
        put(PKG + "Broken", broken());
        client("modern:com.blanket.adapters.Broken#missing", "modern:com.blanket.adapters.Screens#size");
        for (Diagnostic d : onBothTargets(run())) {
            assertEquals(ErrorCode.UNRESOLVED, d.code());
            assertEquals("adapter modern:Broken", d.member());
            assertEquals("intermediary:net/example/class_4#method_99()V", d.reference());
            assertTrue(d.detail().startsWith("C4 adapters: "), d.detail());
            assertTrue(d.nextStep().contains("lookup --ref \"intermediary:net/example/class_4#method_99()V\""), d.nextStep());
        }
    }

    /** {@code glue/GlueGui.json} with {@code flush} bound to {@code ref}; the API of {@link #run} has {@code GlueGui.flush(Object)V}. */
    private void glueFlush(String ref) throws IOException {
        Files.createDirectories(bindings.resolve("glue"));
        Files.writeString(bindings.resolve("glue/GlueGui.json"), """
                { "schema": 1, "api": "com.blanket.runtime.glue.GlueGui",
                  "members": { "flush": [ { "when": {}, "lower": { "adapter": "%s" } } ] } }""".formatted(ref), StandardCharsets.UTF_8);
    }

    @Test
    void aGlueMemberBoundToAnAdapterIsCheckedLikeAnApiMember() throws IOException {
        glueFlush("modern:com.blanket.adapters.Screens#flush");
        Report green = run();
        assertTrue(green.ok(), green.format());

        glueFlush("modern:com.blanket.adapters.Screens#gone");
        for (Diagnostic d : onBothTargets(run())) {
            assertEquals(ErrorCode.UNRESOLVED, d.code());
            assertEquals("GlueGui.flush", d.member());
            assertEquals("C4 adapters: the adapter class " + SCREENS + " has no method gone", d.detail());
        }
    }

    @Test
    void aBuiltClassNoRuleReachesIsAnUnusedWarningNotAnError() throws IOException {
        put(PKG + "Broken", broken());
        Report r = run();
        assertTrue(r.ok(), r.format());
        assertEquals(List.of("adapter modern:Broken is reached by no adapter rule on any supported target (unused adapter): C4 and C6 did not check it"),
                r.warnings().stream().filter(w -> w.contains("unused adapter")).toList(), r.format());
    }

    @Test
    void aVersionSpecificHostIsJudgedOnlyWhereItsRuleReachesIt() throws IOException {
        // as a @Stubs("1.20.1") pair: forge:1.20.1 opens through Screens1201/Host1201, whose override exists on no target
        // of this game; fabric:1.21.1 opens through Screens/Host and must stay green
        Files.writeString(bindings.resolve("types/Client.json"), """
                { "schema": 1, "api": "com.blanket.api.Client",
                  "representation": [ { "when": {}, "alias": "intermediary:net/example/class_4" } ],
                  "members": {
                    "open": [ { "when": { "loader": "forge" }, "lower": { "adapter": "modern:com.blanket.adapters.Screens1201#open" } },
                              { "when": {}, "lower": { "adapter": "modern:com.blanket.adapters.Screens#open" } } ],
                    "size": [ { "when": {}, "lower": { "adapter": "modern:com.blanket.adapters.Screens#size" } } ] } }""", StandardCharsets.UTF_8);
        put(PKG + "Screens1201", screens("Screens1201", "Host1201"));
        put(PKG + "Host1201", host("Host1201", "net/example/class_1", "method_77"));
        refs(OVERRIDES + ", \"com/blanket/adapters/modern/Host1201#method_77(Lnet/example/class_2;)V\": \"net/example/class_1\"");
        Report r = run();
        assertEquals(1, r.errors().size(), r.format());
        Diagnostic d = r.errors().get(0);
        assertEquals("forge:1.20.1", d.target());
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("adapter modern:Host1201", d.member());
        assertTrue(d.detail().startsWith("C6 overrides: Host1201.method_77(Lnet/example/class_2;)V overrides "), d.detail());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("unused adapter")), r.format());
    }

    @Test
    void anAdapterMethodWithAnotherDescriptorThanTheApiMemberIsShape() throws IOException {
        sizeDesc = "()D";
        for (Diagnostic d : onBothTargets(run())) {
            assertEquals(ErrorCode.SHAPE, d.code());
            assertEquals("Client.size", d.member());
            assertTrue(d.detail().startsWith("C4 adapters: the adapter method size has the descriptor "), d.detail());
        }
    }

    @Test
    void anAdapterRuleNamingAMethodTheClassLacksIsC4() throws IOException {
        client("modern:com.blanket.adapters.Screens#open", "modern:com.blanket.adapters.Screens#gone");
        for (Diagnostic d : onBothTargets(run())) {
            assertEquals(ErrorCode.UNRESOLVED, d.code());
            assertEquals("Client.size", d.member());
            assertEquals("C4 adapters: the adapter class " + SCREENS + " has no method gone", d.detail());
        }
    }

    @Test
    void anAdapterRuleOfAnotherSetOrMalformedIsC4() throws IOException {
        client("legacy:com.blanket.adapters.Screens#open", "modern:com.example.Screens#size");
        Report r = run();
        assertEquals(4, r.errors().size(), r.format());
        for (Diagnostic d : r.errors()) {
            if (d.member().equals("Client.open")) {
                assertEquals(ErrorCode.UNRESOLVED, d.code());
                assertTrue(d.detail().startsWith("C4 adapters: the adapter set legacy is not the set of "), d.detail());
            } else {
                assertEquals(ErrorCode.SHAPE, d.code());
                assertEquals("C4 adapters: an adapter reference is <set>:com.blanket.adapters.<Class>#<method> (binding-format.md section 5), found modern:com.example.Screens#size",
                        d.detail());
            }
        }
    }

    @Test
    void anUnbuiltSetIsAWarningNotACrash() throws IOException {
        Files.delete(built.resolve("adapter-refs.json"));
        Report r = run();
        assertTrue(r.ok(), r.format());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("forge:1.20.1: C4 adapters and C6 overrides not checked: the adapter set modern is not built")
                && w.contains("2 adapter rule(s)") && w.contains(":blanket-bindings:remapAdapters")), r.format());
    }

    @Test
    void aHostWhoseOverriddenClassIsNotASupertypeIsC6() throws IOException {
        // Host extends Painter (class_2), which has no render: the declaration overrides nothing
        put(PKG + "Host", host("Host", "net/example/class_2", "method_10"));
        for (Diagnostic d : onBothTargets(run())) {
            assertEquals(ErrorCode.UNRESOLVED, d.code());
            assertEquals("adapter modern:Host", d.member());
            assertEquals("hierarchy", d.namespaceStep());
            assertTrue(d.detail().startsWith("C6 overrides: Host.method_10(Lnet/example/class_2;)V is meant to override "), d.detail());
            assertTrue(d.detail().contains("is not a supertype of " + PKG + "Host"), d.detail());
        }
    }

    @Test
    void anOverrideOfAMethodTheTargetLacksIsC6Only() throws IOException {
        put(PKG + "Host", host("Host", "net/example/class_1", "method_77"));
        refs("\"com/blanket/adapters/modern/Host#method_77(Lnet/example/class_2;)V\": \"net/example/class_1\"");
        for (Diagnostic d : onBothTargets(run())) {
            assertEquals("adapter modern:Host", d.member());
            assertEquals("intermediary:net/example/class_1#method_77(Lnet/example/class_2;)V", d.reference());
            assertTrue(d.detail().startsWith("C6 overrides: Host.method_77(Lnet/example/class_2;)V overrides net/example/class_1.method_77"), d.detail());
        }
    }
}
