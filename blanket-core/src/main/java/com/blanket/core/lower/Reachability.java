// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pass 7a (lowering section 4.7): after folding and {@link DeadCode}, every call or field read that still maps to
 * {@link Lowered.Unsupported} is reachable on this target and is reported as {@code UNSUPPORTED}, once per method and member.
 */
public final class Reachability implements Opcodes {
    private Reachability() {}

    /** As {@link #check(ClassNode, Map, String, Function, List)}, without the feature range in the message. */
    public static void check(ClassNode node, Map<String, Lowered> members, String target, List<Diagnostic> errors) {
        check(node, members, target, feature -> null, errors);
    }

    /**
     * @param featureRange the {@code available} range of a Feature constant as text ({@code mc 1.9..}), or null if unknown
     */
    public static void check(ClassNode node, Map<String, Lowered> members, String target,
                             Function<String, String> featureRange, List<Diagnostic> errors) {
        for (MethodNode m : node.methods) {
            Set<String> seen = new HashSet<String>();
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode c = (MethodInsnNode) insn;
                    report(node, m, insn, c.owner, c.name, c.desc, c.owner + "." + c.name + c.desc, members, target, featureRange, seen, errors);
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    report(node, m, insn, f.owner, f.name, null, f.owner + "." + f.name + ":" + f.desc, members, target, featureRange, seen, errors);
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    for (Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                        if (!(arg instanceof Handle)) continue;
                        Handle h = (Handle) arg;
                        boolean field = h.getTag() <= H_PUTSTATIC;
                        report(node, m, insn, h.getOwner(), h.getName(), field ? null : h.getDesc(),
                                h.getOwner() + "." + h.getName() + (field ? ":" : "") + h.getDesc(), members, target, featureRange, seen, errors);
                    }
                }
            }
        }
    }

    private static void report(ClassNode node, MethodNode m, AbstractInsnNode insn, String owner, String name, String methodDesc,
                               String key, Map<String, Lowered> members, String target, Function<String, String> featureRange,
                               Set<String> seen, List<Diagnostic> errors) {
        Lowered l = members.get(key);
        if (!(l instanceof Lowered.Unsupported) || !seen.add(key)) return;
        Lowered.Unsupported u = (Lowered.Unsupported) l;
        String simple = owner.substring(owner.lastIndexOf('/') + 1);
        StringBuilder label = new StringBuilder(simple).append('.').append(name);
        if (methodDesc != null) {
            label.append('(');
            Type[] args = Type.getArgumentTypes(methodDesc);
            for (int i = 0; i < args.length; i++) {
                if (i > 0) label.append(", ");
                String cn = args[i].getClassName();
                label.append(cn.substring(cn.lastIndexOf('.') + 1));
            }
            label.append(')');
        }
        String site = node.name.replace('/', '.') + "." + m.name + m.desc;
        String detail;
        String next;
        if (u.feature() != null) {
            String range = featureRange.apply(u.feature());
            detail = site + " calls " + label + " which needs Feature." + u.feature()
                    + (range == null ? "" : " (available on " + range + ")");
            next = "Guard it with Blanket.supports(Feature." + u.feature() + ") or annotate the method @Requires(Feature." + u.feature() + ").";
        } else {
            detail = site + " calls " + label + " which is unsupported on " + target + ": " + u.reason();
            next = "Do not use " + label + " on " + target + ", or bind it there.";
        }
        errors.add(new Diagnostic(ErrorCode.UNSUPPORTED, target, label.toString(), null, null, detail, next));
    }
}
