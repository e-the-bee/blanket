// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.resolve.TargetSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdapterClosure} on the invented obfuscated game of {@link ObfGameFixture}, with a built adapter set
 * {@code modern} in intermediary shipping names, planned for an intermediary runtime and an srgmoj-like runtime.
 */
class AdapterClosureTest implements Opcodes {
    private static final String PKG = "com/blanket/adapters/modern/";
    private static final String SCREENS = PKG + "Screens";
    private static final String HOST = PKG + "Host";
    private static final String API_SCREEN = "com/blanket/api/Screen";

    @TempDir
    Path tmp;

    ObfGameFixture game;
    Path bindings;
    Path built;

    @BeforeEach
    void setUp() throws IOException {
        game = new ObfGameFixture(tmp);
        bindings = tmp.resolve("bindings");
        built = bindings.resolve("build/adapters/modern");
        put(SCREENS, screens());
        put(HOST, host());
        put(PKG + "Unused", ObfGameFixture.cls(PKG + "Unused", "net/example/class_2", ACC_PUBLIC | ACC_FINAL, w -> {}));
        // Broken uses a member the game does not have; only a plan that names it pulls it into the closure
        put(PKG + "Broken", ObfGameFixture.cls(PKG + "Broken", "java/lang/Object", ACC_PUBLIC | ACC_FINAL, w ->
                code(w, "missing", "(Lnet/example/class_4;)V", m -> {
                    m.visitVarInsn(ALOAD, 0);
                    m.visitMethodInsn(INVOKEVIRTUAL, "net/example/class_4", "method_99", "()V", false);
                    m.visitInsn(RETURN);
                })));
        refs("""
                { "schema": 1, "set": "modern", "namespace": "intermediary",
                  "declaring": {
                    "net/example/class_3#field_20:I": "net/example/class_1",
                    "net/example/class_3#method_10(Lnet/example/class_2;)V": "net/example/class_1",
                    "com/blanket/adapters/modern/Host#field_20:I": "net/example/class_1",
                    "net/example/class_4#hashCode()I": "java/lang/Object" },
                  "overrides": { "com/blanket/adapters/modern/Host#method_10(Lnet/example/class_2;)V": "net/example/class_1" } }""");
    }

    // ---------------------------------------------------------------------------------------- built adapters (shipping names)

    /** Screens: the adapter methods the plan names. */
    private static byte[] screens() {
        return ObfGameFixture.cls(SCREENS, "java/lang/Object", ACC_PUBLIC | ACC_FINAL, w -> {
            // open(Game, Screen): game.setScreen(new Host(screen)) -- adapter-to-adapter reference
            code(w, "open", "(Lnet/example/class_4;L" + API_SCREEN + ";)V", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitTypeInsn(NEW, HOST);
                m.visitInsn(DUP);
                m.visitVarInsn(ALOAD, 1);
                m.visitMethodInsn(INVOKESPECIAL, HOST, "<init>", "(L" + API_SCREEN + ";)V", false);
                m.visitMethodInsn(INVOKEVIRTUAL, "net/example/class_4", "method_12", "(Lnet/example/class_1;)V", false);
                m.visitMethodInsn(INVOKESTATIC, "net/example/class_4", "method_11", "()Lnet/example/class_4;", false);
                m.visitMethodInsn(INVOKEVIRTUAL, "net/example/class_4", "tick", "()V", false);   // no mapping row anywhere
                m.visitInsn(RETURN);
            });
            // width(TitleScreen): a field read through the subclass; the row is on class_1
            code(w, "width", "(Lnet/example/class_3;)I", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitFieldInsn(GETFIELD, "net/example/class_3", "field_20", "I");
                m.visitInsn(IRETURN);
            });
            // paint(TitleScreen, Painter): a call through the subclass
            code(w, "paint", "(Lnet/example/class_3;Lnet/example/class_2;)V", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitVarInsn(ALOAD, 1);
                m.visitMethodInsn(INVOKEVIRTUAL, "net/example/class_3", "method_10", "(Lnet/example/class_2;)V", false);
                m.visitInsn(RETURN);
            });
            // handle(): a method handle constant and a JDK-declared member called on a game class
            code(w, "handle", "(Lnet/example/class_4;)Ljava/lang/Object;", m -> {
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, "net/example/class_4", "hashCode", "()I", false);
                m.visitInsn(POP);
                m.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, "net/example/class_4", "method_12", "(Lnet/example/class_1;)V", false));
                m.visitInsn(ARETURN);
            });
        });
    }

    /** Host extends the game's Screen, overrides render, reads an inherited field through itself, carries an invisible annotation. */
    private static byte[] host() {
        return ObfGameFixture.cls(HOST, "net/example/class_1", ACC_PUBLIC | ACC_FINAL, w -> {
            w.visitAnnotation("L" + PKG + "Stubs;", false).visitEnd();   // compile-only, not in the built set
            w.visitField(ACC_PRIVATE | ACC_FINAL, "screen", "L" + API_SCREEN + ";", null, null).visitEnd();
            MethodVisitor init = w.visitMethod(ACC_PUBLIC, "<init>", "(L" + API_SCREEN + ";)V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "net/example/class_1", "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitFieldInsn(PUTFIELD, HOST, "screen", "L" + API_SCREEN + ";");
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor render = w.visitMethod(ACC_PUBLIC, "method_10", "(Lnet/example/class_2;)V", null, null);
            render.visitCode();
            render.visitVarInsn(ALOAD, 0);
            render.visitFieldInsn(GETFIELD, HOST, "field_20", "I");
            render.visitInsn(POP);
            render.visitInsn(RETURN);
            render.visitMaxs(0, 0);
            render.visitEnd();
        });
    }

    private static void code(ClassWriter w, String name, String desc, Consumer<MethodVisitor> body) {
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
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

    private void refs(String json) throws IOException {
        Files.createDirectories(built);
        Files.writeString(built.resolve(AdapterClosure.REFS_FILE), json, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------- running it

    /** The plan's adapter members (API names), as RESOLVE plans them. */
    private static Map<String, Lowered> members(String... extra) {
        Map<String, Lowered> m = new LinkedHashMap<>();
        m.put("com/blanket/api/Client.openScreen(Lcom/blanket/api/Screen;)V",
                new Lowered.Adapter(SCREENS, "open", "(Lcom/blanket/api/Client;Lcom/blanket/api/Screen;)V"));
        m.put("com/blanket/api/Title.width()I", new Lowered.Adapter(SCREENS, "width", "(Lcom/blanket/api/Title;)I"));
        m.put("com/blanket/api/Title.paint(Lcom/blanket/api/Painter;)V",
                new Lowered.Adapter(SCREENS, "paint", "(Lcom/blanket/api/Title;Lcom/blanket/api/Painter;)V"));
        m.put("com/blanket/api/Client.handle()Ljava/lang/Object;", new Lowered.Adapter(SCREENS, "handle", "(Lcom/blanket/api/Client;)Ljava/lang/Object;"));
        for (int i = 0; i < extra.length; i += 3) {
            m.put(extra[i], new Lowered.Adapter(extra[i + 1].contains("/") ? extra[i + 1].substring(0, extra[i + 1].indexOf('#')) : SCREENS,
                    extra[i + 1].substring(extra[i + 1].indexOf('#') + 1), extra[i + 2]));
        }
        return m;
    }

    private Map<String, byte[]> close(TargetSpec t, Map<String, Lowered> members, Map<String, String> types) throws IOException {
        try (Names names = new Names(t, ObfGameFixture.GAME)) {
            AdapterClosure c = AdapterClosure.collect(bindings, t, ObfGameFixture.GAME, ObfGameFixture.ANCHOR, members, names);
            names.resolve(game.data(true, false));
            try (TargetHierarchy h = TargetHierarchy.open(t, game.data(true, false), names.runtimeToJar())) {
                return c.remap(types, names, h);
            }
        }
    }

    private static final Map<String, String> SRGMOJ_TYPES = Map.of("com/blanket/api/Client", "net/example/Game",
            "com/blanket/api/Title", "net/example/gui/TitleScreen", "com/blanket/api/Painter", "net/example/gui/Painter");
    private static final Map<String, String> INTERMEDIARY_TYPES = Map.of("com/blanket/api/Client", "net/example/class_4",
            "com/blanket/api/Title", "net/example/class_3", "com/blanket/api/Painter", "net/example/class_2");

    private static ClassNode node(byte[] bytes) {
        ClassNode n = new ClassNode();
        new ClassReader(bytes).accept(n, 0);
        return n;
    }

    private static MethodNode method(ClassNode c, String name) {
        for (MethodNode m : c.methods) if (m.name.equals(name)) return m;
        throw new AssertionError("no method " + name + " in " + c.name);
    }

    /** The member instructions and constants of a method, one line each: {@code OWNER.name desc}. */
    private static List<String> refsOf(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode mi) out.add(mi.owner + "." + mi.name + mi.desc);
            if (i instanceof FieldInsnNode fi) out.add(fi.owner + "." + fi.name + ":" + fi.desc);
            if (i instanceof LdcInsnNode l && l.cst instanceof Handle h) out.add("handle " + h.getOwner() + "." + h.getName() + h.getDesc());
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------- tests

    @Test
    void srgmojRuntimeResolvesSubclassOwnedRefsThroughTheDeclaringClassAndRenamesOverrides() throws IOException {
        Map<String, byte[]> out = close(ObfGameFixture.SRGMOJ, members(), SRGMOJ_TYPES);
        assertEquals(List.of(HOST, SCREENS), List.copyOf(out.keySet()), "the closure: roots plus Host, which Screens names; Unused is left out");

        ClassNode screens = node(out.get(SCREENS));
        assertEquals("(Lnet/example/Game;Lcom/blanket/api/Screen;)V", method(screens, "open").desc);
        assertEquals(List.of(HOST + ".<init>(Lcom/blanket/api/Screen;)V", "net/example/Game.m_12_(Lnet/example/gui/Screen;)V",
                "net/example/Game.m_11_()Lnet/example/Game;", "net/example/Game.tick()V"), refsOf(method(screens, "open")));
        // the bytecode owner stays the subclass; the name comes from the declaring class's row
        assertEquals(List.of("net/example/gui/TitleScreen.f_20_:I"), refsOf(method(screens, "width")));
        assertEquals(List.of("net/example/gui/TitleScreen.m_10_(Lnet/example/gui/Painter;)V"), refsOf(method(screens, "paint")));
        assertEquals(List.of("net/example/Game.hashCode()I", "handle net/example/Game.m_12_(Lnet/example/gui/Screen;)V"), refsOf(method(screens, "handle")));

        ClassNode host = node(out.get(HOST));
        assertEquals("net/example/gui/Screen", host.superName);
        assertEquals("(Lnet/example/gui/Painter;)V", method(host, "m_10_").desc, "the override declaration has the overridden method's runtime name");
        assertEquals(List.of(HOST + ".f_20_:I"), refsOf(method(host, "m_10_")));
        assertEquals(List.of("net/example/gui/Screen.<init>()V", HOST + ".screen:Lcom/blanket/api/Screen;"), refsOf(method(host, "<init>")));
        assertNull(host.invisibleAnnotations, "compile-time annotations are dropped");

        for (Map.Entry<String, byte[]> e : out.entrySet()) {
            assertFalse(new String(e.getValue(), StandardCharsets.ISO_8859_1).contains("class_"), e.getKey() + " keeps a shipping name");
        }
    }

    @Test
    void intermediaryRuntimeKeepsTheShippingNamesAndChecksThemInTheJar() throws IOException {
        Map<String, byte[]> out = close(ObfGameFixture.INTERMEDIARY, members(), INTERMEDIARY_TYPES);
        assertEquals(List.of(HOST, SCREENS), List.copyOf(out.keySet()));
        ClassNode screens = node(out.get(SCREENS));
        assertEquals(List.of("net/example/class_3.field_20:I"), refsOf(method(screens, "width")));
        assertEquals(List.of("net/example/class_3.method_10(Lnet/example/class_2;)V"), refsOf(method(screens, "paint")));
        ClassNode host = node(out.get(HOST));
        assertEquals("net/example/class_1", host.superName);
        assertEquals("(Lnet/example/class_2;)V", method(host, "method_10").desc);
    }

    @Test
    void anAdapterDescriptorThatIsNotTheApiMembersIsShape() {
        Map<String, Lowered> m = members("com/blanket/api/Title.width()D", "width", "(Lcom/blanket/api/Title;)D");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> close(ObfGameFixture.SRGMOJ, m, SRGMOJ_TYPES));
        assertTrue(e.getMessage().startsWith("SHAPE forge:1.20.1 Title.width ref modern:Screens#width: the adapter method width has the descriptor "
                + "(Lnet/example/gui/TitleScreen;)I on forge:1.20.1; the API member needs (Lnet/example/gui/TitleScreen;)D"), e.getMessage());
    }

    @Test
    void aGameMemberMissingOnTheTargetFailsWithTheResolversStep() {
        Map<String, Lowered> m = members("com/blanket/api/Client.missing()V", PKG + "Broken#missing", "(Lcom/blanket/api/Client;)V");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> close(ObfGameFixture.SRGMOJ, m, SRGMOJ_TYPES));
        assertTrue(e.getMessage().startsWith("UNRESOLVED forge:1.20.1 adapter modern:Broken ref intermediary:net/example/class_4#method_99()V at int:1.20.1|id|obf:"),
                e.getMessage());
        assertTrue(e.getMessage().contains("has no row in int:1.20.1"), e.getMessage());
    }

    @Test
    void anUnbuiltSetFailsWithTheTaskThatBuildsIt() throws IOException {
        Files.delete(built.resolve(AdapterClosure.REFS_FILE));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> close(ObfGameFixture.SRGMOJ, members(), SRGMOJ_TYPES));
        assertTrue(e.getMessage().startsWith("UNRESOLVED forge:1.20.1 Client.openScreen ref modern:adapter-refs.json: the adapter set modern is not built"), e.getMessage());
        assertTrue(e.getMessage().contains(":blanket-bindings:remapAdapters"), e.getMessage());
    }

    @Test
    void declaringFollowsAdapterOwnersToTheGameClass() {
        AdapterClosure.Refs refs = new AdapterClosure.Refs("modern", "intermediary",
                Map.of(PKG + "Sub#method_10(Lnet/example/class_2;)V", HOST, "net/example/class_3#hashCode()I", "java/lang/Object"),
                Map.of(HOST + "#method_10(Lnet/example/class_2;)V", "net/example/class_1"));
        assertEquals("net/example/class_1", AdapterClosure.declaring(refs, PKG + "Sub", "method_10", "(Lnet/example/class_2;)V", false, ObfGameFixture.GAME));
        assertEquals("net/example/class_4", AdapterClosure.declaring(refs, "net/example/class_4", "method_12", "(Lnet/example/class_1;)V", false, ObfGameFixture.GAME));
        assertNull(AdapterClosure.declaring(refs, "net/example/class_3", "hashCode", "()I", false, ObfGameFixture.GAME), "a JDK member keeps its name");
        assertNull(AdapterClosure.declaring(refs, HOST, "own", "()V", false, ObfGameFixture.GAME), "an adapter's own member is no game member");
    }
}
