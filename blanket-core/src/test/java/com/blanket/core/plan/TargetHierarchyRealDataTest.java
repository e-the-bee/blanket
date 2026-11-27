// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.lookup.Matrix;
import com.blanket.core.lower.Frames;
import com.blanket.core.lower.Hierarchy;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.testsupport.RealData;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.DownloadedTargetData;
import com.blanket.core.validate.TargetData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TargetHierarchy} on the real client jars (downloaded into the Blanket cache; skipped offline). Expected values
 * are {@code javap} on the real jars, translated with the real mapping files (recorded in the RES2 report):
 * <ul>
 * <li>1.21.1: ProGuard {@code LocalPlayer -> geb}, {@code RemotePlayer -> gec}, {@code AbstractClientPlayer -> gdy},
 * {@code Player -> cmx}; intermediary {@code geb -> class_746}, {@code gec -> class_745}, {@code gdy -> class_742},
 * {@code cmx -> class_1657}; {@code javap geb}: {@code public class geb extends gdy}, {@code javap gec}:
 * {@code public class gec extends gdy}, {@code javap gdy}: {@code public abstract class gdy extends cmx}.</li>
 * <li>1.8.9: Legacy Yarn {@code ClientPlayerEntity -> class_518}, {@code OtherClientPlayerEntity -> class_519},
 * {@code AbstractClientPlayerEntity -> class_1631}; LF intermediary {@code class_518 -> bew}, {@code class_519 -> bex},
 * {@code class_1631 -> bet}, {@code class_988 -> wn}; {@code javap bew}: {@code public class bew extends bet},
 * {@code javap bex}: {@code public class bex extends bet}, {@code javap bet}: {@code public abstract class bet extends wn}.</li>
 * </ul>
 */
class TargetHierarchyRealDataTest {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");

    private static String commonSuper(Hierarchy h, String a, String b) throws ReflectiveOperationException {
        ClassWriter w = Frames.writer(h);
        Method m = ClassWriter.class.getDeclaredMethod("getCommonSuperClass", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(w, a, b);
    }

    private static TargetHierarchy open(String id) throws IOException {
        BindingSet bindings = BindingSet.load(BINDINGS);
        MappingDownloads downloads = new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));
        TargetData data = new DownloadedTargetData(downloads, bindings.targets(), BINDINGS);
        TargetSpec t = Matrix.supported(bindings.targets()).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        try {
            return TargetHierarchy.open(t, data, Map.of());
        } catch (IOException | RuntimeException e) {
            throw RealData.skipIfOffline(e);
        }
    }

    private static Optional<Hierarchy.Facts> get(Hierarchy h, String name) {
        try {
            return h.get(name);
        } catch (RuntimeException e) {
            throw RealData.skipIfOffline(e);
        }
    }

    @Test
    void fabric1211AnswersInIntermediaryNames() throws Exception {
        try (TargetHierarchy h = open("fabric:1.21.1")) {
            assertEquals(Optional.of(new Hierarchy.Facts("net/minecraft/class_742", List.of(), false)), get(h, "net/minecraft/class_746"));
            assertEquals("net/minecraft/class_1657", get(h, "net/minecraft/class_742").orElseThrow().superName());
            assertEquals("net/minecraft/class_742", commonSuper(h, "net/minecraft/class_746", "net/minecraft/class_745"));
            assertEquals(Optional.empty(), get(h, "geb"), "the jar name is no runtime name on fabric");
        }
    }

    @Test
    void forge189AnswersInObfuscatedNames() throws Exception {
        try (TargetHierarchy h = open("forge:1.8.9")) {
            assertEquals(Optional.of(new Hierarchy.Facts("bet", List.of(), false)), get(h, "bew"));
            assertEquals("wn", get(h, "bet").orElseThrow().superName());
            assertEquals("bet", commonSuper(h, "bew", "bex"));
        }
    }

    @Test
    void legacyFabric189AnswersInLegacyIntermediaryNames() throws Exception {
        try (TargetHierarchy h = open("legacy-fabric:1.8.9")) {
            assertEquals(Optional.of(new Hierarchy.Facts("net/minecraft/class_1631", List.of(), false)), get(h, "net/minecraft/class_518"));
            assertEquals("net/minecraft/class_988", get(h, "net/minecraft/class_1631").orElseThrow().superName());
            assertEquals("net/minecraft/class_1631", commonSuper(h, "net/minecraft/class_518", "net/minecraft/class_519"));
        }
    }
}
