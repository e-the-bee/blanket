// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pass 6a (lowering section 4.7): folds {@code Blanket.supports(Feature.X)} to a constant and the branches that test
 * it to {@code GOTO} or nothing. The dead code left behind is removed by {@link DeadCode}.
 */
public final class Folding implements Opcodes {
    static final String BLANKET = "com/blanket/api/Blanket";
    static final String FEATURE = "com/blanket/api/Feature";
    private static final String SUPPORTS_DESC = "(L" + FEATURE + ";)Z";

    private Folding() {}

    /**
     * Folds every {@code supports} call of {@code node}. A call whose argument is not a literal {@code Feature}
     * constant (or names a feature {@code features} does not list) is reported as {@code FORBIDDEN} and left alone.
     */
    public static void fold(ClassNode node, Map<String, Boolean> features, String target, List<Diagnostic> errors) {
        for (MethodNode m : node.methods) {
            if (m.instructions.size() == 0) continue;
            foldSupports(node, m, features, target, errors);
            foldBranches(node, m);
        }
    }

    private static void foldSupports(ClassNode node, MethodNode m, Map<String, Boolean> features, String target,
                                     List<Diagnostic> errors) {
        Set<LabelNode> targets = null;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (call.getOpcode() != INVOKESTATIC || !call.owner.equals(BLANKET) || !call.name.equals("supports")
                    || !call.desc.equals(SUPPORTS_DESC)) {
                continue;
            }
            if (targets == null) targets = referencedLabels(m);
            AbstractInsnNode prev = previousReal(insn, targets);
            String where = describe(node, m, insn);
            if (!(prev instanceof FieldInsnNode) || prev.getOpcode() != GETSTATIC || !((FieldInsnNode) prev).owner.equals(FEATURE)
                    || !((FieldInsnNode) prev).desc.equals("L" + FEATURE + ";")) {
                errors.add(forbidden(target, where, "passes an argument to Blanket.supports that is not a Feature constant read"));
                continue;
            }
            String name = ((FieldInsnNode) prev).name;
            Boolean on = features.get(name);
            if (on == null) {
                errors.add(forbidden(target, where, "asks Blanket.supports about Feature." + name + ", which features.json does not list"));
                continue;
            }
            m.instructions.insertBefore(insn, new InsnNode(on ? ICONST_1 : ICONST_0));
            m.instructions.remove(prev);
            m.instructions.remove(insn);
        }
    }

    private static Diagnostic forbidden(String target, String where, String what) {
        return new Diagnostic(ErrorCode.FORBIDDEN, target, "Blanket.supports", null, null,
                where + " " + what,
                "write Blanket.supports(Feature.NAME) with the constant spelled out, not a variable, parameter or expression");
    }

    /** {@code class.method(desc):line}, the line when the class has debug info. */
    static String describe(ClassNode node, MethodNode m, AbstractInsnNode at) {
        String line = "";
        for (AbstractInsnNode p = at; p != null; p = p.getPrevious()) {
            if (p instanceof LineNumberNode) {
                line = ":" + ((LineNumberNode) p).line;
                break;
            }
        }
        return node.name.replace('/', '.') + "." + m.name + m.desc + line;
    }

    static Set<LabelNode> referencedLabels(MethodNode m) {
        Set<LabelNode> out = Collections.newSetFromMap(new IdentityHashMap<LabelNode, Boolean>());
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof JumpInsnNode) {
                out.add(((JumpInsnNode) i).label);
            } else if (i instanceof TableSwitchInsnNode) {
                out.add(((TableSwitchInsnNode) i).dflt);
                out.addAll(((TableSwitchInsnNode) i).labels);
            } else if (i instanceof LookupSwitchInsnNode) {
                out.add(((LookupSwitchInsnNode) i).dflt);
                out.addAll(((LookupSwitchInsnNode) i).labels);
            }
        }
        for (TryCatchBlockNode t : m.tryCatchBlocks) {
            out.add(t.start);
            out.add(t.end);
            out.add(t.handler);
        }
        return out;
    }

    /** The previous instruction, skipping line numbers, frames and labels nothing refers to; null if a referenced label is in the way. */
    private static AbstractInsnNode previousReal(AbstractInsnNode insn, Set<LabelNode> referenced) {
        for (AbstractInsnNode p = insn.getPrevious(); p != null; p = p.getPrevious()) {
            if (p.getOpcode() >= 0) return p;
            if (p instanceof LabelNode && referenced.contains(p)) return null;
        }
        return null;
    }

    /** Gives the analyzer room: lowered chains push more than the API call they replace. */
    static void widen(MethodNode m) {
        m.maxStack += m.instructions.size();
        m.maxLocals += 4;
    }

    private static boolean isIntConstant(AbstractInsnNode i) {
        int op = i.getOpcode();
        return (op >= ICONST_M1 && op <= ICONST_5) || op == BIPUSH || op == SIPUSH
                || (op == LDC && ((LdcInsnNode) i).cst instanceof Integer);
    }

    private static void foldBranches(ClassNode node, MethodNode m) {
        widen(m);
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<BasicValue>(new ConstInterpreter()).analyze(node.name, m);
        } catch (AnalyzerException e) {
            throw new IllegalStateException("cannot analyze " + node.name + "." + m.name + m.desc + ": " + e.getMessage(), e);
        }
        Set<LabelNode> referenced = referencedLabels(m);
        AbstractInsnNode[] insns = m.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            int op = insn.getOpcode();
            if ((op != IFEQ && op != IFNE) || frames[i] == null) continue;
            Frame<BasicValue> f = frames[i];
            BasicValue top = f.getStack(f.getStackSize() - 1);
            if (!(top instanceof Const) || ((Const) top).value == null) continue;
            boolean taken = (((Const) top).value == 0) == (op == IFEQ);
            AbstractInsnNode prev = previousReal(insn, referenced);
            JumpInsnNode jump = (JumpInsnNode) insn;
            if (prev != null && isIntConstant(prev)) {
                m.instructions.remove(prev);
            } else {
                m.instructions.insertBefore(insn, new InsnNode(POP));
            }
            if (taken) {
                m.instructions.set(insn, new JumpInsnNode(GOTO, jump.label));
            } else {
                m.instructions.remove(insn);
            }
        }
    }

    /** An int value that may be a known constant ({@code value != null}). Equality includes the constant. */
    private static final class Const extends BasicValue {
        final Integer value;

        Const(Integer value) {
            super(Type.INT_TYPE);
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Const && Objects.equals(value, ((Const) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    private static final class ConstInterpreter extends BasicInterpreter {
        private static final Const UNKNOWN = new Const(null);

        ConstInterpreter() {
            super(ASM9);
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            int op = insn.getOpcode();
            if (op >= ICONST_M1 && op <= ICONST_5) return new Const(op - ICONST_0);
            if (op == BIPUSH || op == SIPUSH) return new Const(((IntInsnNode) insn).operand);
            if (op == LDC && ((LdcInsnNode) insn).cst instanceof Integer) return new Const((Integer) ((LdcInsnNode) insn).cst);
            return super.newOperation(insn);
        }

        @Override
        public BasicValue merge(BasicValue v, BasicValue w) {
            if ((v instanceof Const || w instanceof Const) && Type.INT_TYPE.equals(v.getType()) && Type.INT_TYPE.equals(w.getType())) {
                return v.equals(w) ? v : UNKNOWN;
            }
            return super.merge(v, w);
        }
    }
}
