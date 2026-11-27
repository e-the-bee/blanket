// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.resolve.ClassIndex;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/**
 * The class index of each target's loader artifact (the {@link ClassIndex} the validator builds for C7), opened on first
 * use. It answers whether a {@code loader:} class or member exists and suggests near names when it does not.
 */
final class LoaderIndexes {
    private static final int MAX_CANDIDATES = 8;

    /** The step of a failure when the target has no loader artifact (as in {@code validateBindings}). */
    static final String NO_JAR_STEP = "loader-jar";
    /** The step of a failure when the artifact does not hold the class or member. */
    static final String MISSING_STEP = "jar";

    private record Open(List<Path> jars, ClassIndex index) {}

    private final LoaderJars source;
    private final Map<String, Open> open = new HashMap<>();

    LoaderIndexes(LoaderJars source) {
        this.source = source;
    }

    /** The jars of {@code t}, empty when it has no loader artifact. */
    private Open of(TargetSpec t) throws IOException {
        Open o = open.get(t.id());
        if (o == null) {
            List<Path> jars = source.jars(t);
            o = new Open(jars, jars.isEmpty() ? null : ClassIndex.open(jars, true));
            open.put(t.id(), o);
        }
        return o;
    }

    boolean hasJar(TargetSpec t) {
        try {
            return !of(t).jars().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    static String noJarMessage(TargetSpec t) {
        return t.id() + " has no loader artifact in loader-artifacts.json, so a loader: reference cannot be looked up on it";
    }

    /** The row for a resolved loader reference on {@code t}: found in the loader artifact, or why not. */
    Lookup.Row check(TargetSpec t, Resolver.Resolved r) {
        Open o;
        try {
            o = of(t);
        } catch (IOException | RuntimeException e) {
            return Lookup.Row.fail(t.id(), NO_JAR_STEP, e.getMessage(), List.of());
        }
        if (o.jars().isEmpty()) return Lookup.Row.fail(t.id(), NO_JAR_STEP, noJarMessage(t), List.of());
        Optional<ClassIndex.Info> info = o.index().get(r.owner());
        if (info.isEmpty()) {
            return Lookup.Row.fail(t.id(), MISSING_STEP, "class " + r.owner() + " is not in the loader artifact of " + t.id(),
                    classCandidates(o.jars(), r.owner()));
        }
        if (r.name() == null) return Lookup.Row.ok(t.id(), r);
        boolean present = r.field() ? info.get().fields().containsKey(r.name() + ":" + r.desc())
                : info.get().methods().containsKey(r.name() + r.desc());
        if (present) return Lookup.Row.ok(t.id(), r);
        return Lookup.Row.fail(t.id(), MISSING_STEP, (r.field() ? "field " : "method ") + r.owner() + "." + r.name()
                + (r.field() ? ":" : "") + r.desc() + " is not in the loader artifact of " + t.id(), memberCandidates(info.get(), r));
    }

    /** The failure row of a bare member name that no overload of {@code owner} has on {@code t}. */
    Lookup.Row noMember(TargetSpec t, String owner, String name) {
        if (!hasJar(t)) return Lookup.Row.fail(t.id(), NO_JAR_STEP, noJarMessage(t), List.of());
        Lookup.Row cls = check(t, new Resolver.Resolved(owner, owner, null, null, false));
        if (!cls.ok()) return cls;
        return Lookup.Row.fail(t.id(), MISSING_STEP, owner + " has no member named " + name + " in the loader artifact of " + t.id(), List.of());
    }

    /**
     * The members called {@code name} on {@code owner} in the loader artifacts of {@code targets}, as descriptors for a
     * reference text: {@code (I)V} for a method, {@code :I} for a field. Empty when no target holds the class.
     */
    List<String> overloads(List<TargetSpec> targets, String owner, String name) {
        TreeSet<String> out = new TreeSet<>();
        for (TargetSpec t : targets) {
            Open o;
            try {
                o = of(t);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (o.jars().isEmpty()) continue;
            Optional<ClassIndex.Info> info = o.index().get(owner);
            if (info.isEmpty()) continue;
            for (String m : info.get().methods().keySet()) if (m.startsWith(name + "(")) out.add(m.substring(name.length()));
            for (String f : info.get().fields().keySet()) if (f.startsWith(name + ":")) out.add(f.substring(name.length()));
        }
        return new ArrayList<>(out);
    }

    void close() {
        for (Open o : open.values()) {
            if (o.index() == null) continue;
            try {
                o.index().close();
            } catch (IOException e) {
                // nothing to recover: the lookup is over
            }
        }
        open.clear();
    }

    // ---------------------------------------------------------------------------------------- candidates

    /** Classes of the jars whose simple name is within two edits of the wanted one. */
    private static List<String> classCandidates(List<Path> jars, String owner) {
        String wanted = simple(owner).toLowerCase();
        TreeSet<String> near = new TreeSet<>();
        for (Path jar : jars) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                z.stream().forEach(e -> {
                    String n = e.getName();
                    if (!n.endsWith(".class") || n.startsWith("META-INF/")) return;
                    String cls = n.substring(0, n.length() - 6);
                    String s = simple(cls).toLowerCase();
                    if (s.equals(wanted) || distance(s, wanted) <= 2) near.add(cls.replace('/', '.'));
                });
            } catch (IOException e) {
                // a jar that cannot be read gives no suggestions
            }
        }
        return limit(near);
    }

    private static List<String> memberCandidates(ClassIndex.Info info, Resolver.Resolved r) {
        TreeSet<String> near = new TreeSet<>();
        String wanted = r.name().toLowerCase();
        if (r.field()) {
            for (String f : info.fields().keySet()) {
                String n = f.substring(0, f.indexOf(':'));
                if (n.toLowerCase().equals(wanted) || distance(n.toLowerCase(), wanted) <= 2) near.add(r.owner() + "#" + f);
            }
        } else {
            for (String m : info.methods().keySet()) {
                String n = m.substring(0, m.indexOf('('));
                if (n.toLowerCase().equals(wanted) || distance(n.toLowerCase(), wanted) <= 2) near.add(r.owner() + "#" + m);
            }
        }
        return limit(near);
    }

    private static List<String> limit(TreeSet<String> set) {
        List<String> all = new ArrayList<>(set);
        return all.size() > MAX_CANDIDATES ? all.subList(0, MAX_CANDIDATES) : all;
    }

    private static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private static int distance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) return 3;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
