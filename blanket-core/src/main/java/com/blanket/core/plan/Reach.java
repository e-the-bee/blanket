// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.scan.ModScanner;
import com.blanket.core.scan.Scan;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

/**
 * Class-level reachability over the embedded classes (architecture.md section 3.2, pseudocode.md P-03): which classes of
 * the embedded runtime and owned API a target's jar can run, starting from the mod's classes and the fixed roots. Only
 * these classes join the used set; the rest of the embedded classes (the other GL backend, runtime code nothing calls)
 * must not make RESOLVE bind members that no code of the jar reaches. EMIT ships the same closure
 * (lowering-and-adapters.md section 8, class-level shrink).
 */
public final class Reach {
    /** Roots every jar runs besides the mod: the dispatcher and the boot class. The GL backend of the target is the third. */
    public static final List<String> ROOTS = List.of("com/blanket/runtime/Dispatch", "com/blanket/runtime/Boot");

    private Reach() {}

    /** The class files of a jar or a class directory by internal name; {@code module-info} and {@code META-INF/} are skipped. */
    static Map<String, byte[]> read(Path jarOrDir) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (Files.isDirectory(jarOrDir)) {
            try (Stream<Path> files = Files.walk(jarOrDir)) {
                for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                    add(out, jarOrDir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
            return out;
        }
        try (InputStream in = Files.newInputStream(jarOrDir); JarInputStream jar = new JarInputStream(in)) {
            for (JarEntry e; (e = jar.getNextJarEntry()) != null; ) {
                if (!e.isDirectory()) add(out, e.getName(), jar.readAllBytes());
            }
        }
        return out;
    }

    private static void add(Map<String, byte[]> out, String path, byte[] bytes) {
        if (!path.endsWith(".class") || path.startsWith("META-INF/") || path.endsWith("module-info.class")) return;
        out.put(path.substring(0, path.length() - ".class".length()), bytes);
    }

    /**
     * The embedded classes reachable from {@code mod} and {@code roots}: every class they name that {@code embed} has,
     * transitively. {@code stubs} are never entered: the compile-time API classes of aliased types are not shipped.
     */
    public static Set<String> closure(Map<String, byte[]> mod, Map<String, byte[]> embed, Collection<String> roots, Set<String> stubs) {
        Set<String> seen = new HashSet<>(stubs);   // a class already "seen" is never queued
        Deque<String> work = new ArrayDeque<>();
        for (String root : roots) if (embed.containsKey(root) && seen.add(root)) work.add(root);
        for (byte[] bytes : mod.values()) visit(bytes, embed, seen, work);
        while (!work.isEmpty()) visit(embed.get(work.poll()), embed, seen, work);
        seen.removeAll(stubs);
        return seen;
    }

    /** Queues every embedded class the class file names. */
    private static void visit(byte[] bytes, Map<String, byte[]> embed, Set<String> seen, Deque<String> work) {
        Remapper names = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                if (embed.containsKey(internalName) && seen.add(internalName)) work.add(internalName);
                return internalName;
            }
        };
        // the remapper only visits what its delegate hands visitors for, so the delegate is a real writer whose output is dropped
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), names), 0);
    }

    /** The used set of one target: the mod's classes plus the embedded classes reachable from them and from {@code roots}. */
    static Scan scan(Map<String, byte[]> mod, Map<String, byte[]> embed, Collection<String> roots, Set<String> stubs) {
        Scan scan = new Scan();
        for (Map.Entry<String, byte[]> e : mod.entrySet()) scanClass(e.getKey(), e.getValue(), scan);
        for (String name : closure(mod, embed, roots, stubs)) scanClass(name, embed.get(name), scan);
        return scan;
    }

    private static void scanClass(String name, byte[] bytes, Scan scan) {
        try {
            ModScanner.scanClass(bytes, scan);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("cannot read class " + name + ": " + ex.getMessage(), ex);
        }
    }
}
