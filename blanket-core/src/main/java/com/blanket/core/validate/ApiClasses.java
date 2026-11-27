// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** The public members of API and glue classes (compiled {@code blanket-api} and {@code blanket-runtime}), read without loading them. */
public interface ApiClasses {

    /** The package of the blanket-api jar, and the annotation that marks its owned types (fixed API names, M2 plan section 3). */
    String API_PACKAGE = "com/blanket/api/";
    String OWNED = "Lcom/blanket/api/Owned;";

    /** A public method declared by an API class. {@code desc} is in API names ({@code Lcom/blanket/api/Player;}). */
    record Method(String name, String desc, boolean isStatic) {}

    /**
     * What coverage needs to know about an API class itself: whether it is public, whether it carries {@code @Owned},
     * whether it is an enum, and its enum constants in declaration order (the API ordinals).
     */
    record ClassInfo(boolean isPublic, boolean owned, boolean isEnum, List<String> enumConstants) {}

    /** The public, non-synthetic methods the class declares itself (no constructors), or empty when the class is not found. */
    Optional<List<Method>> methods(String internalName);

    /** The class facts, or empty when the class is not found or this source does not read them. */
    default Optional<ClassInfo> classInfo(String internalName) {
        return Optional.empty();
    }

    /**
     * Every class of the blanket-api jar ({@code com/blanket/api/**}), sorted; empty when this source does not list
     * them, and then C1 does not look for API classes without a type file.
     */
    default List<String> apiJarClasses() {
        return List.of();
    }

    /** Reads {@code <internalName>.class} through {@code loader}; lists no classes. */
    static ApiClasses of(ClassLoader loader) {
        return new ApiClasses() {
            @Override
            public Optional<List<Method>> methods(String internalName) {
                return read(loader, internalName).map(Read::methods);
            }

            @Override
            public Optional<ClassInfo> classInfo(String internalName) {
                return read(loader, internalName).map(Read::info);
            }
        };
    }

    /**
     * As {@link #of}, and lists every class of the blanket-api jar on {@code loader} (the jar or classes folder that
     * holds {@code com/blanket/api/Owned.class}), so C1 can require a type file for every alias API class.
     */
    static ApiClasses withApiJar(ClassLoader loader) {
        ApiClasses read = of(loader);
        return new ApiClasses() {
            @Override
            public Optional<List<Method>> methods(String internalName) {
                return read.methods(internalName);
            }

            @Override
            public Optional<ClassInfo> classInfo(String internalName) {
                return read.classInfo(internalName);
            }

            @Override
            public List<String> apiJarClasses() {
                return list(loader);
            }
        };
    }

    /** One API class file as read. */
    record Read(List<Method> methods, ClassInfo info) {}

    private static Optional<Read> read(ClassLoader loader, String internalName) {
        try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
            if (in == null) return Optional.empty();
            return Optional.of(parse(in.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read API class " + internalName + ": " + e.getMessage(), e);
        }
    }

    /** Reads the public methods and the class facts of one class file. */
    static Read parse(byte[] bytes) {
        List<Method> methods = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        int[] access = new int[1];
        boolean[] owned = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int acc, String name, String signature, String superName, String[] interfaces) {
                access[0] = acc;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (descriptor.equals(OWNED)) owned[0] = true;
                return null;
            }

            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String signature, Object value) {
                if ((acc & Opcodes.ACC_ENUM) != 0) constants.add(name);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String signature, String[] exceptions) {
                boolean visible = (acc & Opcodes.ACC_PUBLIC) != 0 && (acc & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
                if (visible && !name.startsWith("<")) methods.add(new Method(name, desc, (acc & Opcodes.ACC_STATIC) != 0));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean isEnum = (access[0] & Opcodes.ACC_ENUM) != 0;
        return new Read(List.copyOf(methods), new ClassInfo((access[0] & Opcodes.ACC_PUBLIC) != 0, owned[0], isEnum,
                isEnum ? List.copyOf(constants) : List.of()));
    }

    /** The classes under {@link #API_PACKAGE} of the jar or folder that holds the {@code Owned} annotation. */
    private static List<String> list(ClassLoader loader) {
        String marker = OWNED.substring(1, OWNED.length() - 1) + ".class";
        URL url = loader.getResource(marker);
        if (url == null) throw new IllegalStateException("blanket-api is not on the classpath: " + marker + " not found");
        TreeSet<String> out = new TreeSet<>();
        try {
            if (url.getProtocol().equals("jar")) {
                JarURLConnection c = (JarURLConnection) url.openConnection();
                c.setUseCaches(false);
                try (JarFile jar = new JarFile(Path.of(c.getJarFileURL().toURI()).toFile())) {
                    for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) add(out, e.nextElement().getName());
                }
            } else {
                Path root = Path.of(url.toURI());
                for (int i = 0; i < marker.split("/").length; i++) root = root.getParent();
                Path base = root;
                try (Stream<Path> files = Files.walk(base.resolve(API_PACKAGE))) {
                    files.forEach(p -> add(out, base.relativize(p).toString().replace('\\', '/')));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the classes of blanket-api at " + url, e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot list the classes of blanket-api at " + url, e);
        }
        return List.copyOf(out);
    }

    private static void add(TreeSet<String> out, String entry) {
        if (entry.startsWith(API_PACKAGE) && entry.endsWith(".class") && !entry.endsWith("package-info.class") && !entry.endsWith("module-info.class")) {
            out.add(entry.substring(0, entry.length() - ".class".length()));
        }
    }
}
