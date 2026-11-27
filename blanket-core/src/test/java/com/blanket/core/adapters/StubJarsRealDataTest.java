// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.testsupport.RealData;
import com.blanket.core.validate.BindingSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The stub and API view jars of the three real adapter sets (Legacy Yarn 1.8.9, Mojang 1.21.1, official 26.1.2), built
 * once from the downloaded client jars and mapping files; skipped when they cannot be downloaded. Names: R-MAP-11,
 * R-MAP-12, R-MAP-13, R-MAP-14 and binding-format.md section 8, example 6.
 */
class StubJarsRealDataTest {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static Path out;
    private static BindingSet bindings;
    private static MappingDownloads downloads;
    private static List<Path> apiClasses;

    @BeforeAll
    static void buildAll() throws Exception {
        downloads = new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));
        bindings = BindingSet.load(BINDINGS);
        apiClasses = List.of(location("com.blanket.api.ClientMod"), location("com.blanket.runtime.Dispatch"));
        out = Files.createTempDirectory("blanket-stubjars");
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        try {
            for (StubJars.AdapterSet set : StubJars.readSets(BINDINGS)) {
                StubJarsMain.build(set, bindings, downloads, apiClasses, out, log);
            }
        } catch (RuntimeException | IOException e) {
            throw RealData.skipIfOffline(e);
        }
    }

    private static Path location(String className) throws URISyntaxException, ClassNotFoundException {
        return Path.of(Class.forName(className).getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> map = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.isDirectory()) map.put(e.getName(), zip.getInputStream(e).readAllBytes());
            }
        }
        return map;
    }

    /** Field and method declarations of a class, plus {@code CODE} for every method that has a Code attribute. */
    private static List<String> declared(byte[] classFile) {
        List<String> out = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                out.add("field " + name + ":" + desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                out.add("method " + name + desc);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitCode() {
                        out.add("CODE " + name + desc);
                    }
                };
            }
        }, 0);
        return out;
    }

    private static void assertNoCode(Map<String, byte[]> classes) {
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            if (!e.getKey().endsWith(".class")) continue;
            List<String> d = declared(e.getValue());
            assertTrue(d.stream().noneMatch(s -> s.startsWith("CODE ")), e.getKey() + " keeps code: " + d);
        }
    }

    @Test
    void legacyStubHasTheLegacyYarnNameOfClass1600() throws Exception {
        Map<String, byte[]> stub = entries(out.resolve("1.8.9-yarn-legacy.jar"));
        // R-MAP-13: class_1600 is the 1.8.9 client; R-MAP-14: Legacy Yarn 1.8.9+build.604 calls it MinecraftClient
        byte[] client = stub.get("net/minecraft/client/MinecraftClient.class");
        assertNotNull(client, "no net/minecraft/client/MinecraftClient in the legacy stub");
        assertFalse(stub.containsKey("ave.class"), "the obfuscated 1.8.9 client class (R-MAP-13: obf ave) must be renamed");
        List<String> d = declared(client);
        // R-MAP-13: player is field_10310 of type class_518 (ClientPlayerEntity in R-MAP-14); getInstance is method_2965
        assertTrue(d.contains("field player:Lnet/minecraft/entity/player/ClientPlayerEntity;"), d.toString());
        assertTrue(d.contains("method getInstance()Lnet/minecraft/client/MinecraftClient;"), d.toString());
        assertNoCode(stub);
    }

    @Test
    void modernStubHasMojangMinecraftWithHitResult() throws Exception {
        Map<String, byte[]> stub = entries(out.resolve("1.21.1-mojang.jar"));
        byte[] client = stub.get("net/minecraft/client/Minecraft.class");
        assertNotNull(client, "no net/minecraft/client/Minecraft in the modern stub");
        List<String> d = declared(client);
        assertTrue(d.stream().anyMatch(s -> s.startsWith("field hitResult:")), "R-MAP-11: Minecraft.hitResult; got " + d);
        assertFalse(stub.containsKey("fgo.class"), "binding-format example 1: Mojang Minecraft is obf fgo on 1.21.1");
        assertNoCode(stub);
    }

    @Test
    void unobfStubHasTheKeyMappingCategory() throws Exception {
        Map<String, byte[]> stub = entries(out.resolve("26.1.2-official.jar"));
        assertNotNull(stub.get("net/minecraft/client/KeyMapping$Category.class"), "R-MAP-12: KeyMapping$Category is in the 26.1 jar");
        byte[] keyMapping = stub.get("net/minecraft/client/KeyMapping.class");
        assertNotNull(keyMapping);
        boolean inner = false;
        for (String s : innerClasses(keyMapping)) inner |= s.equals("net/minecraft/client/KeyMapping$Category");
        assertTrue(inner, "KeyMapping keeps its InnerClasses entry for Category");
        assertNoCode(stub);
    }

    private static List<String> innerClasses(byte[] classFile) {
        List<String> names = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                names.add(name);
            }
        }, ClassReader.SKIP_CODE);
        return names;
    }

    @Test
    void legacyApiViewDropsTheAliasTypesAndMapsThemToYarnNames() throws Exception {
        StubJars.AdapterSet legacy = StubJars.readSets(BINDINGS).get(0);
        assertEquals("legacy", legacy.name());
        Map<String, String> aliases = ApiView.aliases(bindings, ApiView.target(bindings.targets(), legacy), legacy, StubJars.stages(legacy, downloads));
        // R-MAP-14: class_518 = ClientPlayerEntity, class_1600 = MinecraftClient
        assertEquals("net/minecraft/entity/player/ClientPlayerEntity", aliases.get("com/blanket/api/Player"));
        assertEquals("net/minecraft/client/MinecraftClient", aliases.get("com/blanket/api/Client"));

        Map<String, byte[]> view = entries(out.resolve("legacy-api.jar"));
        assertFalse(view.containsKey("com/blanket/api/Player.class"));
        assertFalse(view.containsKey("com/blanket/api/Client.class"));
        assertTrue(view.containsKey("com/blanket/api/ClientMod.class"), "owned and plain API types stay");
        assertTrue(view.containsKey("com/blanket/runtime/Dispatch.class"), "blanket-runtime classes are in the view");
        assertNoCode(view);
    }

    @Test
    void everySetHasAViewAndALibraryList() throws Exception {
        for (String set : List.of("legacy", "modern", "unobf")) {
            assertTrue(entries(out.resolve(set + "-api.jar")).containsKey("com/blanket/api/ClientMod.class"), set);
            List<String> libs = Files.readAllLines(out.resolve(set + "-libs.txt"));
            assertFalse(libs.isEmpty(), set + " has no libraries");
            for (String lib : libs) assertTrue(Files.isRegularFile(Path.of(lib)), set + ": " + lib + " is not a file");
        }
        for (String set : List.of("legacy", "modern")) {
            assertTrue(Files.readAllLines(out.resolve(set + "-libs.txt")).stream().anyMatch(l -> l.contains("guava")),
                    set + ": the version JSON lists Guava, which game signatures mention");
        }
    }
}
