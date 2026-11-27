// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.lower.Frames;
import com.blanket.core.lower.Hierarchy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TargetHierarchy} over the obfuscated fixture jar: asked and answered in runtime names. */
class TargetHierarchyTest {
    @TempDir
    Path tmp;

    ObfGameFixture game;

    @BeforeEach
    void setUp() throws IOException {
        game = new ObfGameFixture(tmp);
    }

    /** {@code getCommonSuperClass} of the writer the lower stage uses, which is protected in ASM. */
    private static String commonSuper(Hierarchy h, String a, String b) throws ReflectiveOperationException {
        ClassWriter w = Frames.writer(h);
        Method m = ClassWriter.class.getDeclaredMethod("getCommonSuperClass", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(w, a, b);
    }

    @Test
    void commonSuperOfTwoSrgmojRuntimeClassesIsFoundInTheObfJarAndAnsweredInRuntimeNames() throws Exception {
        try (TargetHierarchy h = TargetHierarchy.open(ObfGameFixture.SRGMOJ, game.data(true, false), Map.of())) {
            assertEquals("net/example/gui/Screen", commonSuper(h, "net/example/gui/TitleScreen", "net/example/gui/OptionsScreen"));
            assertEquals(Optional.of(new Hierarchy.Facts("java/lang/Object", List.of("net/example/gui/Widget"), false)), h.get("net/example/gui/Screen"));
            assertEquals(Optional.of(new Hierarchy.Facts("java/lang/Object", List.of(), true)), h.get("net/example/gui/Widget"));
            assertEquals("java/lang/Object", commonSuper(h, "net/example/gui/TitleScreen", "net/example/Game"));
            assertEquals(Optional.empty(), h.get("d"), "an obfuscated name is no runtime name on srgmoj");
            assertEquals(Optional.empty(), h.get("mod/Main"), "mod classes are layered on top by the lower stage");
        }
    }

    @Test
    void intermediaryRuntimeAnswersIntermediaryNames() throws Exception {
        try (TargetHierarchy h = TargetHierarchy.open(ObfGameFixture.INTERMEDIARY, game.data(true, false), Map.of())) {
            assertEquals("net/example/class_1", commonSuper(h, "net/example/class_3", "net/example/class_5"));
            assertEquals(Optional.of(new Hierarchy.Facts("net/example/class_1", List.of(), false)), h.get("net/example/class_3"));
        }
    }

    @Test
    void knownPairsTranslateWithoutReadingAMappingFile() throws Exception {
        // the mapping files throw: every answer here comes from the pairs the plan's resolution gave
        Map<String, String> pairs = Map.of("net/example/gui/TitleScreen", "d", "net/example/gui/OptionsScreen", "h", "net/example/gui/Screen", "a",
                "net/example/gui/Widget", "i");
        try (TargetHierarchy h = TargetHierarchy.open(ObfGameFixture.SRGMOJ, game.data(false, false), pairs)) {
            assertEquals("net/example/gui/Screen", h.get("net/example/gui/TitleScreen").orElseThrow().superName());
            assertEquals("net/example/gui/Screen", commonSuper(h, "net/example/gui/TitleScreen", "net/example/gui/OptionsScreen"));
        }
    }

    @Test
    void jdkAndLibraryClassesAreAnsweredLibrariesLazily() throws Exception {
        try (TargetHierarchy h = TargetHierarchy.open(ObfGameFixture.SRGMOJ, game.data(true, true), Map.of())) {
            assertEquals(Optional.of(new Hierarchy.Facts("org/lib/Base", List.of(), false)), h.get("net/example/Skin"));
            assertEquals(Optional.of(new Hierarchy.Facts("java/lang/Object", List.of(), false)), h.get("org/lib/Base"));
            assertEquals("java/util/AbstractList", commonSuper(h, "java/util/ArrayList", "java/util/LinkedList"));
            assertTrue(h.get("java/util/List").orElseThrow().isInterface());
        }
        try (TargetHierarchy h = TargetHierarchy.open(ObfGameFixture.SRGMOJ, game.data(true, false), Map.of())) {
            assertEquals(Optional.empty(), h.get("org/lib/Base"), "a library the version does not list is unknown");
        }
    }

    @Test
    void sameNameRuntimesNeedNoMappingFile() throws Exception {
        var obf = new com.blanket.core.resolve.TargetSpec("forge", "1.8.9", "obf", "forge-legacy", 8, null);
        try (TargetHierarchy h = TargetHierarchy.open(obf, game.data(false, false), Map.of())) {
            assertEquals("a", commonSuper(h, "d", "h"));
            assertEquals(Optional.of(new Hierarchy.Facts("java/lang/Object", List.of("i"), false)), h.get("a"));
        }
    }
}
