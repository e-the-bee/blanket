// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The supertypes and declared members of the classes an adapter set is compiled against, in authoring names: the
 * set's own compiled classes, the era stub jar (the game), then library jars and the running JDK. Classes are read
 * lazily by entry name, without code, and kept.
 */
final class ClassIndex implements Closeable {
    /** Where a class comes from. */
    enum Origin { ADAPTER, GAME, LIBRARY }

    /** One class: its supertypes and the access flags of its members by {@code name+desc} (methods) and {@code name:desc} (fields). */
    record Info(String name, Origin origin, String superName, List<String> interfaces,
                Map<String, Integer> methods, Map<String, Integer> fields) {}

    private final Map<String, byte[]> adapters;
    private final ZipFile game;
    private final List<ZipFile> libraries = new ArrayList<>();
    private final Map<String, Info> cache = new HashMap<>();

    /**
     * @param adapters the set's compiled classes by internal name
     * @param game     the era stub jar
     * @param libraries library jars (the API view jar, the game's libraries); the running JDK is searched last
     */
    ClassIndex(Map<String, byte[]> adapters, Path game, List<Path> libraries) throws IOException {
        this.adapters = adapters;
        this.game = new ZipFile(game.toFile());
        try {
            for (Path lib : libraries) this.libraries.add(new ZipFile(lib.toFile()));
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** The class, or null when no source holds it. */
    Info get(String name) {
        if (cache.containsKey(name)) return cache.get(name);
        Info info = load(name);
        cache.put(name, info);
        return info;
    }

    boolean isGame(String name) {
        Info i = get(name);
        return i != null && i.origin() == Origin.GAME;
    }

    private Info load(String name) {
        byte[] bytes = adapters.get(name);
        if (bytes != null) return read(bytes, Origin.ADAPTER);
        try {
            bytes = entry(game, name);
            if (bytes != null) return read(bytes, Origin.GAME);
            for (ZipFile lib : libraries) {
                bytes = entry(lib, name);
                if (bytes != null) return read(bytes, Origin.LIBRARY);
            }
            // the boot layer's classes; a resource name ending in .class is never encapsulated
            try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                if (in != null) return read(in.readAllBytes(), Origin.LIBRARY);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read class " + name + ": " + e.getMessage(), e);
        }
        return null;
    }

    private static byte[] entry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name + ".class");
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private static Info read(byte[] classFile, Origin origin) {
        Map<String, Integer> methods = new HashMap<>();
        Map<String, Integer> fields = new HashMap<>();
        String[] header = new String[2];
        List<String> interfaces = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] itfs) {
                header[0] = name;
                header[1] = superName;
                if (itfs != null) interfaces.addAll(Arrays.asList(itfs));
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                fields.put(name + ':' + descriptor, access);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.put(name + descriptor, access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Info(header[0], origin, header[1], List.copyOf(interfaces), methods, fields);
    }

    /**
     * The class whose declaration a method reference resolves to, JVM style (JVMS 5.4.3.3): {@code owner} and its
     * superclasses first, then the superinterfaces of all of them, breadth first. Null when not found.
     */
    Info resolveMethod(String owner, String name, String desc) {
        String key = name + desc;
        List<Info> chain = new ArrayList<>();
        for (Info c = get(owner); c != null; c = c.superName() == null ? null : get(c.superName())) {
            if (c.methods().containsKey(key)) return c;
            chain.add(c);
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (Info c : chain) queue.addAll(c.interfaces());
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (!seen.add(n)) continue;
            Info i = get(n);
            if (i == null) continue;
            if (i.methods().containsKey(key)) return i;
            queue.addAll(i.interfaces());
        }
        return null;
    }

    /** The class declaring a field reference, JVM style (JVMS 5.4.3.2): the class, its superinterfaces, then its superclass. */
    Info resolveField(String owner, String name, String desc) {
        Info c = get(owner);
        if (c == null) return null;
        if (c.fields().containsKey(name + ':' + desc)) return c;
        for (String itf : c.interfaces()) {
            Info i = resolveField(itf, name, desc);
            if (i != null) return i;
        }
        return c.superName() == null ? null : resolveField(c.superName(), name, desc);
    }

    /**
     * Every proper supertype of {@code name} (not the class itself), breadth first: superclass before interfaces at
     * each step. Unknown classes end their branch.
     */
    List<Info> supertypes(String name) {
        List<Info> out = new ArrayList<>();
        Info start = get(name);
        if (start == null) return out;
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        enqueue(start, queue);
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (!seen.add(n)) continue;
            Info i = get(n);
            if (i == null) continue;
            out.add(i);
            enqueue(i, queue);
        }
        return out;
    }

    private static void enqueue(Info i, Deque<String> queue) {
        if (i.superName() != null) queue.add(i.superName());
        queue.addAll(i.interfaces());
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (ZipFile z : libraries) {
            try {
                z.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (game != null) game.close();
        if (failure != null) throw failure;
    }
}
