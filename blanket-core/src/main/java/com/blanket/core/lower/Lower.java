// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * LOWER for one target (lowering-and-adapters.md section 2): the mod's and the embedded classes go through passes 1 to 7,
 * the adapter closure of the plan through passes 5 to 7. The classes of aliased API types are compile-time stubs and are
 * not output. Every error of every class is collected; any error fails the whole target with one {@link LowerException}.
 */
public final class Lower implements Opcodes {
    private Lower() {}

    /**
     * Lowers every class for {@code plan}'s target.
     *
     * @param base         the mod's package, internal form ({@code com/example/mod}); Blanket classes move under
     *                     {@code <base>/blanket/} ({@link TypeMap})
     * @param modClasses   the mod's classes, internal name to class file
     * @param embedClasses the embedded API and runtime classes, internal name to class file
     * @param hierarchy    the target's classes (game, libraries, JDK) in runtime names; the output classes are layered on top
     * @return output internal name to class file, sorted by name
     * @throws LowerException with every diagnostic of every class
     */
    public static Map<String, byte[]> lower(Plan plan, String base, Map<String, byte[]> modClasses,
                                            Map<String, byte[]> embedClasses, Hierarchy hierarchy) {
        String target = plan.target().id();
        TypeMap map = new TypeMap(plan.types(), base);
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        for (Map<String, byte[]> group : List.of(modClasses, embedClasses)) {
            for (Map.Entry<String, byte[]> e : group.entrySet()) {
                if (plan.types().containsKey(e.getKey())) continue;
                if (inputs.put(e.getKey(), e.getValue()) != null) {
                    throw new IllegalArgumentException("class " + e.getKey() + " is both a mod class and an embedded class");
                }
            }
        }
        Map<String, Hierarchy.Facts> known = new HashMap<>();
        facts(inputs, map, known);
        facts(plan.adapters(), map, known);
        Hierarchy frames = Hierarchy.layered(known, hierarchy);

        List<Diagnostic> errors = new ArrayList<>();
        Map<String, byte[]> out = new TreeMap<>();
        Map<String, String> sources = new HashMap<>();
        for (byte[] bytes : inputs.values()) {
            ClassNode node = node(bytes);
            Bridges bridges = new Bridges(node);
            MemberLowering.lower(node, plan, bridges, errors);
            Handles.rewrite(node, plan, bridges, errors);
            Folding.fold(node, plan.features(), target, errors);
            DeadCode.remove(node);
            Reachability.check(node, plan.members(), target, errors);
            write(node, plan, map, frames, out, sources, errors);
        }
        for (byte[] bytes : plan.adapters().values()) write(node(bytes), plan, map, frames, out, sources, errors);
        if (!errors.isEmpty()) throw new LowerException(errors);
        return out;
    }

    /** A hierarchy that knows {@code classes} (already in output names) and then {@code next}; for generated classes. */
    public static Hierarchy hierarchy(Map<String, byte[]> classes, Hierarchy next) {
        Map<String, Hierarchy.Facts> known = new HashMap<>();
        facts(classes, new Remapper(ASM9) {}, known);
        return Hierarchy.layered(known, next);
    }

    /**
     * Writes one plan op, the shapes pass 1 emits, plus {@link Op.Kind#LOAD} of a local, which only glue and hook bodies
     * use (blanket-emit).
     */
    public static void op(MethodVisitor mv, Op op) {
        if (op.kind() == Op.Kind.LOAD) mv.visitVarInsn(op.opcode(), (Integer) op.arg());
        else MemberLowering.write(mv, op);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Super class, interfaces and interface flag of each class, by output name. */
    private static void facts(Map<String, byte[]> classes, Remapper map, Map<String, Hierarchy.Facts> out) {
        for (byte[] bytes : classes.values()) {
            ClassReader r = new ClassReader(bytes);
            List<String> interfaces = new ArrayList<>();
            for (String i : r.getInterfaces()) interfaces.add(map.map(i));
            String superName = r.getSuperName() == null ? null : map.map(r.getSuperName());
            out.put(map.map(r.getClassName()), new Hierarchy.Facts(superName, List.copyOf(interfaces), (r.getAccess() & ACC_INTERFACE) != 0));
        }
    }

    /** Passes 5 to 7: collision check, type remap into a frame-computing writer, class-version check. */
    private static void write(ClassNode node, Plan plan, TypeMap map, Hierarchy frames, Map<String, byte[]> out,
                              Map<String, String> sources, List<Diagnostic> errors) {
        String target = plan.target().id();
        Collisions.check(node, map, target, errors);
        String name = map.map(node.name);
        ClassWriter cw = Frames.writer(frames);
        byte[] bytes;
        try {
            node.accept(new ClassRemapper(cw, map));
            bytes = cw.toByteArray();
        } catch (TypeNotPresentException e) {
            String missing = e.typeName().replace('.', '/');
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, target, node.name, missing, "frames",
                    "frames: computing the stack map frames of " + name + " needs the super classes of " + missing
                            + ", which is neither in the mod, in Blanket nor in the game, its libraries or the JDK",
                    "make the mod stop using " + missing.replace('/', '.') + ", or ship the library that has it"));
            return;
        }
        ClassVersions.check(name, bytes, plan.classVersionMax(), target, errors);
        String first = sources.putIfAbsent(name, node.name);
        if (first != null) {
            errors.add(new Diagnostic(ErrorCode.COLLISION, target, name, null, null,
                    "classes " + first + " and " + node.name + " both become " + name + " after the type remap",
                    "rename one of them; Blanket classes move under <mod package>/blanket/"));
            return;
        }
        out.put(name, bytes);
    }
}
