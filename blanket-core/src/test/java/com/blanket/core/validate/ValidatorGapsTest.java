// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.Lowering;
import com.blanket.core.lookup.LookupFixture;
import com.blanket.core.resolve.ClassIndex;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two gaps of C3 and the jar step: chains with {@code const} and {@code newarray} steps (D-056), and library classes
 * (JOML) that live in a jar beside the client jar. Local fixtures only: a client jar and a one-class library jar built
 * with ASM, on the invented game of {@code src/test/resources/lookup}.
 */
class ValidatorGapsTest implements Opcodes {
    private static final String GAPS = "com.blanket.test.Gaps";
    private static final String MATRIX = "org/joml/Matrix4f";

    @TempDir
    Path tmp;

    // ---------------------------------------------------------------------------------------- fixtures

    private static void method(ClassWriter w, int access, String name, String desc, int pushOp, int retOp) {
        MethodVisitor m = w.visitMethod(access, name, desc, null, null);
        m.visitCode();
        m.visitInsn(pushOp);
        m.visitInsn(retOp);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void put(ZipOutputStream z, String name, ClassWriter w) throws IOException {
        z.putNextEntry(new ZipEntry(name + ".class"));
        z.write(w.toByteArray());
        z.closeEntry();
    }

    /** Client jar of the invented game: Player {@code e} extends Base {@code k}, with {@code n(D)D} and {@code h()I}. */
    private Path clientJar() throws IOException {
        Path jar = tmp.resolve("client.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassWriter a = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            a.visit(V17, ACC_PUBLIC, "a", null, "java/lang/Object", null);
            a.visitEnd();
            put(z, "a", a);
            ClassWriter e = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            e.visit(V17, ACC_PUBLIC, "e", null, "k", null);
            method(e, ACC_PUBLIC, "n", "(D)D", DCONST_0, DRETURN);
            method(e, ACC_PUBLIC, "h", "()I", ICONST_0, IRETURN);
            e.visitEnd();
            put(z, "e", e);
            ClassWriter k = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            k.visit(V17, ACC_PUBLIC, "k", null, "java/lang/Object", null);
            k.visitEnd();
            put(z, "k", k);
        }
        return jar;
    }

    /** A JOML jar with {@code Matrix4f}; {@code withGet} switches {@code get([F)[F} on. */
    private Path jomlJar(boolean withGet) throws IOException {
        Path jar = tmp.resolve("joml-" + withGet + ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            w.visit(V17, ACC_PUBLIC, MATRIX, null, "java/lang/Object", null);
            method(w, ACC_PUBLIC, "m00", "()F", FCONST_0, FRETURN);
            if (withGet) method(w, ACC_PUBLIC, "get", "([F)[F", ACONST_NULL, ARETURN);
            w.visitEnd();
            put(z, MATRIX, w);
        }
        return jar;
    }

    /** The client jar, and the library jar when there is one. The library jar is returned for every class asked for. */
    private static final class Data implements TargetData {
        private final FixtureGame.Data game;
        private final Path library;

        Data(Path client, Path library) {
            this.game = new FixtureGame.Data(client);
            this.library = library;
        }

        @Override
        public Resolver.MappingFiles mappingFiles() {
            return game.mappingFiles();
        }

        @Override
        public List<Path> classpath(TargetSpec target) {
            return game.classpath(target);
        }

        @Override
        public boolean hasLoaderJar(TargetSpec target) {
            return false;
        }

        @Override
        public boolean coversLibraries(TargetSpec target) {
            return library != null;
        }

        @Override
        public List<Path> libraryJars(TargetSpec target, Set<String> owners) {
            return library != null && owners.contains(MATRIX) ? List.of(library) : List.of();
        }

        @Override
        public Optional<Path> mojangMappings(String mc) {
            return Optional.empty();
        }

        @Override
        public Optional<String> mcpLicense() {
            return Optional.empty();
        }
    }

    /** One glue member of the invented API type: {@code desc} is its JVM descriptor, {@code lower} its lowering. */
    private Report run(Path library, String desc, String lower) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "bindings");
        Files.writeString(dir.resolve("targets.json"), ValidatorTest.targets("neoforge:1.21.1"), StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("glue"));
        Files.writeString(dir.resolve("glue/Gaps.json"), """
                { "schema": 1, "api": "%s", "members": { "member": [ { "when": {}, "lower": %s } ] } }""".formatted(GAPS, lower), StandardCharsets.UTF_8);
        ApiClasses api = internal -> internal.equals(GAPS.replace('.', '/'))
                ? Optional.of(List.of(new ApiClasses.Method("member", desc, true)))
                : Optional.empty();
        return new Validator(BindingSet.load(dir), new Data(clientJar(), library), api).run();
    }

    private static final String SCALED = """
            { "chain": [ { "cast": "net.example.Player" }, { "const": 2.0 }, { "invoke": "net.example.Player#getX(D)D" } ] }""";
    private static final String MATRIX_ARRAY = """
            { "chain": [ { "cast": "org.joml.Matrix4f" }, { "newarray": "F", "length": 16 }, { "invoke": "org.joml.Matrix4f#get([F)[F" } ] }""";

    // ---------------------------------------------------------------------------------------- const

    @Test
    void constFeedingAOneArgumentInvokePasses() throws IOException {
        Report r = run(null, "(Ljava/lang/Object;)D", SCALED);
        assertTrue(r.ok(), r.format());
    }

    @Test
    void constChainWhoseResultIsNotTheApiResultIsShape() throws IOException {
        Report r = run(null, "(Ljava/lang/Object;)I", SCALED);
        Diagnostic d = ValidatorTest.only(r);
        assertEquals(ErrorCode.SHAPE, d.code());
        assertEquals("neoforge:1.21.1", d.target());
        assertTrue(d.detail().contains("result D"), d.detail());
    }

    /** The parser already refuses a const the invoke cannot take, so C3 is shown the check with a chain the parser never saw. */
    @Test
    void constOfTheWrongTypeIsShape() throws Exception {
        Constructor<Lowering> make = Lowering.class.getDeclaredConstructor(Lowering.Kind.class, String.class, JsonPrimitive.class, List.class);
        make.setAccessible(true);
        Lowering cast = make.newInstance(Lowering.Kind.CAST, "net.example.Player", null, null);
        Lowering text = make.newInstance(Lowering.Kind.CONST, null, new JsonPrimitive("two"), null);
        Lowering call = Lowering.parse(JsonParser.parseString("{\"invoke\": \"net.example.Player#getX(D)D\"}"), "t", "$");
        Lowering chain = make.newInstance(Lowering.Kind.CHAIN, null, null, List.of(cast, text, call));

        Map<String, Resolver.Resolved> resolved = Map.of(
                "net.example.Player", new Resolver.Resolved("net.example.Player", "e", null, null, false),
                "net.example.Player#getX(D)D", new Resolver.Resolved("net.example.Player#getX(D)D", "e", "n", "(D)D", false));
        Function<String, Resolver.Resolved> lookup = resolved::get;
        try (ClassIndex index = ClassIndex.open(List.of(clientJar()), false)) {
            Shapes shapes = new Shapes(index, Map.of());
            String problem = shapes.check(new ApiClasses.Method("member", "(Ljava/lang/Object;)D", true), "com/blanket/test/Gaps", chain, lookup);
            assertNotNull(problem);
            assertTrue(problem.contains("argument 0 is D") && problem.contains("const step 2"), problem);

            Lowering number = make.newInstance(Lowering.Kind.CONST, null, new JsonPrimitive(2.5), null);
            Lowering good = make.newInstance(Lowering.Kind.CHAIN, null, null, List.of(cast, number, call));
            assertNull(shapes.check(new ApiClasses.Method("member", "(Ljava/lang/Object;)D", true), "com/blanket/test/Gaps", good, lookup));
        }
    }

    // ---------------------------------------------------------------------------------------- newarray and libraries

    @Test
    void newArrayFeedingMatrixGetPasses() throws IOException {
        Report r = run(jomlJar(true), "(Ljava/lang/Object;)[F", MATRIX_ARRAY);
        assertTrue(r.ok(), r.format());
    }

    @Test
    void newArrayChainWhoseResultIsNotTheApiResultIsShape() throws IOException {
        Report r = run(jomlJar(true), "(Ljava/lang/Object;)F", MATRIX_ARRAY);
        Diagnostic d = ValidatorTest.only(r);
        assertEquals(ErrorCode.SHAPE, d.code());
        assertTrue(d.detail().contains("result [F"), d.detail());
    }

    @Test
    void libraryMemberAbsentFromTheLibraryJarIsUnresolvedAtTheJarStep() throws IOException {
        Report r = run(jomlJar(false), "(Ljava/lang/Object;)[F", MATRIX_ARRAY);
        Diagnostic d = ValidatorTest.only(r);
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("jar", d.namespaceStep());
        assertEquals("org.joml.Matrix4f#get([F)[F", d.reference());
        assertTrue(d.detail().contains("org/joml/Matrix4f.get([F)[F"), d.detail());
    }

    @Test
    void libraryClassWithoutAnyLibraryJarIsUnresolvedAtTheJarStep() throws IOException {
        // the data covers libraries but none of them provides org/joml/Matrix4f
        Path dir = Files.createDirectories(tmp.resolve("empty"));
        Path none = dir.resolve("none.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(none))) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.closeEntry();
        }
        Report r = run(none, "(Ljava/lang/Object;)[F", MATRIX_ARRAY);
        assertTrue(r.errors().stream().anyMatch(d -> d.code() == ErrorCode.UNRESOLVED && "jar".equals(d.namespaceStep())), r.format());
    }

    @Test
    void libraryPackagePrefixesFollowTheMavenCoordinates() {
        assertEquals(List.of("org/joml/"), DownloadedTargetData.prefixes("org.joml", "joml"));
        assertEquals(List.of("org/lwjgl/glfw/"), DownloadedTargetData.prefixes("org.lwjgl", "lwjgl-glfw"));
        assertEquals(List.of("org/lwjgl/"), DownloadedTargetData.prefixes("org.lwjgl", "lwjgl"));
        assertEquals(List.of("com/mojang/brigadier/"), DownloadedTargetData.prefixes("com.mojang", "brigadier"));
    }

    // ---------------------------------------------------------------------------------------- glue Object parameters (D-056 D)

    /** Class {@code p} with {@code q(Lr;)Lr;} and class {@code r}: a camera-like and a matrix-like type with invented names. */
    private Path cameraJar() throws IOException {
        Path jar = tmp.resolve("camera.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassWriter p = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            p.visit(V17, ACC_PUBLIC, "p", null, "java/lang/Object", null);
            method(p, ACC_PUBLIC, "q", "(Lr;)Lr;", ACONST_NULL, ARETURN);
            p.visitEnd();
            put(z, "p", p);
            ClassWriter r = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            r.visit(V17, ACC_PUBLIC, "r", null, "java/lang/Object", null);
            r.visitEnd();
            put(z, "r", r);
        }
        return jar;
    }

    private String objectsAgainstAnInvoke(String apiOwner) throws IOException {
        String ref = "fake.Camera#q(Lfake/Matrix;)Lfake/Matrix;";
        Lowering invoke = Lowering.parse(JsonParser.parseString("{\"invoke\": \"" + ref + "\"}"), "t", "$");
        Resolver.Resolved r = new Resolver.Resolved(ref, "p", "q", "(Lr;)Lr;", false);
        try (ClassIndex index = ClassIndex.open(List.of(cameraJar()), false)) {
            return new Shapes(index, Map.of()).check(
                    new ApiClasses.Method("member", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true), apiOwner, invoke, text -> r);
        }
    }

    @Test
    void glueObjectParametersTakeAnyReferenceOfTheLoweredInvoke() throws IOException {
        assertNull(objectsAgainstAnInvoke("com/blanket/runtime/glue/GlueFake"));
    }

    @Test
    void objectParametersOfANonGlueMemberStayExact() throws IOException {
        String problem = objectsAgainstAnInvoke("com/blanket/api/Fake");
        assertNotNull(problem);
        assertTrue(problem.contains("parameter 0"), problem);
    }
}
