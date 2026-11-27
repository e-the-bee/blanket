// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.scan;

import com.blanket.core.scan.Scan.ApiMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModScannerTest {

    /** A mod class whose only API uses are two members, one event, and one type in a field descriptor. */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "mod/Main", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "hud", "Lcom/blanket/api/HudRender;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lcom/blanket/api/Player;)D", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/blanket/api/Client", "get", "()Lcom/blanket/api/Client;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/blanket/api/Events", "CLIENT_TICK_END", "Lcom/blanket/api/EventSource;");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/blanket/api/Player", "x", "()D", false);
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path jar(Path dir) throws IOException {
        Path jar = dir.resolve("mod.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry("mod/Main.class"));
            j.write(modClass());
            j.closeEntry();
            j.putNextEntry(new JarEntry("assets/readme.txt"));
            j.write("not a class".getBytes());
            j.closeEntry();
        }
        return jar;
    }

    @Test
    void scanListsTheTwoApiMembersTheEventAndTheTypes(@TempDir Path dir) throws IOException {
        Scan s = ModScanner.scan(jar(dir));
        assertEquals(List.of(
                new ApiMember("com/blanket/api/Client", "get", "()Lcom/blanket/api/Client;", true, false),
                new ApiMember("com/blanket/api/Player", "x", "()D", false, false)),
                List.copyOf(s.members()));
        assertEquals(List.of("CLIENT_TICK_END"), List.copyOf(s.events()));
        assertEquals(List.of("com/blanket/api/Client", "com/blanket/api/EventSource", "com/blanket/api/Events",
                "com/blanket/api/HudRender", "com/blanket/api/Player"), List.copyOf(s.types()));
        assertEquals(1, s.classCount());
    }

    @Test
    void scanIgnoresNonApiOwners(@TempDir Path dir) throws IOException {
        Scan s = ModScanner.scan(jar(dir));
        assertFalse(s.types().contains("java/lang/Object"));
        assertFalse(s.members().stream().anyMatch(m -> m.owner().startsWith("java/")));
    }

    @Test
    void withoutEventsTheReadsStillCountAsTypesAndMembersButSubscribeToNothing() {
        Scan s = new Scan();
        ModScanner.scanClass(modClass(), s, false);
        assertEquals(List.of(), List.copyOf(s.events()));
        assertEquals(2, s.members().size());
        assertEquals(List.of("com/blanket/api/Client", "com/blanket/api/EventSource", "com/blanket/api/Events",
                "com/blanket/api/HudRender", "com/blanket/api/Player"), List.copyOf(s.types()));
    }
}
