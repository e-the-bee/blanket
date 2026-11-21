// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.cli;

import com.blanket.core.lookup.Matrix;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.ModScanner;
import com.blanket.core.validate.ApiClasses;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import com.blanket.core.validate.ModCheck;
import com.blanket.core.validate.TargetData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1 T11: {@code blanket check} and {@code blanket explain} on a fixture binding set, fixture mapping files and a fixture client jar. */
class CliTest {

    private static final String[] FIXTURE = {"targets.json", "hooks.json", "types/Client.json", "types/Player.json", "events/ClientTick.json"};
    private static final String PLAYER_X = "\"invoke\": \"net.example.Player#getX()D\"";

    /**
     * The fixture game: the mapping files, by the resolver's file keys, and a client jar with the obfuscated classes the
     * mapping files name ({@code a} Client: field {@code b}, {@code c()V}; {@code d} Player: field {@code e}, {@code f()D},
     * {@code g(La;I)V}).
     */
    private record FixtureData(Path clientJar) implements TargetData {
        @Override
        public Resolver.MappingFiles mappingFiles() {
            return key -> switch (key) {
                case "pg:1.21.1" -> resource("mapping/proguard.txt");
                case "int:1.21.1" -> resource("mapping/intermediary.tiny");
                default -> throw new IOException("no fixture mapping file " + key);
            };
        }

        @Override
        public List<Path> classpath(TargetSpec target) {
            return List.of(clientJar);
        }

        @Override
        public boolean hasLoaderJar(TargetSpec target) {
            return false;
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

    private static Path clientJar(Path dir) throws IOException {
        ClassWriter client = new ClassWriter(0);
        client.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a", null, "java/lang/Object", null);
        client.visitField(Opcodes.ACC_PUBLIC, "b", "Ld;", null, null).visitEnd();
        method(client, "c", "()V");
        client.visitEnd();
        ClassWriter player = new ClassWriter(0);
        player.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "d", null, "java/lang/Object", null);
        player.visitField(Opcodes.ACC_PUBLIC, "e", "D", null, null).visitEnd();
        method(player, "f", "()D");
        method(player, "g", "(La;I)V");
        player.visitEnd();
        Path jar = dir.resolve("client.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry("a.class"));
            j.write(client.toByteArray());
            j.closeEntry();
            j.putNextEntry(new JarEntry("d.class"));
            j.write(player.toByteArray());
            j.closeEntry();
        }
        return jar;
    }

    /** A public abstract method: the class index reads only names, descriptors and access. */
    private static void method(ClassWriter cw, String name, String desc) {
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null).visitEnd();
    }

    private static Path resource(String name) throws IOException {
        try {
            return Path.of(CliTest.class.getResource("/fixture/" + name).toURI());
        } catch (Exception e) {
            throw new IOException("missing fixture " + name, e);
        }
    }

    /** Copies the fixture bindings into {@code dir}; {@code from} is replaced by {@code to} in Player.json. */
    private static Path bindings(Path dir, String from, String to) throws IOException {
        Path root = dir.resolve("bindings");
        for (String f : FIXTURE) {
            String text = Files.readString(resource(f), StandardCharsets.UTF_8);
            if (f.equals("types/Player.json")) text = text.replace(from, to);
            Files.createDirectories(root.resolve(f).getParent());
            Files.writeString(root.resolve(f), text, StandardCharsets.UTF_8);
        }
        return root;
    }

    /** A mod with four API uses: Client.tick(), Client.player(), Player.x() and the CLIENT_TICK_END event. */
    private static Path modJar(Path dir, String extraClient) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "mod/Main", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lcom/blanket/api/Client;)D", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/blanket/api/Client", "tick", "()V", false);
        if (extraClient != null) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/blanket/api/Client", extraClient, "()V", false);
        }
        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/blanket/api/Events", "CLIENT_TICK_END", "Lcom/blanket/api/EventSource;");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/blanket/api/Client", "player", "()Lcom/blanket/api/Player;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/blanket/api/Player", "x", "()D", false);
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        Path jar = dir.resolve("mod.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry("mod/Main.class"));
            j.write(cw.toByteArray());
            j.closeEntry();
        }
        return jar;
    }

    /** The errors {@code ModCheck} reports for the mod on {@code fabric:1.21.1}, with the fixture game. */
    private static List<Diagnostic> check(Path dir, Path bindingsDir, Path jar) throws IOException {
        BindingSet set = BindingSet.load(bindingsDir);
        TargetSpec t = Matrix.spec(set.targets(), "fabric:1.21.1");
        return ModCheck.run(set, new FixtureData(clientJar(dir)), ApiClasses.of(CliTest.class.getClassLoader()), ModScanner.scan(jar), List.of(t)).errors();
    }

    private record Run(int exit, String out, String err) {}

    private static Run run(Path dir, String... args) throws IOException {
        Path jar = clientJar(dir);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                (bindings, offline) -> new FixtureData(jar));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void checkPassesOnTheFixtureBindings(@TempDir Path dir) throws IOException {
        Path b = bindings(dir, "x", "x");
        Path jar = modJar(dir, null);
        assertEquals(List.of(), check(dir, b, jar));
        Run r = run(dir, "check", "--targets", "fabric:1.21.1", "--bindings", b.toString(), jar.toString());
        assertEquals(0, r.exit(), r.out() + r.err());
        assertTrue(r.out().startsWith("check passed"), r.out());
    }

    @Test
    void brokenBindingReportsUnresolvedNamingTargetAndNamespaceStep(@TempDir Path dir) throws IOException {
        Path b = bindings(dir, PLAYER_X, "\"invoke\": \"net.example.Player#missing()D\"");
        Path jar = modJar(dir, null);

        List<Diagnostic> ds = check(dir, b, jar);
        assertEquals(1, ds.size(), ds.toString());
        Diagnostic d = ds.get(0);
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("fabric:1.21.1", d.target());
        assertEquals("Player.x", d.member());
        assertEquals("net.example.Player#missing()D", d.reference());
        // the step is the mapping file and the namespaces it translates between: Mojang names to obfuscated names
        assertEquals("pg:1.21.1|named|obf", d.namespaceStep());

        Run r = run(dir, "check", "--targets", "fabric:1.21.1", "--bindings", b.toString(), jar.toString());
        assertEquals(1, r.exit(), r.out() + r.err());
        assertTrue(r.out().contains(d.format()), r.out());
        assertTrue(r.out().contains("check failed: 1 error(s)"), r.out());
    }

    @Test
    void memberWithoutRuleIsUnbound(@TempDir Path dir) throws IOException {
        Path b = bindings(dir, "x", "x");
        List<Diagnostic> ds = check(dir, b, modJar(dir, "jump"));
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(ErrorCode.UNBOUND, ds.get(0).code());
        assertEquals("Client.jump", ds.get(0).member());
        assertEquals("fabric:1.21.1", ds.get(0).target());
    }

    @Test
    void loweringWithAnotherStackEffectIsShape(@TempDir Path dir) throws IOException {
        // jump(Client, int) takes three stack values with its receiver; Player.x() takes one
        Path b = bindings(dir, PLAYER_X, "\"invoke\": \"net.example.Player#jump(Lnet/example/Client;I)V\"");
        List<Diagnostic> ds = check(dir, b, modJar(dir, null));
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(ErrorCode.SHAPE, ds.get(0).code());
        assertEquals("Player.x", ds.get(0).member());
        // shapes are compared in the names of the client jar: Player is d, Client is a
        assertTrue(ds.get(0).detail().contains("(Ld;)D"), ds.get(0).detail());
        assertTrue(ds.get(0).detail().contains("(Ld;La;I)V"), ds.get(0).detail());
    }

    @Test
    void aClassTheJarLacksIsUnresolvedAtTheJarStep(@TempDir Path dir) throws IOException {
        // the jar presence check is core C2: the mapping files name the classes, the client jar must hold them
        Path b = bindings(dir, "x", "x");
        Path jar = modJar(dir, null);
        BindingSet set = BindingSet.load(b);
        TargetSpec t = Matrix.spec(set.targets(), "fabric:1.21.1");
        Path empty = dir.resolve("empty-client.jar");
        try (OutputStream out = Files.newOutputStream(empty); JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry("readme.txt"));
            j.write(new byte[0]);
            j.closeEntry();
        }
        List<Diagnostic> ds = ModCheck.run(set, new FixtureData(empty), ApiClasses.of(CliTest.class.getClassLoader()), ModScanner.scan(jar), List.of(t)).errors();
        assertTrue(!ds.isEmpty(), ds.toString());
        for (Diagnostic d : ds) {
            assertEquals(ErrorCode.UNRESOLVED, d.code(), d.format());
            assertEquals("jar", d.namespaceStep(), d.format());
            assertEquals("fabric:1.21.1", d.target());
        }
    }

    @Test
    void explainPrintsTheRouteAndTheRuntimeName(@TempDir Path dir) throws IOException {
        Path b = bindings(dir, "x", "x");
        Run r = run(dir, "explain", "Player.x", "--target", "fabric:1.21.1", "--bindings", b.toString());
        assertEquals(0, r.exit(), r.out() + r.err());
        assertTrue(r.out().contains("Player.x on fabric:1.21.1"), r.out());
        assertTrue(r.out().contains("rule: $.members.x[0]"), r.out());
        assertTrue(r.out().contains("reference net.example.Player#getX()D"), r.out());
        // each hop: the namespaces and the mapping file, in order
        int first = r.out().indexOf("named -> obf  pg:1.21.1");
        int second = r.out().indexOf("obf -> id  int:1.21.1");
        assertTrue(first >= 0 && second > first, r.out());
        assertTrue(r.out().contains("runtime name: class_2.method_5()D"), r.out());
    }

    @Test
    void explainOfABrokenBindingNamesTheFailingStep(@TempDir Path dir) throws IOException {
        Path b = bindings(dir, PLAYER_X, "\"invoke\": \"net.example.Player#missing()D\"");
        Run r = run(dir, "explain", "Player.x", "--target", "fabric:1.21.1", "--bindings", b.toString());
        assertEquals(1, r.exit(), r.out() + r.err());
        assertTrue(r.out().contains("failed at step pg:1.21.1|named|obf"), r.out());
    }

    @Test
    void usageErrorsExitTwo(@TempDir Path dir) throws IOException {
        assertEquals(2, run(dir).exit());
        assertEquals(2, run(dir, "check", "--bindings", "nowhere").exit());
        Path b = bindings(dir, "x", "x");
        Run r = run(dir, "explain", "Player.x", "--target", "fabric:1.7", "--bindings", b.toString());
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("fabric:1.7"), r.err());
    }
}
