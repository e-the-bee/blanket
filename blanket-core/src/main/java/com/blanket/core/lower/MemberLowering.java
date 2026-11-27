// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pass 1 of the lowering (lowering-and-adapters.md sections 2, 4.1, 4.2, 4.6): every call or field access on an API
 * member with a plan entry is replaced by its lowering, in runtime names. Pass 5 later remaps the API names that remain.
 *
 * <ul>
 *   <li>{@link Lowered.Inline}: the ops replace the instruction. A constant-only lowering first pops the arguments of
 *       the call, so every lowering keeps the stack effect of the member it replaces.</li>
 *   <li>{@link Lowered.Construct}: {@code INVOKESTATIC} to the class's bridge ({@link Bridges}).</li>
 *   <li>{@link Lowered.Adapter}: {@code INVOKESTATIC owner.name desc}.</li>
 *   <li>{@link Lowered.Unsupported}: left in place; the reachability pass judges it.</li>
 * </ul>
 * A member of an alias type without a plan entry is {@code UNBOUND}, except members of {@code java/lang/Object}, of
 * {@code java/lang/Enum}, {@code values()} and {@code valueOf(String)}, which stay for pass 5.
 */
public final class MemberLowering implements Opcodes {
    private MemberLowering() {}

    /** Members of Object and Enum a mod may call on any alias type: name plus descriptor. */
    private static final Set<String> PASS_THROUGH = new HashSet<>();

    static {
        for (Class<?> c : new Class<?>[] {Object.class, Enum.class}) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPrivate(m.getModifiers()) || m.isSynthetic()) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                PASS_THROUGH.add(m.getName() + Type.getMethodDescriptor(m));
            }
        }
    }

    public static void lower(ClassNode node, Plan plan, Bridges bridges, List<Diagnostic> errors) {
        for (MethodNode m : new ArrayList<>(node.methods)) {
            int line = -1;
            int extra = 0;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; ) {
                AbstractInsnNode next = in.getNext();
                if (in instanceof LineNumberNode l) {
                    line = l.line;
                } else if (in instanceof MethodInsnNode mi) {
                    extra += method(node, m, line, mi, plan, bridges, errors);
                } else if (in instanceof FieldInsnNode fi) {
                    extra += field(node, m, line, fi, plan, errors);
                }
                in = next;
            }
            m.maxStack += extra;
        }
    }

    /** Whether {@code name desc} is a member of Object or Enum, or an enum's {@code values()}/{@code valueOf(String)}. */
    static boolean passThrough(String owner, String name, String desc) {
        if (PASS_THROUGH.contains(name + desc)) return true;
        if (name.equals("values") && desc.equals("()[L" + owner + ";")) return true;
        return name.equals("valueOf") && desc.equals("(Ljava/lang/String;)L" + owner + ";");
    }

    /** The descriptor of the member as a static method: receiver first for instance members. */
    static String apiDesc(int opcode, String owner, String desc) {
        return opcode == INVOKESTATIC ? desc : "(L" + owner + ";" + desc.substring(1);
    }

    private static int method(ClassNode cls, MethodNode m, int line, MethodInsnNode mi, Plan plan, Bridges bridges,
                              List<Diagnostic> errors) {
        String key = mi.owner + "." + mi.name + mi.desc;
        Lowered lowered = plan.members().get(key);
        if (lowered == null) {
            if (plan.types().containsKey(mi.owner) && !passThrough(mi.owner, mi.name, mi.desc)) {
                errors.add(unbound(plan, cls, m, line, mi.owner, mi.name, key));
            }
            return 0;
        }
        String desc = apiDesc(mi.getOpcode(), mi.owner, mi.desc);
        if (lowered instanceof Lowered.Inline in) {
            InsnList list = new InsnList();
            boolean constant = in.ops().size() == 1 && in.ops().get(0).kind() == Op.Kind.CONST;
            if (constant) {
                Type[] params = Type.getArgumentTypes(desc);
                for (int i = params.length - 1; i >= 0; i--) {
                    list.add(new InsnNode(params[i].getSize() == 2 ? POP2 : POP));
                }
            }
            list.add(insns(in.ops()));
            m.instructions.insert(mi, list);
            m.instructions.remove(mi);
            return 2 * in.ops().size() + 2;
        }
        if (lowered instanceof Lowered.Construct) {
            Handle h = bridges.bridge(key, desc, lowered);
            m.instructions.set(mi, new MethodInsnNode(INVOKESTATIC, h.getOwner(), h.getName(), h.getDesc(), h.isInterface()));
            return 0;
        }
        if (lowered instanceof Lowered.Adapter a) {
            m.instructions.set(mi, new MethodInsnNode(INVOKESTATIC, a.owner(), a.name(), a.desc(), false));
        }
        return 0;
    }

    private static int field(ClassNode cls, MethodNode m, int line, FieldInsnNode fi, Plan plan, List<Diagnostic> errors) {
        String key = fi.owner + "." + fi.name + ":" + fi.desc;
        Lowered lowered = plan.members().get(key);
        if (lowered == null) {
            if (plan.types().containsKey(fi.owner)) errors.add(unbound(plan, cls, m, line, fi.owner, fi.name, key));
            return 0;
        }
        if (lowered instanceof Lowered.Inline in) {
            m.instructions.insert(fi, insns(in.ops()));
            m.instructions.remove(fi);
            return 2 * in.ops().size() + 2;
        }
        return 0;
    }

    private static Diagnostic unbound(Plan plan, ClassNode cls, MethodNode m, int line, String owner, String name, String key) {
        String label = owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
        return new Diagnostic(ErrorCode.UNBOUND, plan.target().id(), label, key, null,
                "lowering: " + cls.name + "." + m.name + (line >= 0 ? ":" + line : "") + " uses " + label
                        + ", which the plan has no lowering for on this target",
                "bind " + label + " in the API type's file in blanket-bindings/types, or stop using it");
    }

    /** The ops as an instruction list. */
    static InsnList insns(List<Op> ops) {
        MethodNode tmp = new MethodNode();
        for (Op op : ops) write(tmp, op);
        return tmp.instructions;
    }

    /** Writes one plan op as instructions (the same shapes as blanket-emit's {@code ChainWriter}). */
    static void write(MethodVisitor mv, Op op) {
        switch (op.kind()) {
            case LOAD -> throw new IllegalStateException("a member lowering cannot load locals: " + op);
            case INVOKE -> mv.visitMethodInsn(op.opcode(), op.owner(), op.name(), op.desc(), op.itf());
            case FIELD -> mv.visitFieldInsn(op.opcode(), op.owner(), op.name(), op.desc());
            case CONST -> constant(mv, op.arg());
            case CAST -> mv.visitTypeInsn(CHECKCAST, op.owner());
            case NEWARRAY -> {
                constant(mv, op.arg());
                Type element = Type.getType(op.desc());
                int sort = element.getSort();
                if (sort == Type.OBJECT || sort == Type.ARRAY) mv.visitTypeInsn(ANEWARRAY, element.getInternalName());
                else mv.visitIntInsn(NEWARRAY, newArrayType(element));
            }
            case NEW -> {
                mv.visitTypeInsn(NEW, op.owner());
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, op.owner(), "<init>", op.desc(), false);
            }
        }
    }

    private static void constant(MethodVisitor mv, Object v) {
        if (v == null) {
            mv.visitInsn(ACONST_NULL);
        } else if (v instanceof Boolean b) {
            mv.visitInsn(b ? ICONST_1 : ICONST_0);
        } else if (v instanceof Integer i) {
            int n = i;
            if (n >= -1 && n <= 5) mv.visitInsn(ICONST_0 + n);
            else if (n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, n);
            else if (n >= Short.MIN_VALUE && n <= Short.MAX_VALUE) mv.visitIntInsn(SIPUSH, n);
            else mv.visitLdcInsn(n);
        } else if (v instanceof Long l) {
            if (l == 0L || l == 1L) mv.visitInsn(LCONST_0 + l.intValue());
            else mv.visitLdcInsn(l);
        } else if (v instanceof Float f) {
            int bits = Float.floatToIntBits(f);
            if (bits == Float.floatToIntBits(0f) || f == 1f || f == 2f) mv.visitInsn(FCONST_0 + f.intValue());
            else mv.visitLdcInsn(f);
        } else if (v instanceof Double d) {
            long bits = Double.doubleToLongBits(d);
            if (bits == Double.doubleToLongBits(0d) || d == 1d) mv.visitInsn(DCONST_0 + d.intValue());
            else mv.visitLdcInsn(d);
        } else if (v instanceof String s) {
            mv.visitLdcInsn(s);
        } else {
            throw new IllegalArgumentException("not a constant: " + v.getClass().getName());
        }
    }

    private static int newArrayType(Type element) {
        return switch (element.getSort()) {
            case Type.BOOLEAN -> T_BOOLEAN;
            case Type.CHAR -> T_CHAR;
            case Type.FLOAT -> T_FLOAT;
            case Type.DOUBLE -> T_DOUBLE;
            case Type.BYTE -> T_BYTE;
            case Type.SHORT -> T_SHORT;
            case Type.INT -> T_INT;
            case Type.LONG -> T_LONG;
            default -> throw new IllegalArgumentException("not an array element type: " + element);
        };
    }
}
