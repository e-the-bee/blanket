// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.lookup.Routes;
import com.blanket.core.resolve.GameClasses;
import com.blanket.core.resolve.ClassIndex;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import com.blanket.core.validate.TargetData;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The references of one target's plan, in two passes. In the collecting pass ({@link #dry()}) every lookup records the
 * reference and answers with a placeholder; {@link #resolve} then routes all of them through {@link Resolver} (runtime
 * names for the plan, client-jar names for access flags) and opens the class index; the second pass gets real answers.
 */
final class Names implements AutoCloseable {

    /** Where a member is declared: whether it is static, and whether the owner it is called on is an interface. */
    record Access(boolean isStatic, boolean ownerInterface) {}

    /** One collected reference. An optional one that fails is kept aside ({@link #failure}) instead of failing the plan. */
    private record Need(String anchor, Ref ref, String label, boolean optional) {}

    private final TargetSpec t;
    private final GameClasses game;
    private final Map<String, Need> needs = new LinkedHashMap<>();
    private final Map<String, Resolver.Resolved> named = new HashMap<>();
    private final Map<String, Resolver.Resolved> jar = new HashMap<>();
    private final Map<String, Diagnostic> optionalFailures = new HashMap<>();
    private ClassIndex index;

    Names(TargetSpec t, GameClasses game) {
        this.t = t;
        this.game = game;
    }

    /** True during the collecting pass, before {@link #resolve}. */
    boolean dry() {
        return index == null;
    }

    private static String key(String anchor, String text) {
        return anchor + "\u0000" + text;
    }

    /** The runtime names of a reference; a placeholder made of the written names during the collecting pass. */
    Resolver.Resolved named(String anchor, String text, String label) {
        Ref ref = parse(text, label);
        if (dry()) {
            needs.merge(key(anchor, text), new Need(anchor, ref, label, false), (old, now) -> old.optional() ? now : old);
            return new Resolver.Resolved(text, ref.owner(), ref.name(), ref.desc(), ref.field());
        }
        Resolver.Resolved r = named.get(key(anchor, text));
        if (r == null) throw new IllegalStateException("reference not collected: " + text);
        return r;
    }

    /**
     * As {@link #named}, for a reference whose failure the caller handles itself: after {@link #resolve} the runtime
     * names, or null when it did not resolve ({@link #failure} says why). A reference also collected by
     * {@link #named} is required.
     */
    Resolver.Resolved optional(String anchor, String text, String label) {
        Ref ref = parse(text, label);
        if (dry()) {
            needs.putIfAbsent(key(anchor, text), new Need(anchor, ref, label, true));
            return new Resolver.Resolved(text, ref.owner(), ref.name(), ref.desc(), ref.field());
        }
        return named.get(key(anchor, text));
    }

    /** The client-jar names of a resolved reference (loader references: their literal names), or null. */
    Resolver.Resolved jarNamed(String anchor, String text) {
        Need n = needs.get(key(anchor, text));
        if (n == null || dry()) throw new IllegalStateException("reference not collected or not resolved: " + text);
        return (n.ref().ns().equals("loader") ? named : jar).get(key(anchor, text));
    }

    /** Why an {@link #optional} reference did not resolve, or null when it did. */
    Diagnostic failure(String anchor, String text) {
        return optionalFailures.get(key(anchor, text));
    }

    /**
     * Runtime class name to client-jar class name for every class the resolved references name (owners and descriptor
     * classes), the start of {@link TargetHierarchy}'s translation. Loader references and same-named classes are left out.
     */
    Map<String, String> runtimeToJar() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Resolver.Resolved> e : jar.entrySet()) {
            Resolver.Resolved r = named.get(e.getKey()), j = e.getValue();
            if (r == null) continue;
            pair(out, r.owner(), j.owner());
            if (r.desc() == null || j.desc() == null) continue;
            List<String> rc = com.blanket.core.mapping.Desc.classesIn(r.desc()), jc = com.blanket.core.mapping.Desc.classesIn(j.desc());
            if (rc.size() != jc.size()) continue;
            for (int i = 0; i < rc.size(); i++) pair(out, rc.get(i), jc.get(i));
        }
        return out;
    }

    private static void pair(Map<String, String> out, String runtime, String jarName) {
        if (!runtime.equals(jarName)) out.putIfAbsent(runtime, jarName);
    }

    /** Access flags of a member reference, read from the jar it lives in (client jar, libraries, loader jar, JDK). */
    Access access(String anchor, String text, String label) {
        Ref ref = parse(text, label);
        if (dry()) {
            needs.merge(key(anchor, text), new Need(anchor, ref, label, false), (old, now) -> old.optional() ? now : old);
            return new Access(false, false);
        }
        boolean loader = ref.ns().equals("loader");
        Resolver.Resolved r = (loader ? named : jar).get(key(anchor, text));
        Optional<ClassIndex.Info> owner = index.get(r.owner());
        if (owner.isEmpty()) {
            throw Planner.fail(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, text, "jar",
                    "class " + r.owner() + " is not in the jars of " + t.id() + (loader ? " (client jar and loader jar)" : " (client jar and its libraries)"),
                    loader ? "add the loader artifact of " + t.id() + " to loader-artifacts.json, or fix the reference"
                            : "run `./gradlew lookup --ref \"" + text + "\"` and fix the reference"));
        }
        String member = r.field() ? r.name() + ":" + r.desc() : r.name() + r.desc();
        Integer flags = find(r.owner(), member, r.field(), new java.util.HashSet<>());
        if (flags == null) {
            throw Planner.fail(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, text, "jar",
                    (r.field() ? "field " : "method ") + r.owner() + "." + member + " is not declared in " + r.owner() + " or its supertypes",
                    "reference the member on its declaring class (binding-format.md 2.3); `./gradlew lookup --ref \"" + text + "\"`"));
        }
        return new Access((flags & Opcodes.ACC_STATIC) != 0, owner.get().isInterface());
    }

    /** Flags of a member declared in {@code owner} or a supertype, or null. */
    private Integer find(String owner, String member, boolean field, java.util.Set<String> seen) {
        if (owner == null || !seen.add(owner)) return null;
        Optional<ClassIndex.Info> info = index.get(owner);
        if (info.isEmpty()) return null;
        Integer flags = (field ? info.get().fields() : info.get().methods()).get(member);
        if (flags != null) return flags;
        flags = find(info.get().superName(), member, field, seen);
        for (int i = 0; flags == null && i < info.get().interfaces().size(); i++) {
            flags = find(info.get().interfaces().get(i), member, field, seen);
        }
        return flags;
    }

    private Ref parse(String text, String label) {
        try {
            return Ref.parse(text, label, "$");
        } catch (BindingException e) {
            throw Planner.fail(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, text, "parse", "C2 resolution: " + e.getMessage(),
                    "write the reference as [ns:]class#member with a descriptor (binding-format.md 2.3)"));
        }
    }

    /**
     * Resolves every collected reference and opens the class index over the target's jars and the libraries the
     * references name. Every reference that does not resolve is reported at once.
     */
    void resolve(TargetData data) throws IOException {
        List<Diagnostic> errors = new ArrayList<>();
        Map<String, List<Need>> byAnchor = new LinkedHashMap<>();
        for (Need n : needs.values()) {
            String route = Routes.problem(t, n.ref(), game);
            if (route != null) {
                report(n, unresolved(n, Routes.STEP, route), null, errors);
            } else {
                byAnchor.computeIfAbsent(n.anchor(), k -> new ArrayList<>()).add(n);
            }
        }
        boolean sameNames = t.runtime().equals("obf") || t.runtime().equals("official");
        TargetSpec jarSpec = new TargetSpec(t.loader(), t.mc(), "obf", t.emitter(), t.java(), t.mcpVersion());
        for (Map.Entry<String, List<Need>> e : byAnchor.entrySet()) {
            String anchor = e.getKey();
            run(new Resolver(t, anchor, data.mappingFiles(), game), anchor, e.getValue(), named, errors);
            List<Need> forJar = new ArrayList<>();
            for (Need n : e.getValue()) {
                if (named.containsKey(key(anchor, n.ref().text())) && !n.ref().ns().equals("loader")) forJar.add(n);
            }
            if (sameNames) {
                for (Need n : forJar) jar.put(key(anchor, n.ref().text()), named.get(key(anchor, n.ref().text())));
            } else {
                run(new Resolver(jarSpec, anchor, data.mappingFiles(), game), anchor, forJar, jar, errors);
            }
        }
        if (!errors.isEmpty()) throw Planner.fail(errors);

        Set<String> libraries = new TreeSet<>();
        for (Map.Entry<String, Resolver.Resolved> e : jar.entrySet()) {
            if (isLibraryClass(e.getValue().owner())) libraries.add(e.getValue().owner());
        }
        List<Path> jars = new ArrayList<>(data.classpath(t));
        jars.addAll(data.libraryJars(t, libraries));
        index = ClassIndex.open(jars, true);
    }

    private void run(Resolver resolver, String anchor, List<Need> list, Map<String, Resolver.Resolved> into, List<Diagnostic> errors) {
        if (list.isEmpty()) return;
        List<Ref> refs = new ArrayList<>();
        Map<String, Need> byText = new HashMap<>();
        for (Need n : list) {
            refs.add(n.ref());
            byText.put(n.ref().text(), n);
        }
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out;
        try {
            out = resolver.resolve(refs, problems);
        } catch (IllegalStateException unreadable) {
            for (Need n : list) report(n, unresolved(n, "mapping file", unreadable.getMessage()), into, errors);
            return;
        }
        for (Map.Entry<String, Resolver.Resolved> e : out.entrySet()) into.put(key(anchor, e.getKey()), e.getValue());
        for (Resolver.Problem p : problems) {
            Need n = byText.get(p.ref());
            if (n != null) report(n, unresolved(n, p.step(), p.message()), into, errors);
        }
    }

    /** A failed reference fails the plan; an optional one is set aside and dropped from {@code into}. */
    private void report(Need n, Diagnostic d, Map<String, Resolver.Resolved> into, List<Diagnostic> errors) {
        if (!n.optional()) {
            errors.add(d);
            return;
        }
        optionalFailures.putIfAbsent(key(n.anchor(), n.ref().text()), d);
        if (into != null) into.remove(key(n.anchor(), n.ref().text()));
    }

    private Diagnostic unresolved(Need n, String step, String message) {
        return new Diagnostic(ErrorCode.UNRESOLVED, t.id(), n.label(), n.ref().text(), step, "C2 resolution: " + message,
                "run `./gradlew lookup --ref \"" + n.ref().text().replace("\"", "\\\"") + "\"` for the failing step and candidates, then fix the binding");
    }

    /** A class that is neither the game's nor the JDK's: a library shipped beside the client jar (as TargetChecks decides). */
    private boolean isLibraryClass(String owner) {
        return !(game.isGame(owner)
                || owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/"));
    }

    @Override
    public void close() throws IOException {
        if (index != null) index.close();
    }
}
