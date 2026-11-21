// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.Lowering;
import com.blanket.core.resolve.ClassIndex;
import com.blanket.core.resolve.Resolver;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * C3 shape (binding-format.md section 4.3): the stack effect of a direct lowering, receiver then arguments then result,
 * must equal the API member's after type remapping, with two allowances: the API receiver may be a subclass of the
 * lowering's owner, and the lowering's result may be a subclass of the API result. No widening and no boxing.
 * Everything is compared in the names of the target's client jar, where class hierarchies can be read.
 */
final class Shapes {
    /** Glue types (D-054, D-056): their Object parameters are cast by the generator. */
    private static final String GLUE_PACKAGE = "com/blanket/runtime/glue/";
    /** The parameter of a leading {@code cast} step: a checkcast takes any reference, so any reference fits. */
    private static final String ANY_REFERENCE = "*";


    /** Parameters (receiver first) and result, as JVM type descriptors. */
    record Effect(List<String> params, String result) {
        String show() {
            return "(" + String.join("", params) + ")" + result;
        }
    }

    /** A lowering whose chain does not fit together. */
    private static final class ChainError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ChainError(String message) {
            super(message, null, false, false);
        }
    }

    /** A part of the lowering the check cannot see: reported as a warning, not as a pass. */
    private static final class Skip extends RuntimeException {
        Skip(String message) {
            super(message, null, false, false);
        }
    }

    private final ClassIndex index;
    private final Map<String, String> alias;

    /** @param alias API class (internal name) to the jar name of the class it aliases on this target */
    Shapes(ClassIndex index, Map<String, String> alias) {
        this.index = index;
        this.alias = alias;
    }

    /**
     * Compares one API member with its lowering.
     *
     * @param owner    the API class that declares the member, internal name
     * @param resolved reference text to its resolution in jar names, null when it did not resolve
     * @return a description of the difference, or null when the shapes fit or the lowering is not a direct one
     */
    String check(ApiClasses.Method api, String owner, Lowering lowering, Function<String, Resolver.Resolved> resolved) {
        return check(api, owner, lowering, resolved, reason -> { });
    }

    /** As above; {@code skipped} gets the reason when the lowering could not be checked (the caller reports a warning). */
    String check(ApiClasses.Method api, String owner, Lowering lowering, Function<String, Resolver.Resolved> resolved,
                 java.util.function.Consumer<String> skipped) {
        Effect want = apiEffect(api, owner);
        Effect got;
        try {
            got = lowered(lowering, resolved);
        } catch (ChainError e) {
            return "API member " + want.show() + ", " + e.getMessage();
        } catch (Skip e) {
            skipped.accept(e.getMessage());
            return null;
        }
        if (got == null) return null;
        String problem = compare(want, got, !api.isStatic(), owner.startsWith(GLUE_PACKAGE));
        return problem == null ? null : "API member " + want.show() + " but the lowering " + got.show() + ": " + problem;
    }

    private Effect apiEffect(ApiClasses.Method m, String owner) {
        List<String> params = new ArrayList<>();
        if (!m.isStatic()) params.add(remap("L" + owner + ";"));
        for (Type a : Type.getArgumentTypes(m.desc())) params.add(remap(a.getDescriptor()));
        return new Effect(params, remap(Type.getReturnType(m.desc()).getDescriptor()));
    }

    private String remap(String desc) {
        int dims = 0;
        while (desc.charAt(dims) == '[') dims++;
        if (desc.charAt(dims) != 'L') return desc;
        String name = desc.substring(dims + 1, desc.length() - 1);
        String to = alias.getOrDefault(name, name);
        return desc.substring(0, dims) + "L" + to + ";";
    }

    private String compare(Effect want, Effect got, boolean hasReceiver, boolean glue) {
        if (want.params().size() != got.params().size()) {
            return "it takes " + got.params().size() + " stack values (receiver included), the API member " + want.params().size();
        }
        for (int i = 0; i < want.params().size(); i++) {
            String a = want.params().get(i);
            String b = got.params().get(i);
            boolean receiver = hasReceiver && i == 0;
            if (b.equals(ANY_REFERENCE) && (a.startsWith("L") || a.startsWith("["))) continue;
            // D-056 D: a glue parameter typed Object takes any reference; the generator emits a checkcast for it
            if (glue && !receiver && a.equals("Ljava/lang/Object;") && (b.startsWith("L") || b.startsWith("["))) continue;
            if (receiver ? !isSubtype(a, b) : !a.equals(b)) {
                return (receiver ? "receiver " : "parameter " + i + " ") + b + " does not accept " + a;
            }
        }
        if (!want.result().equals(got.result()) && !isSubtype(got.result(), want.result())) {
            return "result " + got.result() + " is not " + want.result() + " or a subclass of it";
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------- lowered effects

    /** The effect of a direct lowering, or null when it is not direct, uses a step this check does not model, or has an unresolved part. */
    private Effect lowered(Lowering l, Function<String, Resolver.Resolved> resolved) {
        switch (l.kind()) {
            case INVOKE:
            case GET:
            case PUT:
            case NEW:
                return single(l, resolved, "the lowering");
            case CHAIN:
                return chain(l, resolved);
            default:
                return null;
        }
    }

    private Effect single(Lowering l, Function<String, Resolver.Resolved> resolved, String what) {
        if (l.ref() == null) throw new Skip(what + " has no reference");
        Resolver.Resolved r = resolved.apply(l.ref());
        if (r == null) throw new Skip(what + " (" + l.ref() + ") did not resolve");
        ClassIndex.Info info = index.get(r.owner()).orElse(null);
        if (info == null) throw new Skip(what + ": class " + r.owner() + " is not in the index");
        String self = "L" + r.owner() + ";";
        List<String> params = new ArrayList<>();
        switch (l.kind()) {
            case INVOKE: {
                Integer access = info.methods().get(r.name() + r.desc());
                if (access == null) throw new Skip(what + ": method " + r.owner() + "." + r.name() + r.desc() + " is not in the index");
                if ((access & Opcodes.ACC_STATIC) == 0) params.add(self);
                for (Type a : Type.getArgumentTypes(r.desc())) params.add(a.getDescriptor());
                return new Effect(params, Type.getReturnType(r.desc()).getDescriptor());
            }
            case GET: {
                Integer access = info.fields().get(r.name() + ":" + r.desc());
                if (access == null) throw new Skip(what + ": field " + r.owner() + "." + r.name() + ":" + r.desc() + " is not in the index");
                if ((access & Opcodes.ACC_STATIC) == 0) params.add(self);
                return new Effect(params, r.desc());
            }
            case PUT: {
                Integer access = info.fields().get(r.name() + ":" + r.desc());
                if (access == null) throw new Skip(what + ": field " + r.owner() + "." + r.name() + ":" + r.desc() + " is not in the index");
                if ((access & Opcodes.ACC_STATIC) == 0) params.add(self);
                params.add(r.desc());
                return new Effect(params, "V");
            }
            case NEW: {
                if (!info.methods().containsKey(r.name() + r.desc())) throw new Skip(what + ": constructor " + r.owner() + "." + r.name() + r.desc() + " is not in the index");
                for (Type a : Type.getArgumentTypes(r.desc())) params.add(a.getDescriptor());
                return new Effect(params, self);
            }
            default:
                return null;
        }
    }

    /**
     * A chain takes the receiver and arguments of its first step; every later step works on the previous result, so that
     * result must fit the step's owner. A chain with a step kind this check does not model is skipped.
     */
    private Effect chain(Lowering chain, Function<String, Resolver.Resolved> resolved) {
        List<Lowering> steps = chain.steps();
        Effect first;
        String current;
        Lowering s0 = steps.get(0);
        if (s0.kind() == Lowering.Kind.CAST) {
            Resolver.Resolved r = resolved.apply(s0.ref());
            if (r == null) throw new Skip("chain step 1 (" + s0.ref() + ") did not resolve");
            current = "L" + r.owner() + ";";
            first = new Effect(List.of(ANY_REFERENCE), current);
        } else {
            first = single(s0, resolved, "chain step 1");
            current = first.result();
        }
        List<Lowering> pushed = new ArrayList<>();
        for (int i = 1; i < steps.size(); i++) {
            Lowering s = steps.get(i);
            switch (s.kind()) {
                case CAST: {
                    Resolver.Resolved r = resolved.apply(s.ref());
                    if (r == null) throw new Skip("chain step " + (i + 1) + " (" + s.ref() + ") did not resolve");
                    current = "L" + r.owner() + ";";
                    break;
                }
                case CONST:
                case NEWARRAY:
                    pushed.add(s);   // an argument of the next invoke (D-056)
                    break;
                case INVOKE:
                case GET: {
                    Effect e = single(s, resolved, "chain step " + (i + 1));
                    if (e.params().isEmpty()) {
                        throw new ChainError("chain step " + (i + 1) + " (" + s.ref() + ") must work on the previous result, but it is static");
                    }
                    int args = e.params().size() - 1;
                    if (args != pushed.size()) {
                        throw new ChainError("chain step " + (i + 1) + " (" + s.ref() + ") takes " + args + " arguments but "
                                + pushed.size() + " const/newarray steps come right before it");
                    }
                    if (!isSubtype(current, e.params().get(0))) {
                        throw new ChainError("chain step " + (i + 1) + " (" + s.ref() + ") needs a " + e.params().get(0)
                                + " but the previous step leaves " + current);
                    }
                    for (int k = 0; k < args; k++) {
                        String want = e.params().get(k + 1);
                        Lowering p = pushed.get(k);
                        if (!pushedFits(p, want)) {
                            throw new ChainError("chain step " + (i + 1) + " (" + s.ref() + ") argument " + k + " is " + want
                                    + ", which the " + p.kind().json() + " step " + (i - args + k + 1) + " does not give");
                        }
                    }
                    pushed.clear();
                    current = e.result();
                    break;
                }
                default:
                    throw new Skip("chain step " + (i + 1) + " is a " + s.kind().json() + " step, which the shape check does not model");
            }
        }
        if (!pushed.isEmpty()) {
            throw new ChainError("the last chain steps push " + pushed.size() + " values no invoke consumes");
        }
        return new Effect(first.params(), current);
    }

    /** Whether a const or newarray step gives a value of the descriptor type (binding-format.md 4.3, D-056). */
    private static boolean pushedFits(Lowering step, String type) {
        if (step.kind() == Lowering.Kind.NEWARRAY) return type.equals("[" + step.ref());
        return Lowering.constFits(step.constant(), type);
    }

    // ---------------------------------------------------------------------------------------- hierarchy

    /** True when {@code a} is {@code b} or a class or interface that extends or implements it; only object types have subtypes. */
    boolean isSubtype(String a, String b) {
        if (a.equals(b)) return true;
        if (!a.startsWith("L") || !b.startsWith("L")) return false;
        String target = b.substring(1, b.length() - 1);
        if (target.equals("java/lang/Object")) return true;
        Deque<String> work = new ArrayDeque<>();
        work.add(a.substring(1, a.length() - 1));
        Set<String> seen = new HashSet<>();
        while (!work.isEmpty()) {
            String n = work.poll();
            if (!seen.add(n)) continue;
            if (n.equals(target)) return true;
            index.get(n).ifPresent(info -> {
                if (info.superName() != null) work.add(info.superName());
                work.addAll(info.interfaces());
            });
        }
        return false;
    }
}
