// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lazy existence and access lookup over the target's client jar, its loader jar and the running JDK
 * (docs/pseudocode.md section 3). A class is parsed the first time it is asked for; only its header and member
 * signatures are kept, never code.
 *
 * <p>The JDK is read through {@code jrt:/}, not {@code lib/ct.sym}: ct.sym files its releases under symbolic
 * directory names (for example {@code BCDEF/}), which are not worth decoding for the few JDK classes a binding can
 * name. This is recorded in the progress file as a deviation.
 */
public final class ClassIndex implements AutoCloseable {

    /** Access and member signatures of one class. Method keys are {@code name + descriptor}; field keys {@code name:descriptor}. */
    public record Info(int access, String superName, List<String> interfaces, Map<String, Integer> methods, Map<String, Integer> fields) {
        public boolean isInterface() { return (access & Opcodes.ACC_INTERFACE) != 0; }
        public boolean isStatic(Map<String, Integer> table, String key) { return (table.get(key) & Opcodes.ACC_STATIC) != 0; }
    }

    private static final String[] JDK_MODULES = {"java.base", "java.desktop", "java.logging", "java.net.http", "java.sql", "java.xml"};

    private final List<ZipFile> jars = new ArrayList<>();
    private final Map<String, ZipEntry> entries = new HashMap<>();
    private final Map<String, Optional<Info>> cache = new HashMap<>();
    private final boolean withJdk;
    private FileSystem jrt;

    private ClassIndex(boolean withJdk) {
        this.withJdk = withJdk;
    }

    /** Opens the jars in priority order (first match wins). {@code withJdk} adds the running JDK's classes last. */
    public static ClassIndex open(List<Path> jars, boolean withJdk) throws IOException {
        ClassIndex index = new ClassIndex(withJdk);
        for (Path jar : jars) {
            ZipFile z = new ZipFile(jar.toFile());
            index.jars.add(z);
            z.stream().forEach(e -> {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    index.entries.putIfAbsent(n.substring(0, n.length() - 6), e);
                }
            });
        }
        return index;
    }

    @Override
    public void close() throws IOException {
        for (ZipFile z : jars) z.close();
        // The default jrt file system is shared by the JVM and cannot be closed; it is left open.
    }

    /** The class with this internal name ({@code net/minecraft/class_310}), or empty. */
    public Optional<Info> get(String internalName) {
        return cache.computeIfAbsent(internalName, this::read);
    }

    public boolean exists(String internalName) {
        return get(internalName).isPresent();
    }

    private Optional<Info> read(String name) {
        ZipEntry entry = entries.get(name);
        if (entry != null) {
            for (ZipFile z : jars) {
                ZipEntry e = z.getEntry(entry.getName());
                if (e != null) {
                    try (InputStream in = z.getInputStream(e)) {
                        return Optional.of(parse(in.readAllBytes()));
                    } catch (IOException ex) {
                        throw new IllegalStateException("cannot read " + name + " from " + z.getName(), ex);
                    }
                }
            }
        }
        if (withJdk) {
            Optional<Info> jdk = readJdk(name);
            if (jdk.isPresent()) return jdk;
        }
        return Optional.empty();
    }

    private Optional<Info> readJdk(String name) {
        try {
            if (jrt == null) jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            for (String module : JDK_MODULES) {
                Path p = jrt.getPath("/modules/" + module + "/" + name + ".class");
                if (Files.isRegularFile(p)) return Optional.of(parse(Files.readAllBytes(p)));
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read JDK class " + name, e);
        }
    }

    static Info parse(byte[] bytes) {
        Map<String, Integer> methods = new HashMap<>();
        Map<String, Integer> fields = new HashMap<>();
        int[] access = new int[1];
        String[] superName = new String[1];
        List<String> interfaces = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int acc, String name, String signature, String sup, String[] itfs) {
                access[0] = acc;
                superName[0] = sup;
                if (itfs != null) for (String i : itfs) interfaces.add(i);
            }

            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String signature, Object value) {
                fields.put(name + ":" + desc, acc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String signature, String[] exceptions) {
                methods.put(name + desc, acc);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Info(access[0], superName[0], interfaces, methods, fields);
    }
}
