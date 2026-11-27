// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The private static synthetic bridge methods of one class (lowering-and-adapters.md sections 4.2 and 4.3). A bridge
 * has the API descriptor, receiver first, and the lowering as its body; there is one per (class, member), reused by
 * every call site and handle of that class. Names are {@code blanket$<kind>$<Type>$<member>}: the {@code blanket$}
 * prefix keeps them apart from javac's {@code lambda$} methods, and a numeric suffix keeps them apart from any other
 * method of the class.
 */
public final class Bridges implements Opcodes {
    private final ClassNode owner;
    private final boolean itf;
    private final Map<String, Handle> made = new HashMap<>();
    private final Set<String> taken = new HashSet<>();

    public Bridges(ClassNode owner) {
        this.owner = owner;
        this.itf = (owner.access & ACC_INTERFACE) != 0;
        for (MethodNode m : owner.methods) taken.add(m.name + m.desc);
    }

    /**
     * The handle of the bridge for {@code memberKey} ({@code owner.name+desc} or {@code owner.name:desc}), made on first
     * use and added to the class.
     *
     * @param apiDesc  the bridge's descriptor in API names, receiver first for instance members
     * @param lowering {@link Lowered.Inline}, {@link Lowered.Construct} or {@link Lowered.Adapter}
     * @return an {@code H_INVOKESTATIC} handle on this class; {@code isInterface} is set when the class is an interface
     */
    public Handle bridge(String memberKey, String apiDesc, Lowered lowering) {
        String cacheKey = memberKey + "|" + apiDesc;
        Handle h = made.get(cacheKey);
        if (h != null) return h;
        String name = unique(baseName(memberKey, lowering), apiDesc);
        MethodNode m = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, apiDesc, null, null);
        body(m, apiDesc, lowering);
        owner.methods.add(m);
        taken.add(name + apiDesc);
        h = new Handle(H_INVOKESTATIC, owner.name, name, apiDesc, itf);
        made.put(cacheKey, h);
        return h;
    }

    private String unique(String base, String desc) {
        String name = base;
        for (int i = 2; taken.contains(name + desc); i++) name = base + "$" + i;
        return name;
    }

    private static String baseName(String memberKey, Lowered lowering) {
        int dot = memberKey.indexOf('.');
        String type = memberKey.substring(0, dot);
        type = type.substring(type.lastIndexOf('/') + 1);
        int end = dot + 1;
        while (end < memberKey.length() && memberKey.charAt(end) != '(' && memberKey.charAt(end) != ':') end++;
        return "blanket$" + kind(lowering) + "$" + type + "$" + memberKey.substring(dot + 1, end);
    }

    private static String kind(Lowered l) {
        if (l instanceof Lowered.Construct) return "new";
        if (l instanceof Lowered.Adapter) return "adapter";
        if (l instanceof Lowered.Inline in) {
            List<Op> ops = in.ops();
            if (ops.size() == 1) {
                switch (ops.get(0).kind()) {
                    case INVOKE: return "invoke";
                    case FIELD: return switch (ops.get(0).opcode()) {
                        case GETFIELD, GETSTATIC -> "get";
                        default -> "put";
                    };
                    case CONST: return "const";
                    default: break;
                }
            }
            return "chain";
        }
        throw new IllegalArgumentException("no bridge for " + l);
    }

    private static void body(MethodNode m, String apiDesc, Lowered lowering) {
        Type[] params = Type.getArgumentTypes(apiDesc);
        int slots = 0;
        for (Type p : params) slots += p.getSize();
        int pushes = 0;
        m.visitCode();
        if (lowering instanceof Lowered.Construct c) {
            m.visitTypeInsn(NEW, c.owner());
            m.visitInsn(DUP);
            loads(m, params);
            m.visitMethodInsn(INVOKESPECIAL, c.owner(), "<init>", c.ctorDesc(), false);
            pushes = 2;
        } else if (lowering instanceof Lowered.Adapter a) {
            loads(m, params);
            m.visitMethodInsn(INVOKESTATIC, a.owner(), a.name(), a.desc(), false);
        } else if (lowering instanceof Lowered.Inline in) {
            boolean constant = in.ops().size() == 1 && in.ops().get(0).kind() == Op.Kind.CONST;
            if (!constant) loads(m, params);
            for (Op op : in.ops()) MemberLowering.write(m, op);
            pushes = 2 * in.ops().size();
        } else {
            throw new IllegalArgumentException("no bridge for " + lowering);
        }
        Type ret = Type.getReturnType(apiDesc);
        m.visitInsn(ret.getSort() == Type.VOID ? RETURN : ret.getOpcode(IRETURN));
        m.visitMaxs(slots + pushes + 2, slots);
        m.visitEnd();
    }

    private static void loads(MethodNode m, Type[] params) {
        int slot = 0;
        for (Type p : params) {
            m.visitVarInsn(p.getOpcode(ILOAD), slot);
            slot += p.getSize();
        }
    }
}
