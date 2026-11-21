// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.bindings.Targets;
import com.blanket.core.mapping.MappingFile;
import com.blanket.core.mapping.MappingIndex;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * {@code lookup}: resolves one reference on every supported target and says, per target, the runtime name or the step
 * where it failed (contributing.md section 2). It is the Java port of M0's {@code tools/names.py}, built on the mapping
 * readers and the {@link Resolver} instead of ad-hoc parsers.
 *
 * <p>The query is {@code [ns:]Class[#member]}. {@code Class} may be a simple name ({@code Entity}) for the Mojang
 * namespace; {@code member} may be a bare name, which lists every overload ({@code names.py}'s
 * {@code Class#member}), or a descriptor ({@code getX()D}, {@code x:D}) as in binding-format.md section 2.3.
 */
public final class Lookup {

    /** One target's answer. {@code owner}, {@code name} and {@code desc} are runtime names when {@code ok}, else null. */
    public record Row(String target, boolean ok, String owner, String name, String desc, boolean field,
                      String step, String message, List<String> candidates) {

        static Row ok(String target, Resolver.Resolved r) {
            return new Row(target, true, r.owner(), r.name(), r.desc(), r.field(), null, null, List.of());
        }

        static Row fail(String target, String step, String message, List<String> candidates) {
            return new Row(target, false, null, null, null, false, step, message, candidates);
        }
    }

    /** The answers for one fully-written reference. */
    public record Result(String ref, List<Row> rows) {}

    private final Targets targets;
    private final Resolver.MappingFiles files;

    public Lookup(Targets targets, Resolver.MappingFiles files) {
        this.targets = targets;
        this.files = files;
    }

    /**
     * Resolves {@code query} on the given targets ({@code loader:mc} ids), or on every supported target when
     * {@code targetIds} is empty. A query can expand to several references (a bare member name with overloads, or a
     * simple class name found in more than one package).
     */
    public List<Result> lookup(String query, Collection<String> targetIds) {
        List<TargetSpec> specs = new ArrayList<>();
        if (targetIds.isEmpty()) {
            specs.addAll(Matrix.supported(targets));
        } else {
            for (String id : targetIds) specs.add(Matrix.spec(targets, id));
        }
        List<Result> out = new ArrayList<>();
        for (Object o : expand(query.trim())) {
            if (o instanceof Result r) {
                out.add(r);
                continue;
            }
            String text = (String) o;
            Ref ref = Ref.parse(text, "lookup", "$");
            List<Row> rows = new ArrayList<>();
            for (TargetSpec t : specs) rows.add(resolve(t, ref));
            out.add(new Result(text, rows));
        }
        return out;
    }

    /** Resolves one parsed reference on one target. */
    public Row resolve(TargetSpec t, Ref ref) {
        String route = Routes.problem(t, ref);
        if (route != null) return Row.fail(t.id(), Routes.STEP, route, List.of());
        List<Resolver.Problem> problems = new ArrayList<>();
        java.util.Map<String, Resolver.Resolved> out;
        try {
            out = new Resolver(t, targets.anchor(), files).resolve(List.of(ref), problems);
        } catch (IllegalStateException e) {
            return Row.fail(t.id(), "mapping file", e.getMessage(), List.of());
        }
        if (!problems.isEmpty()) {
            Resolver.Problem p = problems.get(0);
            return Row.fail(t.id(), p.step(), p.message(), candidatesFor(ref, p));
        }
        return Row.ok(t.id(), out.get(ref.text()));
    }

    // ---------------------------------------------------------------------------------------- query expansion

    /** A list of reference texts to resolve, and ready-made failures ({@link Result}) for parts that cannot be. */
    private List<Object> expand(String query) {
        String prefix = "";
        String rest = query;
        String ns = "mojang";
        String version = null;
        int colon = query.indexOf(':');
        int hash = query.indexOf('#');
        if (colon >= 0 && (hash < 0 || colon < hash)) {
            prefix = query.substring(0, colon + 1);
            String p = query.substring(0, colon);
            int at = p.indexOf('@');
            ns = at >= 0 ? p.substring(0, at) : p;
            version = at >= 0 ? p.substring(at + 1) : null;
            rest = query.substring(colon + 1);
            hash = rest.indexOf('#');
            if (!Ref.NAMESPACES.contains(ns)) {
                throw new IllegalArgumentException("unknown namespace '" + ns + "' (expected one of " + new java.util.TreeSet<>(Ref.NAMESPACES) + ")");
            }
        }
        String cls = (hash < 0 ? rest : rest.substring(0, hash)).replace('.', '/');
        String member = hash < 0 ? null : rest.substring(hash + 1);
        if (cls.isEmpty()) throw new IllegalArgumentException("empty class name in '" + query + "'");

        List<Object> out = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        if (cls.indexOf('/') >= 0 || ns.equals("intermediary") || ns.equals("lf") || ns.equals("loader")) {
            owners.add(cls);
        } else if (ns.equals("mojang")) {
            String fileKey = "pg:" + (version == null ? targets.anchor() : version);
            try {
                Path file = files.path(fileKey);
                for (String c : Candidates.classesNamed(file, Candidates.canonical(fileKey), "named", cls)) {
                    if (c.equals(cls) || c.endsWith("/" + cls)) owners.add(c);
                }
                if (owners.isEmpty()) {
                    List<String> near = Candidates.classesNamed(file, Candidates.canonical(fileKey), "named", cls);
                    out.add(failure(query, fileKey + "|named|obf", "no class named " + cls + " in " + fileKey, near));
                    return out;
                }
            } catch (IOException e) {
                out.add(failure(query, "mapping file", "cannot read " + fileKey + ": " + e.getMessage(), List.of()));
                return out;
            }
        } else {
            out.add(failure(query, "query", "write the full class name for the " + ns + " namespace", List.of()));
            return out;
        }

        for (String owner : owners) {
            if (member == null) {
                out.add(prefix + owner);
            } else if (member.indexOf('(') >= 0 || member.indexOf(':') >= 0) {
                out.add(prefix + owner + "#" + member);
            } else {
                expandName(out, prefix, ns, version, owner, member);
            }
        }
        return out;
    }

    /** A bare member name: every overload of that name on the owner, from the source mapping file. */
    private void expandName(List<Object> out, String prefix, String ns, String version, String owner, String name) {
        String query = prefix + owner + "#" + name;
        if (!ns.equals("mojang")) {
            out.add(failure(query, "query", "write " + name + " with a descriptor (name(args)ret or name:type) for the " + ns + " namespace",
                    List.of()));
            return;
        }
        String fileKey = "pg:" + (version == null ? targets.anchor() : version);
        try {
            Path file = files.path(fileKey);
            MappingFile f = MappingFile.open(file, "named", "obf");
            MappingIndex idx = f.read(Set.of(f.key("named", owner, name)));
            List<MappingIndex.MemberRow> rows = idx.membersNamed("named", owner, name);
            if (rows.isEmpty()) {
                out.add(failure(query, fileKey + "|named|obf", owner + " has no member named " + name + " in " + fileKey,
                        Candidates.membersContaining(file, Candidates.canonical(fileKey), "named", owner, name)));
                return;
            }
            for (MappingIndex.MemberRow r : rows) {
                out.add(prefix + owner + "#" + name + (r.method() ? r.desc(0) : ":" + r.desc(0)));
            }
        } catch (IOException e) {
            out.add(failure(query, "mapping file", "cannot read " + fileKey + ": " + e.getMessage(), List.of()));
        }
    }

    private static Result failure(String ref, String step, String message, List<String> candidates) {
        return new Result(ref, List.of(Row.fail("*", step, message, candidates)));
    }

    // ---------------------------------------------------------------------------------------- candidates

    /** Suggestions when the failing step is the first file of the reference's own namespace. */
    private List<String> candidatesFor(Ref ref, Resolver.Problem p) {
        String[] parts = p.step().split("\\|");
        if (parts.length != 3 || !ownFirstStep(ref, parts[1])) return List.of();
        try {
            Path file = files.path(parts[0]);
            String[] canonical = Candidates.canonical(parts[0]);
            if (ref.isClass()) {
                String simple = ref.owner().substring(ref.owner().lastIndexOf('/') + 1);
                return Candidates.classesNamed(file, canonical, parts[1], simple);
            }
            List<String> out = Candidates.membersLike(file, canonical, parts[1], ref.owner(), ref.name(), ref.desc(), ref.field());
            if (out.isEmpty()) {
                String simple = ref.owner().substring(ref.owner().lastIndexOf('/') + 1);
                out = Candidates.classesNamed(file, canonical, parts[1], simple);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** True when {@code from} is the namespace the reference was written in (its first hop), not one reached later. */
    private static boolean ownFirstStep(Ref ref, String from) {
        switch (ref.ns()) {
            case "mojang":
                return from.equals("named");
            case "intermediary":
            case "lf":
                return from.equals("id");
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------------------------------- output

    /** The table of {@code contributing.md} section 2: one block per reference, one line per target. */
    public static String table(List<Result> results) {
        StringBuilder b = new StringBuilder();
        for (Result r : results) {
            b.append(r.ref()).append('\n');
            for (Row row : r.rows()) {
                b.append("  ").append(pad(row.target(), 22)).append(' ');
                if (row.ok()) {
                    b.append(row.owner());
                    if (row.name() != null) b.append('.').append(row.name()).append(' ').append(row.desc());
                    b.append('\n');
                    continue;
                }
                b.append("FAIL at ").append(row.step()).append(": ").append(row.message()).append('\n');
                for (String c : row.candidates()) b.append("      candidate: ").append(c).append('\n');
            }
        }
        return b.toString();
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    /** Wraps a {@link BindingException} (malformed reference) for callers that print one line. */
    public static String describe(RuntimeException e) {
        return e instanceof BindingException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
