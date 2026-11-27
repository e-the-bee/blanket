// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.scan.ModScanner;
import com.blanket.core.scan.Scan;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

/**
 * Class-level reachability over the embedded classes (architecture.md section 3.2, pseudocode.md P-03): which classes of
 * the embedded runtime and owned API a target's jar can run, starting from the mod's classes, the fixed roots and the
 * adapter classes the plan ships whole. Only these classes join the used set; the rest of the embedded classes (the
 * other GL backend, runtime code nothing calls) must not make RESOLVE bind members that no code of the jar reaches. EMIT
 * ships the same closure (lowering-and-adapters.md section 8, class-level shrink).
 */
public final class Reach {
    /** The runtime's event dispatcher: its reads of {@code Events.X} fire events and do not subscribe to them. */
    static final String DISPATCH = "com/blanket/runtime/Dispatch";
    /** Roots every jar runs besides the mod: the dispatcher and the boot class. The GL backend of the target is the third. */
    public static final List<String> ROOTS = List.of(DISPATCH, "com/blanket/runtime/Boot");

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
     * The embedded classes reachable from {@code mod}, {@code adapters} and {@code roots}: every class they name that
     * {@code embed} has, transitively. {@code stubs} are never entered: the compile-time API classes of aliased types are
     * not shipped. The adapter classes are shipped whole, so what they name is shipped too (an adapter that tests
     * {@code instanceof} a runtime class needs that class); RESOLVE and EMIT both call this with the plan's adapters.
     */
    public static Set<String> closure(Map<String, byte[]> mod, Map<String, byte[]> adapters, Map<String, byte[]> embed, Collection<String> roots,
                                      Set<String> stubs) {
        Set<String> seen = new HashSet<>(stubs);   // a class already "seen" is never queued
        Deque<String> work = new ArrayDeque<>();
        for (String root : roots) if (embed.containsKey(root) && seen.add(root)) work.add(root);
        for (byte[] bytes : mod.values()) visit(bytes, embed, seen, work);
        for (byte[] bytes : adapters.values()) visit(bytes, embed, seen, work);
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

    /**
     * The used set of one target: {@code classes}, the embedded classes of {@link #closure}; {@code scan}, the mod's
     * classes plus those; {@code glueCalls}, the glue stub methods ({@code owner.name}) that any of them or an adapter
     * class calls, the glue members the plan gives a body.
     */
    record Used(Set<String> classes, Scan scan, Set<String> glueCalls) {}

    /**
     * The used set from {@link #closure}. An event is used where one of the scanned classes reads {@code Events.X},
     * except {@link #DISPATCH}, which reads every event to fire it (events-and-hooks.md section 1 principle 5: wire only
     * what a subscriber uses). The adapter classes are not scanned for API members: LOWER does not lower them.
     *
     * @param glueOwners the glue stub classes (internal names)
     */
    static Used used(Map<String, byte[]> mod, Map<String, byte[]> adapters, Map<String, byte[]> embed, Collection<String> roots, Set<String> stubs,
                     Set<String> glueOwners) {
        Set<String> classes = closure(mod, adapters, embed, roots, stubs);
        Scan scan = new Scan();
        Set<String> calls = new TreeSet<>();
        for (Map.Entry<String, byte[]> e : mod.entrySet()) {
            scanClass(e.getKey(), e.getValue(), scan, true);
            glueCalls(e.getValue(), glueOwners, calls);
        }
        for (String name : new TreeSet<>(classes)) {
            scanClass(name, embed.get(name), scan, !name.equals(DISPATCH));
            glueCalls(embed.get(name), glueOwners, calls);
        }
        for (byte[] bytes : adapters.values()) glueCalls(bytes, glueOwners, calls);
        return new Used(Set.copyOf(classes), scan, Collections.unmodifiableSet(calls));
    }

    /** Adds every static method of {@code owners} the class calls, directly or through a method handle, as {@code owner.name}. */
    private static void glueCalls(byte[] bytes, Set<String> owners, Set<String> out) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owners.contains(owner)) out.add(owner + "." + name);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                        for (Object a : args) {
                            if (a instanceof Handle h && h.getTag() == Opcodes.H_INVOKESTATIC && owners.contains(h.getOwner())) {
                                out.add(h.getOwner() + "." + h.getName());
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static void scanClass(String name, byte[] bytes, Scan scan, boolean events) {
        try {
            ModScanner.scanClass(bytes, scan, events);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("cannot read class " + name + ": " + ex.getMessage(), ex);
        }
    }
}
