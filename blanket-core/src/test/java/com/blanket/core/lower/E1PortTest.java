// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Experiment E1 (research-log R-EXP-01, lowering-and-adapters.md section 2) as a test of {@link Lower}: a mod compiled
 * by javac with {@code --release 8} against a fake API calls {@code Player.x()} directly, in a lambda, through a method
 * reference, in an anonymous {@code ToDoubleFunction<Player>} (with javac's generic bridge method) and in a
 * {@code List<Player>} loop. It is lowered once to {@code INVOKEVIRTUAL Entity.getX()D} and once to
 * {@code GETFIELD Entity.posX D}, and each result runs in a forked JVM with {@code -Xverify:all}: on the test JDK always,
 * and on JDK 8 when {@code BLANKET_JDK8} names a JDK 8 home (the gate run sets it).
 */
class E1PortTest implements Opcodes {
    static final String PLAYER = "com/blanket/api/Player";
    static final String X = PLAYER + ".x()D";
    static final List<String> EXPECTED = List.of("direct=1.5", "lambda=2.5", "ref=1.5", "anon=3.0", "loop=3.5");

    @TempDir
    Path tmp;

    static final Map<String, String> SOURCES = Map.of(
            "com/blanket/api/Player.java", """
                    package com.blanket.api;
                    public final class Player {
                        private Player() {}
                        public double x() { throw new AssertionError(); }
                    }""",
            "game/Entity.java", """
                    package game;
                    public class Entity {
                        public double posX;
                        public double getX() { return posX; }
                    }""",
            "game/LocalPlayer.java", """
                    package game;
                    public class LocalPlayer extends Entity {
                        public LocalPlayer(double x) { posX = x; }
                    }""",
            "mod/E1.java", """
                    package mod;

                    import com.blanket.api.Player;
                    import java.util.List;
                    import java.util.function.ToDoubleFunction;

                    public final class E1 {
                        public static double direct(Player p) { return p.x(); }

                        public static double lambda(Player p) {
                            ToDoubleFunction<Player> f = q -> q.x() + 1;
                            return f.applyAsDouble(p);
                        }

                        public static double ref(Player p) {
                            ToDoubleFunction<Player> f = Player::x;
                            return f.applyAsDouble(p);
                        }

                        public static double anon(Player p) {
                            ToDoubleFunction<Player> f = new ToDoubleFunction<Player>() {
                                @Override
                                public double applyAsDouble(Player q) { return q.x() * 2; }
                            };
                            return f.applyAsDouble(p);
                        }

                        public static double loop(List<Player> players) {
                            double sum = 0;
                            for (Player p : players) sum += p.x();
                            return sum;
                        }
                    }""");

    /** Compiled after lowering, against the lowered classes: E1's methods take {@code game.LocalPlayer} now. */
    static final String MAIN = """
            package run;

            import game.LocalPlayer;
            import java.util.Arrays;
            import mod.E1;

            public final class Main {
                public static void main(String[] args) {
                    LocalPlayer p = new LocalPlayer(1.5);
                    System.out.println("direct=" + E1.direct(p));
                    System.out.println("lambda=" + E1.lambda(p));
                    System.out.println("ref=" + E1.ref(p));
                    System.out.println("anon=" + E1.anon(p));
                    System.out.println("loop=" + E1.loop(Arrays.asList(p, new LocalPlayer(2.0))));
                }
            }""";

    // ---------------------------------------------------------------------------------------- helpers

    /** javac {@code --release 8} of {@code sources} (relative path to text) into {@code out}, with {@code classpath}. */
    static void javac(Path dir, Map<String, String> sources, Path out, Path classpath) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            files.add(p);
        }
        Files.createDirectories(out);
        List<String> options = new ArrayList<>(List.of("--release", "8", "-proc:none", "-Xlint:-options", "-d", out.toString()));
        if (classpath != null) options.addAll(List.of("-cp", classpath.toString()));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        java.io.StringWriter log = new java.io.StringWriter();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            assertTrue(javac.getTask(log, fm, null, options, null, fm.getJavaFileObjectsFromPaths(files)).call(), log.toString());
        }
    }

    static Map<String, byte[]> read(Path dir) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                out.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
            }
        }
        return out;
    }

    static void write(Path dir, Map<String, byte[]> classes) throws IOException {
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            Path p = dir.resolve(e.getKey() + ".class");
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
    }

    /** {@code classes} first, then the running JDK. */
    static Hierarchy hierarchy(Map<String, byte[]> classes) {
        return new Hierarchy() {
            @Override
            public Optional<Facts> get(String name) {
                byte[] b = classes.get(name);
                if (b == null) {
                    try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                        if (in == null) return Optional.empty();
                        b = in.readAllBytes();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                ClassReader r = new ClassReader(b);
                return Optional.of(new Facts(r.getSuperName(), List.of(r.getInterfaces()), (r.getAccess() & ACC_INTERFACE) != 0));
            }

            @Override
            public void close() {}
        };
    }

    /** Runs {@code run.Main} from {@code classes} with {@code java -Xverify:all}; returns its output lines. */
    static List<String> runVerified(Path java, Path classes) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(java.toString(), "-Xverify:all", "-cp", classes.toString(), "run.Main")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "java did not finish: " + out);
        assertEquals(0, p.exitValue(), java + " -Xverify:all failed:\n" + out);
        List<String> lines = new ArrayList<>();
        for (String l : out.split("\\R")) {
            if (l.matches("[a-z]+=.*")) lines.add(l);
        }
        assertEquals(EXPECTED, lines, out);
        return lines;
    }

    /** The test JDK's java, and JDK 8's when {@code BLANKET_JDK8} is set. */
    static List<Path> javas() {
        List<Path> out = new ArrayList<>();
        out.add(Path.of(System.getProperty("java.home"), "bin", "java"));
        String jdk8 = System.getenv("BLANKET_JDK8");
        if (jdk8 != null && !jdk8.isBlank()) {
            Path java = Path.of(jdk8, "bin", "java");
            assertTrue(Files.isRegularFile(java), "BLANKET_JDK8=" + jdk8 + " has no bin/java");
            out.add(java);
        }
        return out;
    }

    /** Compiles the inputs, lowers the mod with {@code x}'s lowering, runs it verified; returns the lowered classes. */
    Map<String, byte[]> lowerAndRun(String variant, Lowered x) throws IOException, InterruptedException {
        Path input = tmp.resolve("input");
        if (!Files.isDirectory(input)) javac(tmp.resolve("src"), SOURCES, input, null);
        Map<String, byte[]> all = read(input);
        Map<String, byte[]> game = new TreeMap<>();
        Map<String, byte[]> mod = new TreeMap<>();
        all.forEach((name, bytes) -> {
            if (name.startsWith("game/")) game.put(name, bytes);
            else if (name.startsWith("mod/")) mod.put(name, bytes);
        });
        assertEquals(List.of("mod/E1", "mod/E1$1"), List.copyOf(mod.keySet()));

        Plan plan = LowerFixtures.plan(Map.of(PLAYER, "game/LocalPlayer"), Map.of(X, x));
        Map<String, byte[]> lowered = Lower.lower(plan, "mod", mod, Map.of(), hierarchy(game));
        assertEquals(mod.keySet(), lowered.keySet());

        Path run = tmp.resolve("run-" + variant);
        write(run, game);
        write(run, lowered);
        javac(tmp.resolve("main-src-" + variant), Map.of("run/Main.java", MAIN), run, run);
        for (Path java : javas()) runVerified(java, run);
        return lowered;
    }

    static MethodNode method(ClassNode n, String name) {
        return n.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name));
    }

    static Handle implHandle(MethodNode m) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof InvokeDynamicInsnNode indy) return (Handle) indy.bsmArgs[1];
        }
        throw new AssertionError("no invokedynamic in " + m.name);
    }

    // ---------------------------------------------------------------------------------------- tests

    @Test
    void invokeLoweringRunsVerified() throws IOException, InterruptedException {
        Map<String, byte[]> out = lowerAndRun("invoke", new Lowered.Inline(List.of(Op.invoke(INVOKEVIRTUAL, "game/Entity", "getX", "()D", false))));
        ClassNode e1 = LowerFixtures.node(out.get("mod/E1"));
        // a single invoke with the API descriptor after remapping: the method reference points at the game method itself
        Handle h = implHandle(method(e1, "ref"));
        assertEquals(new Handle(H_INVOKEVIRTUAL, "game/Entity", "getX", "()D", false), h);
        assertEquals("(Lgame/LocalPlayer;)D", method(e1, "direct").desc);
        assertTrue(method(e1, "loop").signature.contains("Ljava/util/List<Lgame/LocalPlayer;>;"), method(e1, "loop").signature);
    }

    @Test
    void getLoweringRunsVerifiedThroughABridge() throws IOException, InterruptedException {
        Map<String, byte[]> out = lowerAndRun("get", new Lowered.Inline(List.of(Op.field(GETFIELD, "game/Entity", "posX", "D"))));
        ClassNode e1 = LowerFixtures.node(out.get("mod/E1"));
        // a field read cannot be a lambda implementation (LambdaConversionException in E1): the handle goes to a bridge
        assertEquals(new Handle(H_INVOKESTATIC, "mod/E1", "blanket$get$Player$x", "(Lgame/LocalPlayer;)D", false), implHandle(method(e1, "ref")));
        boolean reads = false;
        for (AbstractInsnNode i : method(e1, "direct").instructions) {
            reads |= i instanceof FieldInsnNode f && f.getOpcode() == GETFIELD && f.owner.equals("game/Entity") && f.name.equals("posX");
        }
        assertTrue(reads, "direct reads Entity.posX");
        ClassNode anon = LowerFixtures.node(out.get("mod/E1$1"));
        // javac's generic bridge (Object)D and the real method (LocalPlayer)D stay apart after remapping
        List<String> descs = new ArrayList<>();
        for (MethodNode m : anon.methods) if (m.name.equals("applyAsDouble")) descs.add(m.desc);
        assertEquals(List.of("(Lgame/LocalPlayer;)D", "(Ljava/lang/Object;)D"), descs.stream().sorted().toList());
    }
}
