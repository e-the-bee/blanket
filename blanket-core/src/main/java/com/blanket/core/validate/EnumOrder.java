// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * C5 enum order (testing-strategy.md section 2, lowering-and-adapters.md section 4.6): on every supported target, each
 * constant of an aliased API enum has, as its ordinal on the game enum, its index in the API enum. The game field a
 * constant binds to is the {@code get} of its rule; its ordinal is read from the game enum's {@code <clinit>} (the
 * {@code int} passed to the constructor before the {@code PUTSTATIC} of that field), not from the field order. A
 * mismatch is {@link ErrorCode#ORDER}. A constant without a {@code get} rule on the target, or whose reference does not
 * resolve, is left to C1 and C2.
 */
final class EnumOrder {
    private static final String C5 = "C5 enum order";

    /** One API constant on one target: its API ordinal, the rule's field reference and its anchor. */
    private record Constant(String name, int apiOrdinal, String ref, String anchor) {}

    private final BindingSet bindings;
    private final TargetData data;
    private final ApiClasses api;
    private final TargetSpec t;
    private final List<Diagnostic> errors;

    EnumOrder(BindingSet bindings, TargetData data, ApiClasses api, TargetSpec target, List<Diagnostic> errors) {
        this.bindings = bindings;
        this.data = data;
        this.api = api;
        this.t = target;
        this.errors = errors;
    }

    void run() throws IOException {
        Map<String, List<Constant>> enums = new LinkedHashMap<>();   // type file -> constants bound on this target
        Map<String, List<Ref>> refs = new LinkedHashMap<>();
        for (Map.Entry<String, TypeBinding> e : bindings.types().entrySet()) {
            TypeBinding tb = e.getValue();
            ApiClasses.ClassInfo info = api.classInfo(Rules.internal(tb.api())).orElse(null);
            if (info == null || !info.isEnum()) continue;
            int ri = Rules.first(tb.representation(), TypeBinding.Representation::when, t);
            if (ri < 0 || tb.representation().get(ri).alias() == null) continue;
            String anchor = tb.anchor() != null ? tb.anchor() : bindings.targets().anchor();
            List<Constant> constants = new ArrayList<>();
            for (int i = 0; i < info.enumConstants().size(); i++) {
                String name = info.enumConstants().get(i);
                List<TypeBinding.MemberRule> rules = tb.members().get(name);
                int k = rules == null ? -1 : Rules.first(rules, TypeBinding.MemberRule::when, t);
                if (k < 0 || rules.get(k).lower().kind() != Lowering.Kind.GET) continue;
                String text = rules.get(k).lower().ref();
                Ref ref;
                try {
                    ref = Ref.parse(text, e.getKey(), "$");
                } catch (RuntimeException malformed) {
                    continue;   // C2 reports the malformed reference
                }
                constants.add(new Constant(name, i, text, anchor));
                refs.computeIfAbsent(anchor, a -> new ArrayList<>()).add(ref);
            }
            if (!constants.isEmpty()) enums.put(e.getKey(), constants);
        }
        if (enums.isEmpty()) return;

        Resolver.MappingFiles files = data.mappingFiles();
        Resolution named = Resolution.of(t, refs, files, bindings.targets().gameClasses());
        boolean sameNames = t.runtime().equals("obf") || t.runtime().equals("official");
        Resolution jar = named;
        if (!sameNames) {
            Map<String, List<Ref>> resolvedRefs = new LinkedHashMap<>();
            for (Map.Entry<String, List<Ref>> e : refs.entrySet()) {
                for (Ref r : e.getValue()) {
                    if (named.resolved(e.getKey(), r.text()) != null) resolvedRefs.computeIfAbsent(e.getKey(), a -> new ArrayList<>()).add(r);
                }
            }
            jar = Resolution.of(Resolution.jarNames(t), resolvedRefs, files, bindings.targets().gameClasses());
        }
        List<Path> jars = data.classpath(t);
        Map<String, Map<String, Integer>> ordinals = new HashMap<>();   // jar class -> field -> ordinal
        for (Map.Entry<String, List<Constant>> e : enums.entrySet()) {
            String file = e.getKey();
            String simple = Rules.simple(bindings.types().get(file).api());
            List<String> wrong = new ArrayList<>();
            String gameEnum = null;
            for (Constant c : e.getValue()) {
                Resolver.Resolved r = jar.resolved(c.anchor(), c.ref());
                if (r == null || !r.field()) continue;   // C2 reports it
                gameEnum = r.owner();
                Map<String, Integer> table = ordinals.computeIfAbsent(r.owner(), o -> read(jars, o));
                Integer ordinal = table.get(r.name() + ":" + r.desc());
                if (ordinal == null) {
                    wrong.add(c.name() + " (API " + c.apiOrdinal() + ") binds " + r.owner() + "." + r.name()
                            + ", which the <clinit> of " + r.owner() + " does not create as an enum constant");
                } else if (ordinal != c.apiOrdinal()) {
                    wrong.add(c.name() + " is " + c.apiOrdinal() + " in the API but " + ordinal + " on " + t.id() + " (" + r.owner() + "." + r.name() + ")");
                }
            }
            if (wrong.isEmpty()) continue;
            errors.add(new Diagnostic(ErrorCode.ORDER, t.id(), simple, gameEnum, null,
                    C5 + ": the constants of " + simple + " are not in the order of the game enum: " + String.join("; ", wrong),
                    "declare the constants of the API enum " + simple + " in the game's order (javap -c the <clinit> of the game enum), "
                            + "or bind each constant in " + file + " to the game field of the same meaning"));
        }
    }

    /**
     * Field ({@code name:desc}) to ordinal for the enum constants a class's {@code <clinit>} creates: each
     * {@code NEW; DUP; LDC name; <int>; ...; INVOKESPECIAL <init>(Ljava/lang/String;I...)V; PUTSTATIC field}. Empty when
     * the class or its {@code <clinit>} is missing.
     */
    static Map<String, Integer> ordinals(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals("<clinit>")) continue;
            // One frame per NEW not yet constructed: the first two constants pushed for it (nested NEWs, such as a
            // constructor argument built in place, get their own frame).
            java.util.Deque<List<Object>> frames = new java.util.ArrayDeque<>();
            Integer constructed = null;   // the ordinal of the constant just constructed, waiting for its PUTSTATIC
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (i instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) {
                    frames.push(new ArrayList<>());
                    constructed = null;
                } else if (constant(i) != null) {
                    if (!frames.isEmpty() && frames.peek().size() < 2) frames.peek().add(constant(i));
                } else if (i instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")) {
                    if (frames.isEmpty()) continue;
                    List<Object> pushed = frames.pop();
                    boolean enumConstant = call.desc.startsWith("(Ljava/lang/String;I") && pushed.size() == 2 && pushed.get(0) instanceof String;
                    constructed = enumConstant && frames.isEmpty() && pushed.get(1) instanceof Integer ordinal ? ordinal : null;
                } else if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC) {
                    if (constructed != null && f.owner.equals(node.name)) out.putIfAbsent(f.name + ":" + f.desc, constructed);
                    constructed = null;
                }
            }
        }
        return out;
    }

    /** The constant an instruction pushes (a string or an int), or null. */
    private static Object constant(AbstractInsnNode i) {
        int op = i.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (i instanceof IntInsnNode n && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) return n.operand;
        if (i instanceof LdcInsnNode l && (l.cst instanceof String || l.cst instanceof Integer)) return l.cst;
        return null;
    }

    private static Map<String, Integer> read(List<Path> jars, String internalName) {
        for (Path jar : jars) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry(internalName + ".class");
                if (e == null) continue;
                try (InputStream in = z.getInputStream(e)) {
                    return ordinals(in.readAllBytes());
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("cannot read " + internalName + " from " + jar, e);
            }
        }
        return Map.of();
    }
}
