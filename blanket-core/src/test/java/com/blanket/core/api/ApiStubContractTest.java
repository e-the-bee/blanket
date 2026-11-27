// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The compiled {@code blanket-api} keeps the stub contract (api-surface.md section 1 rule 5): every class without
 * {@code @Owned} is a final alias stub with private constructors and bodies that throw
 * {@code new AssertionError("blanket stub")}; and its public surface is exactly the member list of sections 3.1 to 3.10.
 */
class ApiStubContractTest {
    private static final String PACKAGE = "com/blanket/api/";
    private static final String OWNED = "Lcom/blanket/api/Owned;";

    /** Alias types (sections 3.2 to 3.7 and 3.9): method keys are {@code [static ]name+descriptor}, enum constants {@code field NAME}. */
    private static final Map<String, Set<String>> ALIASES = new TreeMap<>();
    /** The owned types of sections 3.8 to 3.11 that T1 writes, and Screen (3.10 without add/remove). */
    private static final Map<String, Set<String>> OWNED_TYPES = new TreeMap<>();

    static {
        ALIASES.put("Client", Set.of(
                "static get()Lcom/blanket/api/Client;",
                "player()Lcom/blanket/api/Player;",
                "world()Lcom/blanket/api/World;",
                "guiWidth()I",
                "guiHeight()I",
                "guiScale()D",
                "openScreen(Lcom/blanket/api/Screen;)V",
                "closeScreen()V",
                "isScreenOpen()Z",
                "crosshairTarget()Lcom/blanket/api/Target;",
                "showMessage(Ljava/lang/String;)V",
                "static runOnClientThread(Ljava/lang/Runnable;)V",
                "translate(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"));
        ALIASES.put("Player", Set.of(
                "x()D",
                "y()D",
                "z()D",
                "position()Lcom/blanket/api/Vec3;",
                "yaw()F",
                "pitch()F",
                "onGround()Z",
                "health()F",
                "maxHealth()F",
                "foodLevel()I",
                "saturation()F",
                "mainHandItem()Lcom/blanket/api/ItemStack;",
                "offHandItem()Lcom/blanket/api/ItemStack;",
                "selectedSlot()I",
                "inventoryItem(I)Lcom/blanket/api/ItemStack;",
                "boundingBox()Lcom/blanket/api/Box;"));
        ALIASES.put("World", Set.of(
                "blockState(Lcom/blanket/api/BlockPos;)Lcom/blanket/api/BlockState;",
                "gameTime()J",
                "dimension()Ljava/lang/String;"));
        ALIASES.put("BlockState", Set.of(
                "blockId()Ljava/lang/String;",
                "isAir()Z",
                "propertyValue(Ljava/lang/String;)Ljava/lang/String;"));
        ALIASES.put("ItemStack", Set.of(
                "isEmpty()Z",
                "count()I",
                "itemId()Ljava/lang/String;",
                "displayName()Ljava/lang/String;"));
        ALIASES.put("BlockPos", Set.of(
                "static of(III)Lcom/blanket/api/BlockPos;",
                "x()I",
                "y()I",
                "z()I",
                "offset(Lcom/blanket/api/Direction;)Lcom/blanket/api/BlockPos;",
                "offset(III)Lcom/blanket/api/BlockPos;"));
        ALIASES.put("Vec3", Set.of(
                "static of(DDD)Lcom/blanket/api/Vec3;",
                "x()D",
                "y()D",
                "z()D",
                "add(Lcom/blanket/api/Vec3;)Lcom/blanket/api/Vec3;",
                "subtract(Lcom/blanket/api/Vec3;)Lcom/blanket/api/Vec3;",
                "scale(D)Lcom/blanket/api/Vec3;",
                "length()D",
                "distanceTo(Lcom/blanket/api/Vec3;)D"));
        ALIASES.put("Box", Set.of(
                "static of(DDDDDD)Lcom/blanket/api/Box;",
                "minX()D",
                "minY()D",
                "minZ()D",
                "maxX()D",
                "maxY()D",
                "maxZ()D",
                "inflate(D)Lcom/blanket/api/Box;",
                "center()Lcom/blanket/api/Vec3;"));
        ALIASES.put("Direction", Set.of(
                "field DOWN",
                "field UP",
                "field NORTH",
                "field SOUTH",
                "field WEST",
                "field EAST",
                "opposite()Lcom/blanket/api/Direction;"));
        ALIASES.put("KeyBinding", Set.of(
                "static register(Ljava/lang/String;Lcom/blanket/api/Key;Ljava/lang/String;)Lcom/blanket/api/KeyBinding;",
                "consumePress()Z",
                "isDown()Z"));

        OWNED_TYPES.put("Target", Set.of(
                "kind()Lcom/blanket/api/TargetKind;",
                "blockPos()Lcom/blanket/api/BlockPos;",
                "side()Lcom/blanket/api/Direction;",
                "location()Lcom/blanket/api/Vec3;",
                "static block(Lcom/blanket/api/BlockPos;Lcom/blanket/api/Direction;Lcom/blanket/api/Vec3;)Lcom/blanket/api/Target;",
                "static entity(Lcom/blanket/api/Vec3;)Lcom/blanket/api/Target;",
                "static miss(Lcom/blanket/api/Vec3;)Lcom/blanket/api/Target;"));
        OWNED_TYPES.put("TargetKind", Set.of(
                "field MISS",
                "field BLOCK",
                "field ENTITY"));
        OWNED_TYPES.put("Key", Set.of(
                "field A",
                "field B",
                "field C",
                "field D",
                "field E",
                "field F",
                "field G",
                "field H",
                "field I",
                "field J",
                "field K",
                "field L",
                "field M",
                "field N",
                "field O",
                "field P",
                "field Q",
                "field R",
                "field S",
                "field T",
                "field U",
                "field V",
                "field W",
                "field X",
                "field Y",
                "field Z",
                "field NUM_0",
                "field NUM_1",
                "field NUM_2",
                "field NUM_3",
                "field NUM_4",
                "field NUM_5",
                "field NUM_6",
                "field NUM_7",
                "field NUM_8",
                "field NUM_9",
                "field F1",
                "field F2",
                "field F3",
                "field F4",
                "field F5",
                "field F6",
                "field F7",
                "field F8",
                "field F9",
                "field F10",
                "field F11",
                "field F12",
                "field ESCAPE",
                "field ENTER",
                "field SPACE",
                "field TAB",
                "field BACKSPACE",
                "field LEFT",
                "field RIGHT",
                "field UP",
                "field DOWN",
                "field LEFT_SHIFT",
                "field RIGHT_SHIFT",
                "field LEFT_CONTROL",
                "field RIGHT_CONTROL",
                "field LEFT_ALT",
                "field RIGHT_ALT",
                "field NONE"));
        OWNED_TYPES.put("Input", Set.of(
                "static isKeyDown(Lcom/blanket/api/Key;)Z"));
        OWNED_TYPES.put("Blanket", Set.of(
                "static supports(Lcom/blanket/api/Feature;)Z"));
        OWNED_TYPES.put("Feature", Set.of(
                "field OFFHAND"));
        OWNED_TYPES.put("Screen", Set.of(
                "init()V",
                "render(Lcom/blanket/api/render/Canvas2D;IIF)V",
                "mouseClicked(DDI)Z",
                "mouseReleased(DDI)Z",
                "mouseScrolled(DDD)Z",
                "mouseDragged(DDIDD)Z",
                "keyPressed(Lcom/blanket/api/Key;)Z",
                "charTyped(C)Z",
                "onClose()V",
                "pausesGame()Z",
                "width()I",
                "height()I",
                "close()V",
                "attach(IILjava/lang/Runnable;)V",
                "title()Ljava/lang/String;",
                "runInit()V"));
        OWNED_TYPES.put("ScreenKind", Set.of(
                "field NONE",
                "field TITLE",
                "field PAUSE",
                "field CHAT",
                "field INVENTORY",
                "field CONTAINER",
                "field OTHER"));
        OWNED_TYPES.put("ScreenOpen", Set.of(
                "kind()Lcom/blanket/api/ScreenKind;",
                "isModScreen()Z",
                "set(Lcom/blanket/api/ScreenKind;Z)Lcom/blanket/api/ScreenOpen;"));
    }

    @Test
    void aliasSurfaceMatchesTheSpecification() throws Exception {
        Map<String, ClassNode> classes = classes();
        for (Map.Entry<String, Set<String>> e : ALIASES.entrySet()) {
            ClassNode n = classes.get(PACKAGE + e.getKey());
            assertNotNull(n, e.getKey() + " is missing from blanket-api");
            assertEquals(new TreeSet<>(e.getValue()), surface(n), e.getKey() + " members");
        }
    }

    @Test
    void ownedSurfaceMatchesTheSpecification() throws Exception {
        Map<String, ClassNode> classes = classes();
        for (Map.Entry<String, Set<String>> e : OWNED_TYPES.entrySet()) {
            ClassNode n = classes.get(PACKAGE + e.getKey());
            assertNotNull(n, e.getKey() + " is missing from blanket-api");
            assertTrue(isOwned(n), e.getKey() + " must carry @Owned");
            assertEquals(new TreeSet<>(e.getValue()), surface(n), e.getKey() + " members");
        }
    }

    @Test
    void everyClassWithoutOwnedIsAFinalAliasStub() throws Exception {
        Set<String> stubs = new TreeSet<>();
        for (ClassNode n : classes().values()) {
            if (isOwned(n) || n.name.contains("$")) continue; // nested and anonymous classes follow their outer class
            stubs.add(n.name.substring(PACKAGE.length()));
            assertTrue((n.access & Opcodes.ACC_FINAL) != 0, n.name + " must be final");
            assertTrue((n.access & Opcodes.ACC_INTERFACE) == 0, n.name + " must be a class or an enum, or carry @Owned");
            boolean isEnum = (n.access & Opcodes.ACC_ENUM) != 0;
            for (FieldNode f : n.fields) {
                assertTrue((f.access & (Opcodes.ACC_ENUM | Opcodes.ACC_SYNTHETIC)) != 0, n.name + "." + f.name + " is a field of an alias type");
            }
            for (MethodNode m : n.methods) {
                if (m.name.equals("<init>")) {
                    assertTrue((m.access & Opcodes.ACC_PRIVATE) != 0, n.name + " constructor must be private");
                } else if (isEnum && (m.name.equals("<clinit>") || m.name.equals("values") || m.name.equals("valueOf")
                        || (m.access & Opcodes.ACC_SYNTHETIC) != 0)) {
                    continue; // what javac writes into every enum
                } else {
                    assertTrue(throwsBlanketStub(m), n.name + "." + m.name + m.desc + " must be `throw new AssertionError(\"blanket stub\")`");
                }
            }
        }
        assertEquals(new TreeSet<>(ALIASES.keySet()), stubs, "the alias types of blanket-api");
    }

    // ---------------------------------------------------------------------------------------- reading the classes

    private static boolean isOwned(ClassNode n) {
        List<org.objectweb.asm.tree.AnnotationNode> all = new ArrayList<>();
        if (n.invisibleAnnotations != null) all.addAll(n.invisibleAnnotations);
        if (n.visibleAnnotations != null) all.addAll(n.visibleAnnotations);
        return all.stream().anyMatch(a -> a.desc.equals(OWNED));
    }

    /** Public and protected methods (no constructors) and enum constants; the methods javac adds to enums are not API. */
    private static Set<String> surface(ClassNode n) {
        Set<String> out = new TreeSet<>();
        boolean isEnum = (n.access & Opcodes.ACC_ENUM) != 0;
        for (FieldNode f : n.fields) {
            if ((f.access & Opcodes.ACC_ENUM) != 0) out.add("field " + f.name);
        }
        for (MethodNode m : n.methods) {
            boolean visible = (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                    && (m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
            if (!visible || m.name.startsWith("<")) continue;
            if (isEnum && (m.name.equals("values") || m.name.equals("valueOf"))) continue;
            out.add(((m.access & Opcodes.ACC_STATIC) != 0 ? "static " : "") + m.name + m.desc);
        }
        return out;
    }

    /** The body is exactly {@code new AssertionError("blanket stub"); athrow}. */
    private static boolean throwsBlanketStub(MethodNode m) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions) {
            if (i.getOpcode() >= 0) code.add(i);
        }
        return code.size() == 5
                && code.get(0) instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals("java/lang/AssertionError")
                && code.get(1).getOpcode() == Opcodes.DUP
                && code.get(2) instanceof LdcInsnNode l && "blanket stub".equals(l.cst)
                && code.get(3) instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL
                && c.owner.equals("java/lang/AssertionError") && c.name.equals("<init>") && c.desc.equals("(Ljava/lang/Object;)V")
                && code.get(4).getOpcode() == Opcodes.ATHROW;
    }

    /** Every class of {@code com.blanket.api} (including sub-packages), read from wherever the classpath has blanket-api. */
    private static Map<String, ClassNode> classes() throws IOException, URISyntaxException {
        Path location = Path.of(com.blanket.api.Client.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, ClassNode> out = new TreeMap<>();
        if (Files.isDirectory(location)) {
            readAll(location, out);
        } else {
            try (FileSystem jar = FileSystems.newFileSystem(location)) {
                readAll(jar.getPath("/"), out);
            }
        }
        return out;
    }

    private static void readAll(Path root, Map<String, ClassNode> out) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(root.resolve("com/blanket/api"))) {
            files = s.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
        }
        for (Path p : files) {
            try (InputStream in = Files.newInputStream(p)) {
                ClassNode n = new ClassNode();
                new ClassReader(in).accept(n, 0);
                out.put(n.name, n);
            }
        }
    }
}
