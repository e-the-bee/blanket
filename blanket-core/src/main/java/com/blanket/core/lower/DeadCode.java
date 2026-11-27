// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.List;

/**
 * Pass 6b (lowering section 4.7): replaces unreachable instructions with {@code NOP...ATHROW} (one run per
 * stretch of dead instructions) and drops exception-table entries that cover only dead code.
 */
public final class DeadCode implements Opcodes {
    private DeadCode() {}

    public static void remove(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (m.instructions.size() == 0) continue;
            Folding.widen(m);
            Frame<BasicValue>[] frames;
            try {
                frames = new Analyzer<BasicValue>(new BasicInterpreter()).analyze(node.name, m);
            } catch (AnalyzerException e) {
                throw new IllegalStateException("cannot analyze " + node.name + "." + m.name + m.desc + ": " + e.getMessage(), e);
            }
            AbstractInsnNode[] insns = m.instructions.toArray();
            dropDeadEntries(m, insns, frames);
            List<AbstractInsnNode> run = new ArrayList<AbstractInsnNode>();
            for (int i = 0; i < insns.length; i++) {
                if (insns[i].getOpcode() < 0) continue;
                if (frames[i] == null) {
                    run.add(insns[i]);
                } else {
                    flush(m, run);
                }
            }
            flush(m, run);
        }
    }

    private static void flush(MethodNode m, List<AbstractInsnNode> run) {
        for (int k = 0; k < run.size(); k++) {
            m.instructions.set(run.get(k), new InsnNode(k == run.size() - 1 ? ATHROW : NOP));
        }
        run.clear();
    }

    /** Removes try/catch entries whose protected range holds no reachable instruction. */
    private static void dropDeadEntries(MethodNode m, AbstractInsnNode[] insns, Frame<BasicValue>[] frames) {
        java.util.Map<AbstractInsnNode, Integer> index = new java.util.IdentityHashMap<AbstractInsnNode, Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        m.tryCatchBlocks.removeIf((TryCatchBlockNode t) -> {
            for (int i = index.get(t.start); i < index.get(t.end); i++) {
                if (insns[i].getOpcode() >= 0 && frames[i] != null) return false;
            }
            return true;
        });
    }
}
