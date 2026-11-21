// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Counts the call sites an {@code INVOKE} or {@code INVOKE_STRING} injection point can match inside a hooked method (C7). */
final class CallSites {
    private CallSites() {}

    /**
     * How many calls to {@code callOwner.callName callDesc} the body of {@code owner.method desc} contains; when
     * {@code ldc} is not null only calls whose argument is that string constant, loaded right before the call.
     *
     * @return the count, or -1 when the class or the method with a body is not in the jars
     */
    static int count(List<Path> jars, String owner, String method, String desc, String callOwner, String callName,
                     String callDesc, String ldc) throws IOException {
        byte[] bytes = read(jars, owner);
        if (bytes == null) return -1;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method) || !m.desc.equals(desc)) continue;
            int n = 0;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (!(i instanceof MethodInsnNode call)) continue;
                if (!call.owner.equals(callOwner) || !call.name.equals(callName) || !call.desc.equals(callDesc)) continue;
                if (ldc == null) {
                    n++;
                    continue;
                }
                AbstractInsnNode before = call.getPrevious();
                while (before != null && before.getOpcode() < 0) before = before.getPrevious();
                if (before instanceof LdcInsnNode l && ldc.equals(l.cst)) n++;
            }
            return n;
        }
        return -1;
    }

    private static byte[] read(List<Path> jars, String internalName) throws IOException {
        for (Path jar : jars) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry(internalName + ".class");
                if (e == null) continue;
                try (InputStream in = z.getInputStream(e)) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }
}
