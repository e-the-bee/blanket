// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.scan;

import com.blanket.core.scan.Scan.ApiMember;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * SCAN (pseudocode.md section 2): reads every class of a mod jar with ASM and collects what it uses from
 * {@code com.blanket.api}. Only the M1 part of the used set is built: API members, API types and events. Library
 * references, feature and overlay call sites, resource constants and the forbidden-construct checks come with the
 * milestones that add the API they need.
 */
public final class ModScanner {
    public static final String API_PREFIX = "com/blanket/api/";
    public static final String EVENTS = API_PREFIX + "Events";
    public static final String CLIENT_MOD = API_PREFIX + "ClientMod";

    private ModScanner() {}

    /** Scans every {@code .class} of the jar (not {@code META-INF/} and not {@code module-info}). */
    public static Scan scan(Path jar) throws IOException {
        try {
            return scan(classes(jar));
        } catch (IllegalArgumentException e) {
            throw new IOException(jar + ": " + e.getMessage(), e);
        }
    }

    /** Scans the given class files; a class ASM cannot read is an {@link IllegalArgumentException} naming it. */
    public static Scan scan(Map<String, byte[]> classes) {
        Scan scan = new Scan();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            try {
                scanClass(e.getValue(), scan);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("cannot read class " + e.getKey() + ".class: " + ex.getMessage(), ex);
            }
        }
        return scan;
    }

    /** The class files {@link #scan(Path)} reads, by internal name, in jar order. */
    public static Map<String, byte[]> classes(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class") || n.startsWith("META-INF/") || n.equals("module-info.class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    out.put(n.substring(0, n.length() - ".class".length()), in.readAllBytes());
                }
            }
        }
        return out;
    }

    /** Adds one class to {@code scan}. */
    public static void scanClass(byte[] bytes, Scan scan) {
        scanClass(bytes, scan, true);
    }

    /**
     * Adds one class to {@code scan}; with {@code events} false its reads of {@code Events.X} still count as type uses but
     * not as subscriptions (the runtime's own dispatcher reads every event and subscribes to none).
     */
    public static void scanClass(byte[] bytes, Scan scan, boolean events) {
        new ClassReader(bytes).accept(new Classes(new Used(scan, events)), 0);
        scan.classRead();
    }

    /** Recording helpers shared by all visitors of one scan. */
    private static final class Used {
        final Scan scan;
        final boolean events;

        Used(Scan scan, boolean events) {
            this.scan = scan;
            this.events = events;
        }

        void internal(String name) {
            if (name == null) return;
            if (name.charAt(0) == '[') {
                type(Type.getType(name));
            } else if (name.startsWith(API_PREFIX)) {
                scan.addType(name);
            }
        }

        void type(Type t) {
            if (t.getSort() == Type.ARRAY) t = t.getElementType();
            if (t.getSort() == Type.OBJECT) internal(t.getInternalName());
        }

        /** A field descriptor or a method descriptor. */
        void desc(String d) {
            if (d == null) return;
            if (d.charAt(0) == '(') {
                for (Type a : Type.getArgumentTypes(d)) type(a);
                type(Type.getReturnType(d));
            } else {
                type(Type.getType(d));
            }
        }

        void signature(String sig) {
            if (sig == null) return;
            new SignatureReader(sig).accept(new Sig());
        }

        void member(String owner, String name, String desc, boolean isStatic, boolean isField) {
            internal(owner);
            desc(desc);
            if (owner.startsWith(API_PREFIX)) {
                scan.addMember(new ApiMember(owner, name, desc, isStatic, isField));
            }
        }

        void handle(Handle h) {
            int tag = h.getTag();
            boolean field = tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC;
            boolean isStatic = tag == Opcodes.H_GETSTATIC || tag == Opcodes.H_PUTSTATIC || tag == Opcodes.H_INVOKESTATIC;
            member(h.getOwner(), h.getName(), h.getDesc(), isStatic, field);
        }

        void constant(Object c) {
            if (c instanceof Type t) {
                if (t.getSort() == Type.METHOD) desc(t.getDescriptor());
                else type(t);
            } else if (c instanceof Handle h) {
                handle(h);
            } else if (c instanceof ConstantDynamic cd) {
                desc(cd.getDescriptor());
                handle(cd.getBootstrapMethod());
                for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) constant(cd.getBootstrapMethodArgument(i));
            }
        }

        AnnotationVisitor annotation(String desc) {
            desc(desc);
            return new Annotations(this);
        }

        /** Class types in a generic signature. */
        final class Sig extends SignatureVisitor {
            private String last;

            Sig() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visitClassType(String name) {
                last = name;
                internal(name);
            }

            @Override
            public void visitInnerClassType(String name) {
                last = last + "$" + name;
                internal(last);
            }
        }
    }

    private static final class Annotations extends AnnotationVisitor {
        private final Used used;

        Annotations(Used used) {
            super(Opcodes.ASM9);
            this.used = used;
        }

        @Override
        public void visit(String name, Object value) {
            used.constant(value);
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            used.desc(desc);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            used.desc(desc);
            return this;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return this;
        }
    }

    private static final class Classes extends ClassVisitor {
        private final Used used;

        Classes(Used used) {
            super(Opcodes.ASM9);
            this.used = used;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            used.internal(superName);
            if (interfaces != null) {
                for (String i : interfaces) {
                    used.internal(i);
                    if (i.equals(CLIENT_MOD)) used.scan.addEntry(name);
                }
            }
            used.signature(signature);
        }

        @Override
        public void visitOuterClass(String owner, String name, String desc) {
            used.internal(owner);
            used.desc(desc);
        }

        @Override
        public void visitNestHost(String nestHost) {
            used.internal(nestHost);
        }

        @Override
        public void visitNestMember(String nestMember) {
            used.internal(nestMember);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            used.internal(permittedSubclass);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            used.internal(name);
            used.internal(outerName);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String desc, String signature) {
            used.desc(desc);
            used.signature(signature);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return used.annotation(d);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String d, boolean visible) {
                    return used.annotation(d);
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            used.desc(desc);
            used.signature(signature);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return used.annotation(d);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String d, boolean visible) {
                    return used.annotation(d);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            used.desc(desc);
            used.signature(signature);
            if (exceptions != null) for (String e : exceptions) used.internal(e);
            return new Methods(used);
        }
    }

    private static final class Methods extends MethodVisitor {
        private final Used used;

        Methods(Used used) {
            super(Opcodes.ASM9);
            this.used = used;
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return new Annotations(used);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end,
                                                              int[] index, String desc, boolean visible) {
            return used.annotation(desc);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            used.internal(type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (opcode == Opcodes.GETSTATIC && owner.equals(EVENTS)) {
                used.internal(owner);
                used.desc(desc);
                if (used.events) used.scan.addEvent(name);
                return;
            }
            used.member(owner, name, desc, opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC, true);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
            used.member(owner, name, desc, opcode == Opcodes.INVOKESTATIC, false);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            used.desc(desc);
            used.handle(bsm);
            for (Object a : args) used.constant(a);
        }

        @Override
        public void visitLdcInsn(Object value) {
            used.constant(value);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            used.desc(desc);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            used.internal(type);
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            used.desc(desc);
            used.signature(signature);
        }
    }
}
