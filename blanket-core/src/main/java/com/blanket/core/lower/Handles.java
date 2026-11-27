// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/**
 * Pass 2 of the lowering (lowering-and-adapters.md section 4.3): {@code invokedynamic} bootstrap arguments that are
 * method handles on API members are redirected. A handle goes straight to the target method when the lowering is one
 * invoke with the API descriptor after remapping (receiver included); in every other case it becomes an
 * {@code H_INVOKESTATIC} handle on a bridge of the class ({@link Bridges}) whose body is the lowering. The bootstrap's
 * {@code instantiatedMethodType} is not touched. {@code StringConcatFactory} call sites are left alone, and pattern
 * switches over API types are rejected ({@code FORBIDDEN}).
 */
public final class Handles implements Opcodes {
    private Handles() {}

    private static final String SWITCH_BOOTSTRAPS = "java/lang/runtime/SwitchBootstraps";
    private static final String CONCAT = "java/lang/invoke/StringConcatFactory";
    private static final String API = "com/blanket/api/";

    public static void rewrite(ClassNode node, Plan plan, Bridges bridges, List<Diagnostic> errors) {
        SimpleRemapper aliases = new SimpleRemapper(plan.types());
        for (MethodNode m : new java.util.ArrayList<>(node.methods)) {
            int line = -1;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof LineNumberNode l) {
                    line = l.line;
                } else if (in instanceof InvokeDynamicInsnNode indy) {
                    String bsmOwner = indy.bsm.getOwner();
                    if (bsmOwner.equals(CONCAT)) continue;
                    if (bsmOwner.equals(SWITCH_BOOTSTRAPS)) {
                        checkSwitch(node, m, line, indy, plan, errors);
                        continue;
                    }
                    Object[] args = indy.bsmArgs;
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof Handle h) args[i] = retarget(node, m, line, h, plan, aliases, bridges, errors);
                    }
                }
            }
        }
    }

    private static Handle retarget(ClassNode cls, MethodNode m, int line, Handle h, Plan plan, SimpleRemapper aliases,
                                   Bridges bridges, List<Diagnostic> errors) {
        int tag = h.getTag();
        if (tag != H_INVOKESTATIC && tag != H_INVOKEVIRTUAL && tag != H_INVOKEINTERFACE && tag != H_INVOKESPECIAL) return h;
        String key = h.getOwner() + "." + h.getName() + h.getDesc();
        Lowered lowered = plan.members().get(key);
        if (lowered == null) {
            if (plan.types().containsKey(h.getOwner()) && !MemberLowering.passThrough(h.getOwner(), h.getName(), h.getDesc())) {
                String label = h.getOwner().substring(h.getOwner().lastIndexOf('/') + 1) + "." + h.getName();
                errors.add(new Diagnostic(ErrorCode.UNBOUND, plan.target().id(), label, key, null,
                        "lowering: " + cls.name + "." + m.name + (line >= 0 ? ":" + line : "") + " has a method reference to "
                                + label + ", which the plan has no lowering for on this target",
                        "bind " + label + " in the API type's file in blanket-bindings/types, or stop using it"));
            }
            return h;
        }
        if (lowered instanceof Lowered.Unsupported) return h;
        String apiDesc = MemberLowering.apiDesc(tag == H_INVOKESTATIC ? INVOKESTATIC : INVOKEVIRTUAL, h.getOwner(), h.getDesc());
        if (lowered instanceof Lowered.Inline in && in.ops().size() == 1 && in.ops().get(0).kind() == Op.Kind.INVOKE) {
            Op op = in.ops().get(0);
            String want = aliases.mapMethodDesc(apiDesc);
            if (op.opcode() == INVOKESTATIC && op.desc().equals(want)) {
                return new Handle(H_INVOKESTATIC, op.owner(), op.name(), op.desc(), op.itf());
            }
            if ((op.opcode() == INVOKEVIRTUAL || op.opcode() == INVOKEINTERFACE) && Type.getArgumentTypes(want).length > 0
                    && op.desc().equals(withoutReceiver(want))) {
                return new Handle(op.opcode() == INVOKEVIRTUAL ? H_INVOKEVIRTUAL : H_INVOKEINTERFACE,
                        op.owner(), op.name(), op.desc(), op.itf());
            }
        }
        return bridges.bridge(key, apiDesc, lowered);
    }

    private static String withoutReceiver(String desc) {
        Type[] params = Type.getArgumentTypes(desc);
        Type[] rest = java.util.Arrays.copyOfRange(params, 1, params.length);
        return Type.getMethodDescriptor(Type.getReturnType(desc), rest);
    }

    private static void checkSwitch(ClassNode cls, MethodNode m, int line, InvokeDynamicInsnNode indy, Plan plan,
                                    List<Diagnostic> errors) {
        String name = indy.bsm.getName();
        if (!name.equals("typeSwitch") && !name.equals("enumSwitch")) return;
        String api = null;
        for (Type p : Type.getArgumentTypes(indy.desc)) {
            if (api == null && isApi(p, plan)) api = p.getInternalName();
        }
        for (Object a : indy.bsmArgs) {
            if (api == null) api = apiIn(a, plan);
        }
        if (api == null) return;
        errors.add(new Diagnostic(ErrorCode.FORBIDDEN, plan.target().id(), cls.name + "." + m.name + (line >= 0 ? ":" + line : ""),
                api, null,
                "a pattern switch (SwitchBootstraps." + name + ") over the API type " + api.replace('/', '.') + " in " + cls.name
                        + "." + m.name + (line >= 0 ? ":" + line : ""),
                "use if/instanceof or a classic switch"));
    }

    private static boolean isApi(Type t, Plan plan) {
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() != Type.OBJECT) return false;
        String n = t.getInternalName();
        return n.startsWith(API) || plan.types().containsKey(n);
    }

    private static String apiIn(Object arg, Plan plan) {
        if (arg instanceof Type t) return isApi(t, plan) ? (t.getSort() == Type.METHOD ? null : t.getInternalName()) : null;
        if (arg instanceof String s) {
            String n = s.replace('.', '/');
            return n.startsWith(API) || plan.types().containsKey(n) ? n : null;
        }
        if (arg instanceof ConstantDynamic cd) {
            for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) {
                String r = apiIn(cd.getBootstrapMethodArgument(i), plan);
                if (r != null) return r;
            }
        }
        return null;
    }
}
