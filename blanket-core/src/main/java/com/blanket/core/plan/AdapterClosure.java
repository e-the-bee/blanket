// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.mapping.Desc;
import com.blanket.core.resolve.GameClasses;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The adapter classes one target's plan needs, in runtime names (lowering-and-adapters.md section 5.4). The plan's
 * {@link Lowered.Adapter} members name classes of {@code <bindings>/build/adapters/<set>/} (shipping names, written by
 * {@code remapAdapters}); their closure over adapter-to-adapter references is loaded, and every game class and member
 * the closure names becomes a reference in the set's shipping namespace, collected into {@link Names} with the plan's
 * own references. A member whose bytecode owner is not its declaring class (a call through a subclass, an inherited
 * field read through the adapter's own class) is referenced on the declaring class that {@code adapter-refs.json}
 * names, because mapping files list members only there (binding-format.md section 2.3); an adapter method that
 * overrides a game method is renamed with that game method.
 *
 * <p>A reference without a mapping row keeps its name, as every remapper does for unobfuscated names (constructors,
 * {@code values()}, overrides of JDK methods, library classes), but only when the target's jars have it under that
 * name; otherwise it fails with the resolver's message. Every resolved class and member is also looked up in the jars
 * (binding-format.md section 2.4, last paragraph). Invisible annotations are compile-time data and are dropped.
 *
 * <p>Used in two steps around {@link Names#resolve}: {@link #collect} during the collecting pass, {@link #remap} after.
 */
final class AdapterClosure {
    static final String PACKAGE = "com/blanket/adapters/";
    static final String REFS_FILE = "adapter-refs.json";
    private static final Set<String> NAMESPACES = Set.of("intermediary", "lf", "official");
    private static final String REBUILD = "run `./gradlew :blanket-bindings:remapAdapters` and plan again";

    /** {@code adapter-refs.json} of one set (lowering contract in M2 plan section 3): shipping names throughout. */
    record Refs(String set, String namespace, Map<String, String> declaring, Map<String, String> overrides) {}

    /** One adapter set as loaded: its refs, the closure's class files (shipping names) and the references they make. */
    private static final class Loaded {
        final String set;
        final Path dir;
        final Refs refs;
        final Map<String, byte[]> classes = new TreeMap<>();
        /** Shipping game class to its reference text ({@code ns:class}). */
        final Map<String, String> classRefs = new LinkedHashMap<>();
        /** Bytecode member key ({@code owner#name desc}, {@code owner#name:desc}) to its reference on the declaring game class. */
        final Map<String, Member> memberRefs = new LinkedHashMap<>();

        Loaded(String set, Path dir, Refs refs) {
            this.set = set;
            this.dir = dir;
            this.refs = refs;
        }
    }

    /** A game member reference: the text collected into {@link Names}, its declaring class and its shipping name and type. */
    private record Member(String text, String declaring, String name, String desc, boolean field, String label) {}

    private final TargetSpec t;
    private final GameClasses game;
    private final String anchor;
    private final Map<String, Lowered> members;
    private final Map<String, Loaded> sets = new TreeMap<>();
    private final List<Diagnostic> errors = new ArrayList<>();

    private AdapterClosure(TargetSpec t, GameClasses game, String anchor, Map<String, Lowered> members) {
        this.t = t;
        this.game = game;
        this.anchor = anchor;
        this.members = members;
    }

    /**
     * Loads the closure of every adapter class {@code members} names and collects its game references into
     * {@code names}, which must still be collecting. Problems with the built adapter folder fail the plan at once.
     *
     * @param members the plan's members (API member key to lowering), from the collecting pass
     */
    static AdapterClosure collect(Path bindingsDir, TargetSpec t, GameClasses game, String anchor, Map<String, Lowered> members, Names names) {
        AdapterClosure c = gather(bindingsDir, t, game, anchor, members, Map.of(), names);
        if (!c.errors.isEmpty()) throw Planner.fail(c.errors);
        return c;
    }

    /**
     * As {@link #collect}, with {@code extraRoots} (set to adapter classes) closed over too, and every problem left in
     * {@link #errors()} instead of thrown; the game references of the sets that loaded are collected all the same.
     * The bindings validator uses it (C4, C6).
     */
    static AdapterClosure gather(Path bindingsDir, TargetSpec t, GameClasses game, String anchor, Map<String, Lowered> members,
                                 Map<String, List<String>> extraRoots, Names names) {
        if (!names.dry()) throw new IllegalStateException("adapter references are collected before the names are resolved");
        AdapterClosure c = new AdapterClosure(t, game, anchor, members);
        Map<String, List<String>> roots = new TreeMap<>();   // set -> root classes, in member order
        Map<String, String> labelOf = new LinkedHashMap<>();
        for (Map.Entry<String, Lowered> e : members.entrySet()) {
            if (!(e.getValue() instanceof Lowered.Adapter a)) continue;
            String set = setOf(a.owner());
            roots.computeIfAbsent(set, k -> new ArrayList<>()).add(a.owner());
            labelOf.putIfAbsent(a.owner(), label(e.getKey()));
        }
        for (Map.Entry<String, List<String>> e : extraRoots.entrySet()) {
            for (String owner : e.getValue()) {
                roots.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(owner);
                labelOf.putIfAbsent(owner, "adapter " + e.getKey() + ":" + simple(owner));
            }
        }
        for (Map.Entry<String, List<String>> e : roots.entrySet()) {
            Loaded s = c.load(bindingsDir, e.getKey(), labelOf.get(e.getValue().get(0)));
            if (s != null) c.close(s, e.getValue(), labelOf);
        }
        for (Loaded s : c.sets.values()) {
            for (Map.Entry<String, String> e : s.classRefs.entrySet()) names.optional(anchor, e.getValue(), "adapter " + s.set);
            for (Member m : s.memberRefs.values()) names.optional(anchor, m.text(), m.label());
        }
        return c;
    }

    /** The problems found so far ({@link #gather}, {@link #remapAll}, {@link #checkOverrides}). */
    List<Diagnostic> errors() {
        return errors;
    }

    /** True when the plan names no adapter. */
    boolean isEmpty() {
        return sets.isEmpty();
    }

    /**
     * The closure in runtime names (class names still {@code com/blanket/adapters/<set>/...}), after {@code names} has
     * resolved. Checks every reference against the target's jars through {@code hierarchy}, and every planned adapter
     * method's descriptor against the API member's with the plan's alias types ({@code SHAPE} when they differ). All
     * failures are thrown together.
     *
     * @param types the plan's alias API type to runtime class map
     */
    Map<String, byte[]> remap(Map<String, String> types, Names names, TargetHierarchy hierarchy) {
        Map<String, byte[]> out = remapAll(types, names, hierarchy);
        if (!errors.isEmpty()) throw Planner.fail(errors);
        return out;
    }

    /** As {@link #remap}, leaving every problem in {@link #errors()} instead of throwing. */
    Map<String, byte[]> remapAll(Map<String, String> types, Names names, TargetHierarchy hierarchy) {
        Map<String, byte[]> out = new TreeMap<>();
        for (Loaded s : sets.values()) {
            Map<String, String> classMap = classMap(s, names, hierarchy);
            Map<String, String> memberMap = memberMap(s, names, hierarchy, classMap);
            Remapper runtime = new Remapper(Opcodes.ASM9) {
                @Override
                public String map(String internalName) {
                    return classMap.getOrDefault(internalName, internalName);
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    return memberMap.getOrDefault(owner + "#" + name + descriptor, name);
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    return memberMap.getOrDefault(owner + "#" + name + ":" + descriptor, name);
                }
            };
            for (Map.Entry<String, byte[]> e : s.classes.entrySet()) {
                ClassWriter w = new ClassWriter(0);
                new ClassReader(e.getValue()).accept(new DropInvisibleAnnotations(new ClassRemapper(w, runtime)), 0);
                out.put(e.getKey(), w.toByteArray());
            }
        }
        checkShapes(types, out);
        return out;
    }

    // ---------------------------------------------------------------------------------------- overrides (C6)

    /**
     * C6 (testing-strategy.md section 2): every {@code overrides} entry of a loaded set's {@code adapter-refs.json}
     * whose host class is in the closure resolves on this target, the runtime method exists in the jars, and the
     * overridden class is a supertype of the host in runtime names, so the host's method really overrides. Replaces
     * the resolution failures {@link #remapAll} reported for those references with these diagnostics.
     *
     * @param remapped the closure from {@link #remapAll}
     */
    void checkOverrides(Names names, TargetHierarchy hierarchy, Map<String, byte[]> remapped) {
        List<Diagnostic> found = new ArrayList<>();
        Set<String> texts = new HashSet<>();
        for (Loaded s : sets.values()) {
            for (Map.Entry<String, String> e : s.refs.overrides().entrySet()) {
                String key = e.getKey();
                int hash = key.indexOf('#'), paren = key.indexOf('(', hash);
                String host = key.substring(0, hash);
                if (!s.classes.containsKey(host) || paren < 0) continue;
                String name = key.substring(hash + 1, paren), desc = key.substring(paren);
                String overridden = e.getValue();
                if (!game.isGame(overridden)) continue;   // a JDK or library method: no mapping, nothing target-specific
                String text = s.refs.namespace() + ":" + overridden + "#" + name + desc;
                String label = "adapter " + s.set + ":" + simple(host);
                String method = simple(host) + "." + name + desc;
                String fix = "make " + method + " override a method " + t.mc() + " has (javap the stub jar and `./gradlew lookup --ref \""
                        + text + "\"`), or give " + t.mc() + " its own host class, then " + REBUILD;
                texts.add(text);
                Resolver.Resolved r = names.optional(anchor, text, label);
                String runtimeOwner, runtimeMethod;
                if (r != null) {
                    Resolver.Resolved j = names.jarNamed(anchor, text);
                    runtimeOwner = r.owner();
                    runtimeMethod = r.owner() + "." + r.name() + r.desc();
                    if (j == null || !hierarchy.jarDeclares(j.owner(), j.name() + j.desc(), false, true)) {
                        found.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, text, "jar",
                                "C6 overrides: " + method + " overrides " + runtimeMethod + " (runtime names), which the jars of " + t.id() + " do not have", fix));
                        continue;
                    }
                } else if (unmappedPresent(hierarchy, overridden, name, desc)) {
                    runtimeOwner = overridden;   // no mapping row, present under its own name (as memberMap accepts it)
                    runtimeMethod = overridden + "." + name + desc;
                } else {
                    Diagnostic why = names.failure(anchor, text);
                    found.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, text, why != null ? why.namespaceStep() : null,
                            "C6 overrides: " + method + " overrides " + overridden + "." + name + desc + ", which does not resolve on " + t.id()
                                    + (why != null ? ": " + why.detail() : ""), fix));
                    continue;
                }
                Set<String> supers = supertypes(host, remapped, hierarchy);
                if (!supers.contains(runtimeOwner)) {
                    found.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, text, "hierarchy",
                            "C6 overrides: " + method + " is meant to override " + runtimeMethod + " (runtime names), but "
                                    + runtimeOwner + " is not a supertype of " + host + " on " + t.id() + " (supertypes: " + supers + ")", fix));
                }
            }
        }
        errors.removeIf(d -> texts.contains(d.reference()));
        errors.addAll(found);
    }

    /** Whether a game method without a mapping row is in the jars under its own (shipping) names. */
    private static boolean unmappedPresent(TargetHierarchy h, String owner, String name, String desc) {
        String jarOwner = h.toJar(owner);
        String jarDesc = Desc.mapClasses(desc, c -> h.toJar(c) instanceof String j ? j : c);
        return jarOwner != null && h.jarDeclares(jarOwner, name + jarDesc, false, true);
    }

    /** Every supertype of an adapter class in runtime names: adapter classes from the remapped closure, the rest from the hierarchy. */
    private static Set<String> supertypes(String cls, Map<String, byte[]> remapped, TargetHierarchy hierarchy) {
        Set<String> out = new java.util.LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>();
        work.add(cls);
        while (!work.isEmpty()) {
            String c = work.poll();
            List<String> next = new ArrayList<>();
            byte[] bytes = remapped.get(c);
            if (bytes != null) {
                ClassReader r = new ClassReader(bytes);
                if (r.getSuperName() != null) next.add(r.getSuperName());
                next.addAll(List.of(r.getInterfaces()));
            } else {
                hierarchy.get(c).ifPresent(f -> {
                    if (f.superName() != null) next.add(f.superName());
                    next.addAll(f.interfaces());
                });
            }
            for (String n : next) if (out.add(n)) work.add(n);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------- loading

    private static String setOf(String adapterOwner) {
        int slash = adapterOwner.indexOf('/', PACKAGE.length());
        if (!adapterOwner.startsWith(PACKAGE) || slash < 0) throw new IllegalArgumentException("not an adapter class: " + adapterOwner);
        return adapterOwner.substring(PACKAGE.length(), slash);
    }

    /** {@code com/blanket/api/Player.x()D} gives {@code Player.x}. */
    private static String label(String memberKey) {
        int dot = memberKey.indexOf('.');
        int end = memberKey.length();
        for (char ch : new char[] {'(', ':'}) {
            int i = memberKey.indexOf(ch, dot);
            if (i > 0) end = Math.min(end, i);
        }
        return memberKey.substring(memberKey.lastIndexOf('/', dot) + 1, end);
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private Loaded load(Path bindingsDir, String set, String label) {
        Path dir = bindingsDir.resolve("build").resolve("adapters").resolve(set);
        Path file = dir.resolve(REFS_FILE);
        if (!Files.isRegularFile(file)) {
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, set + ":" + REFS_FILE, null,
                    "the adapter set " + set + " is not built: " + file + " does not exist", REBUILD));
            return null;
        }
        Refs refs;
        try {
            refs = parseRefs(BindingFiles.readJson(file), "build/adapters/" + set + "/" + REFS_FILE);
        } catch (BindingException e) {
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, set + ":" + REFS_FILE, null, e.getMessage(), REBUILD));
            return null;
        }
        if (!refs.set().equals(set)) {
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, set + ":" + REFS_FILE, null,
                    file + " is the refs file of the set " + refs.set() + ", not of " + set, REBUILD));
            return null;
        }
        Loaded s = new Loaded(set, dir, refs);
        sets.put(set, s);
        return s;
    }

    /**
     * Parses {@code adapter-refs.json}:
     * {@code {"schema":1,"set":..,"namespace":..,"declaring":{key: owner},"overrides":{key: owner}}}.
     */
    static Refs parseRefs(JsonElement root, String file) {
        if (!root.isJsonObject()) throw new BindingException(file, "$", "expected an object");
        JsonObject o = root.getAsJsonObject();
        for (String k : o.keySet()) {
            if (!Set.of("schema", "set", "namespace", "declaring", "overrides").contains(k)) throw new BindingException(file, "$." + k, "unknown key");
        }
        if (!o.has("schema") || !o.get("schema").isJsonPrimitive() || o.get("schema").getAsInt() != 1) {
            throw new BindingException(file, "$.schema", "expected 1");
        }
        String set = text(o, "set", file);
        String namespace = text(o, "namespace", file);
        if (!NAMESPACES.contains(namespace)) throw new BindingException(file, "$.namespace", "'" + namespace + "' is not one of " + new java.util.TreeSet<>(NAMESPACES));
        return new Refs(set, namespace, owners(o, "declaring", file), owners(o, "overrides", file));
    }

    private static String text(JsonObject o, String key, String file) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new BindingException(file, "$." + key, "expected a string");
        return e.getAsString();
    }

    private static Map<String, String> owners(JsonObject o, String key, String file) {
        JsonElement e = o.get(key);
        if (e == null) return Map.of();
        if (!e.isJsonObject()) throw new BindingException(file, "$." + key, "expected an object from member to owner");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
            if (!m.getValue().isJsonPrimitive() || !m.getValue().getAsJsonPrimitive().isString() || m.getKey().indexOf('#') <= 0) {
                throw new BindingException(file, "$." + key + "." + m.getKey(), "expected \"<owner>#<member>\": \"<owner>\"");
            }
            out.put(m.getKey(), m.getValue().getAsString());
        }
        return Map.copyOf(out);
    }

    /** Loads the roots and every adapter class of the set they reach, recording the game classes and members each names. */
    private void close(Loaded s, List<String> roots, Map<String, String> labelOf) {
        String setPackage = PACKAGE + s.set + "/";
        Deque<String> work = new ArrayDeque<>();
        Map<String, String> from = new LinkedHashMap<>();   // class -> what needs it, for messages
        for (String r : roots) {
            if (from.putIfAbsent(r, labelOf.get(r)) == null) work.add(r);
        }
        while (!work.isEmpty()) {
            String name = work.poll();
            Path file = s.dir.resolve(name + ".class");
            if (!Files.isRegularFile(file)) {
                errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), from.get(name), s.set + ":" + name.substring(setPackage.length()).replace('/', '.'), null,
                        "the adapter class " + name + " is not in " + s.dir, REBUILD));
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), from.get(name), name, null, "cannot read " + file + ": " + e.getMessage(), REBUILD));
                continue;
            }
            s.classes.put(name, bytes);
            String label = "adapter " + s.set + ":" + simple(name);
            Remapper record = new Remapper(Opcodes.ASM9) {
                @Override
                public String map(String internalName) {
                    if (internalName.startsWith(setPackage)) {
                        if (from.putIfAbsent(internalName, label) == null) work.add(internalName);
                    } else if (game.isGame(internalName)) {
                        s.classRefs.putIfAbsent(internalName, s.refs.namespace() + ":" + internalName);
                    }
                    return internalName;
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    member(s, owner, name, descriptor, false, label);
                    return name;
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    member(s, owner, name, descriptor, true, label);
                    return name;
                }
            };
            new ClassReader(bytes).accept(new DropInvisibleAnnotations(new ClassRemapper(new ClassWriter(0), record)), 0);
        }
    }

    /** Records a member use or declaration when it is a game member; constructors and initializers only need their classes. */
    private void member(Loaded s, String owner, String name, String desc, boolean field, String label) {
        if (name.equals("<init>") || name.equals("<clinit>")) return;
        String key = owner + "#" + name + (field ? ":" : "") + desc;
        if (s.memberRefs.containsKey(key)) return;
        String declaring = declaring(s.refs, owner, name, desc, field);
        if (declaring == null) return;
        s.classRefs.putIfAbsent(declaring, s.refs.namespace() + ":" + declaring);
        String text = s.refs.namespace() + ":" + declaring + "#" + name + (field ? ":" : "") + desc;
        s.memberRefs.put(key, new Member(text, declaring, name, desc, field, label));
    }

    /**
     * The game class that declares a member used with this bytecode owner, or null when it is no game member (the
     * adapter's own, or one a JDK or library class declares). An adapter method listed in {@code overrides} is declared
     * by the game method it overrides; any other owner listed in {@code declaring} defers to the class named there.
     */
    static String declaring(Refs refs, String owner, String name, String desc, boolean field, GameClasses game) {
        Set<String> seen = new HashSet<>();
        for (String o = owner; o != null && seen.add(o); ) {
            String key = o + "#" + name + (field ? ":" : "") + desc;
            String overridden = field ? null : refs.overrides().get(key);
            if (overridden != null) return game.isGame(overridden) ? overridden : null;
            String next = refs.declaring().get(key);
            if (game.isGame(o)) return next == null ? o : game.isGame(next) ? next : null;
            o = next;
        }
        return null;
    }

    private String declaring(Refs refs, String owner, String name, String desc, boolean field) {
        return declaring(refs, owner, name, desc, field, game);
    }

    // ---------------------------------------------------------------------------------------- resolving

    /** Shipping game class to runtime class, for every class the closure names. */
    private Map<String, String> classMap(Loaded s, Names names, TargetHierarchy h) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.classRefs.entrySet()) {
            String text = e.getValue();
            Resolver.Resolved r = names.optional(anchor, text, "adapter " + s.set);
            if (r != null) {
                Resolver.Resolved j = names.jarNamed(anchor, text);
                if (j == null) {
                    errors.add(failure(names, text, "adapter " + s.set));
                } else if (h.jarInfo(j.owner()).isEmpty()) {
                    errors.add(missing("adapter " + s.set, text, "class " + j.owner() + " (runtime name " + r.owner() + ") is not in the jars of " + t.id()));
                } else {
                    out.put(e.getKey(), r.owner());
                }
            } else if (h.toJar(e.getKey()) instanceof String jarName && h.jarInfo(jarName).isPresent()) {
                out.put(e.getKey(), e.getKey());   // no mapping row, present under its own name: a library or unobfuscated class
            } else {
                errors.add(failure(names, text, "adapter " + s.set));
            }
        }
        return out;
    }

    /** Bytecode member key to runtime member name, for every game member the closure uses, overrides included. */
    private Map<String, String> memberMap(Loaded s, Names names, TargetHierarchy h, Map<String, String> classMap) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Member> e : s.memberRefs.entrySet()) {
            Member m = e.getValue();
            String sep = m.field() ? ":" : "";
            Resolver.Resolved r = names.optional(anchor, m.text(), m.label());
            if (r != null) {
                Resolver.Resolved j = names.jarNamed(anchor, m.text());
                if (j == null) {
                    errors.add(failure(names, m.text(), m.label()));
                } else if (!h.jarDeclares(j.owner(), j.name() + sep + j.desc(), m.field(), true)) {
                    errors.add(missing(m.label(), m.text(), (m.field() ? "field " : "method ") + j.owner() + "." + j.name() + sep + j.desc()
                            + " (runtime " + r.owner() + "." + r.name() + sep + r.desc() + ") is not declared in the jars of " + t.id()));
                } else {
                    out.put(e.getKey(), r.name());
                }
                continue;
            }
            // No mapping row: an unobfuscated member keeps its name if the jar has it under that name.
            String owner = classMap.get(m.declaring());
            if (owner == null) continue;   // the class failed and is reported
            String jarOwner = h.toJar(owner);
            String jarDesc = Desc.mapClasses(Desc.mapClasses(m.desc(), c -> classMap.getOrDefault(c, c)), c -> h.toJar(c) instanceof String j ? j : c);
            if (jarOwner == null || !h.jarDeclares(jarOwner, m.name() + sep + jarDesc, m.field(), true)) errors.add(failure(names, m.text(), m.label()));
        }
        return out;
    }

    private Diagnostic failure(Names names, String text, String label) {
        Diagnostic d = names.failure(anchor, text);
        return d != null ? d : missing(label, text, "the reference has no client-jar name on " + t.id());
    }

    private Diagnostic missing(String label, String text, String detail) {
        return new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, text, "jar", "C2 resolution: " + detail,
                "the adapter must not use this member on " + t.id() + ": fix the adapter source or give " + t.mc() + " its own adapter, then " + REBUILD);
    }

    // ---------------------------------------------------------------------------------------- shape

    /**
     * Every planned adapter method exists in the remapped closure, is static, and has the API member's descriptor with
     * the alias types replaced by the plan's runtime classes (receiver first, binding-format.md section 4.3).
     */
    private void checkShapes(Map<String, String> types, Map<String, byte[]> remapped) {
        for (Map.Entry<String, Lowered> e : members.entrySet()) {
            if (!(e.getValue() instanceof Lowered.Adapter a)) continue;
            byte[] bytes = remapped.get(a.owner());
            if (bytes == null) continue;   // reported while loading
            String want = Desc.mapClasses(a.desc(), c -> types.getOrDefault(c, c));
            List<String> found = new ArrayList<>();
            boolean[] isStatic = new boolean[1];
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (name.equals(a.name())) {
                        found.add(descriptor);
                        if (descriptor.equals(want)) isStatic[0] = (access & Opcodes.ACC_STATIC) != 0;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            String label = label(e.getKey());
            String ref = setOf(a.owner()) + ":" + simple(a.owner()) + "#" + a.name();
            if (found.isEmpty()) {
                errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, ref, null,
                        "the adapter class " + a.owner() + " has no method " + a.name(),
                        "add a public static method " + a.name() + want + " (runtime names) to the adapter, or fix the rule of " + label));
            } else if (!found.contains(want)) {
                errors.add(new Diagnostic(ErrorCode.SHAPE, t.id(), label, ref, null,
                        "the adapter method " + a.name() + " has the descriptor " + String.join(", ", found) + " on " + t.id()
                                + "; the API member needs " + want + " (API descriptor " + a.desc() + ")",
                        "give the adapter method the API member's parameters, receiver first, with alias types as their Minecraft classes (binding-format.md example 6)"));
            } else if (!isStatic[0]) {
                errors.add(new Diagnostic(ErrorCode.SHAPE, t.id(), label, ref, null,
                        "the adapter method " + a.name() + want + " is not static; the call site is INVOKESTATIC",
                        "make " + simple(a.owner()) + "." + a.name() + " public static"));
            }
        }
    }

    // ---------------------------------------------------------------------------------------- annotations

    /** Drops invisible (class-retention) annotations: compile-time data that may name compile-only classes such as {@code @Stubs}. */
    private static final class DropInvisibleAnnotations extends ClassVisitor {
        DropInvisibleAnnotations(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return visible ? super.visitAnnotation(descriptor, true) : null;
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            return visible ? super.visitTypeAnnotation(typeRef, typePath, descriptor, true) : null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
            return fv == null ? null : new FieldVisitor(Opcodes.ASM9, fv) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return visible ? super.visitAnnotation(d, true) : null;
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String d, boolean visible) {
                    return visible ? super.visitTypeAnnotation(typeRef, typePath, d, true) : null;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return visible ? super.visitAnnotation(d, true) : null;
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String d, boolean visible) {
                    return visible ? super.visitTypeAnnotation(typeRef, typePath, d, true) : null;
                }

                @Override
                public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
                    if (visible) super.visitAnnotableParameterCount(parameterCount, true);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                    return visible ? super.visitParameterAnnotation(parameter, d, true) : null;
                }

                @Override
                public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String d, boolean visible) {
                    return visible ? super.visitInsnAnnotation(typeRef, typePath, d, true) : null;
                }

                @Override
                public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String d, boolean visible) {
                    return visible ? super.visitTryCatchAnnotation(typeRef, typePath, d, true) : null;
                }

                @Override
                public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String d, boolean visible) {
                    return visible ? super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, d, true) : null;
                }
            };
        }
    }
}
