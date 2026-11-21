// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.cli;

import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.Selector;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.bindings.Version;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@code blanket explain <ApiType.member> --target <t>}: the rule chosen for the member, and for every reference of
 * its lowering each hop of the route (namespace to namespace, and the mapping file that does it) and the runtime name,
 * or the diagnostic that stopped it. The route is {@link Resolver#route}; file keys: {@code pg} Mojang ProGuard,
 * {@code int} Fabric intermediary, {@code lfint} Legacy Fabric intermediary, {@code tsrg} MCPConfig joined.tsrg.
 */
final class Explain {
    /** The text lines to print and the diagnostics (errors) found; the member is explained when there are none. */
    record Result(List<String> lines, List<Diagnostic> diagnostics) {}

    private final BindingSet bindings;
    private final Resolver.MappingFiles files;

    Explain(BindingSet bindings, Resolver.MappingFiles files) {
        this.bindings = bindings;
        this.files = files;
    }

    /** {@code member} is {@code Type.key} with a simple or full API type name; the key is a member key of the type file. */
    Result explain(String member, TargetSpec t) {
        int paren = member.indexOf('(');
        String head = paren < 0 ? member : member.substring(0, paren);
        int dot = head.lastIndexOf('.');
        if (dot <= 0 || dot == head.length() - 1) {
            throw new CliException("member '" + member + "' is not <ApiType>.<member>, e.g. Client.get");
        }
        String typeName = member.substring(0, dot);
        String key = member.substring(dot + 1);
        TypeBinding tb = typeByName(typeName);
        if (tb == null) {
            throw new CliException("no type file for API type '" + typeName + "' in the bindings");
        }
        List<String> keys = new ArrayList<>();
        if (tb.members().containsKey(key)) {
            keys.add(key);
        } else {
            for (String k : tb.members().keySet()) {
                if (k.startsWith(key + "(")) keys.add(k);
            }
        }
        List<String> lines = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        String label = typeName + "." + key;
        if (keys.isEmpty()) {
            diagnostics.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                    "no rule list for " + key + " in " + tb.file(),
                    "add a \"" + key + "\" rule list to " + tb.file() + " with a rule whose \"when\" matches " + t.id()));
            return new Result(lines, diagnostics);
        }
        int ri = first(tb.representation(), TypeBinding.Representation::when, t);
        for (String k : keys) {
            String keyLabel = simple(tb.api()) + "." + k;
            lines.add(keyLabel + " on " + t.id() + " (" + t.loader() + " " + t.mc() + ", runtime namespace " + t.runtime() + ")");
            lines.add("  type " + tb.api() + " (" + tb.file() + ")");
            if (ri < 0) {
                diagnostics.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), keyLabel, null, null,
                        "no representation rule of " + tb.api() + " matches " + t.id(),
                        "add a representation rule whose \"when\" matches " + t.id() + " to " + tb.file()));
                continue;
            }
            TypeBinding.Representation rep = tb.representation().get(ri);
            lines.add("  representation: " + (rep.alias() != null ? "alias " + rep.alias() : "owned " + rep.owned()) + " when " + rep.when());
            List<TypeBinding.MemberRule> rules = tb.members().get(k);
            int i = first(rules, TypeBinding.MemberRule::when, t);
            if (i < 0) {
                diagnostics.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), keyLabel, null, null,
                        "no rule of " + k + " in " + tb.file() + " matches " + t.id(),
                        "add a rule whose \"when\" matches " + t.id() + " to " + tb.file() + " $.members." + k));
                continue;
            }
            TypeBinding.MemberRule rule = rules.get(i);
            String jsonPath = "$.members." + k + "[" + i + "]";
            lines.add("  rule: " + jsonPath + " when " + rule.when() + " (first match of " + rules.size() + ")");
            if (rule.requires() != null) lines.add("  requires feature " + rule.requires());
            lines.add("  lower: " + rule.lower());
            String anchor = tb.anchor() != null ? tb.anchor() : bindings.targets().anchor();
            for (String text : refs(rule.lower())) {
                explainRef(Ref.parse(text, tb.file(), jsonPath + ".lower"), anchor, t, keyLabel, lines, diagnostics);
            }
        }
        return new Result(lines, diagnostics);
    }

    private void explainRef(Ref ref, String anchor, TargetSpec t, String label, List<String> lines, List<Diagnostic> diagnostics) {
        Resolver resolver = new Resolver(t, anchor, files, bindings.targets().gameClasses());
        List<Resolver.Problem> problems = new ArrayList<>();
        Resolver.Resolved r = resolver.resolve(List.of(ref), problems).get(ref.text());
        lines.add("  reference " + ref.text());
        List<Resolver.Hop> route = resolver.route(ref);
        if (route.isEmpty()) {
            lines.add("    route: none (used as is)");
        } else {
            lines.add("    route (" + route.size() + (route.size() == 1 ? " hop" : " hops") + ", anchor " + anchor + "):");
            for (Resolver.Hop h : route) {
                lines.add("      " + h.from() + " -> " + h.to() + "  " + h.file());
            }
        }
        if (problems.isEmpty() && r != null) {
            lines.add("    runtime name: " + r.owner() + (r.name() == null ? "" : "." + r.name() + (r.field() ? ":" : "") + r.desc()));
            return;
        }
        for (Resolver.Problem p : problems) {
            lines.add("    failed at step " + p.step() + ": " + p.message());
            diagnostics.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, p.ref(), p.step(), p.message(),
                    "fix the reference " + p.ref() + " in the bindings: check the name with the lookup task for " + t.id()
                            + ", or add an earlier rule for this target"));
        }
    }

    /** The type or glue file of a simple or dotted API type name, or null. */
    private TypeBinding typeByName(String name) {
        for (TypeBinding b : bindings.typesAndGlue().values()) {
            if (b.api().equals(name) || b.api().endsWith("." + name)) return b;
        }
        return null;
    }

    private static <R> int first(List<R> rules, Function<R, Selector> when, TargetSpec t) {
        Version v = Version.parse(t.mc());
        for (int i = 0; i < rules.size(); i++) {
            if (when.apply(rules.get(i)).matches(t.loader(), v)) return i;
        }
        return -1;
    }

    private static String simple(String dotted) {
        return dotted.substring(dotted.lastIndexOf('.') + 1);
    }

    /** The reference texts of a lowering, in order (invoke, get, put, new, cast, and every chain step). */
    private static List<String> refs(Lowering l) {
        List<String> out = new ArrayList<>();
        switch (l.kind()) {
            case INVOKE, GET, PUT, NEW, CAST -> out.add(l.ref());
            case CHAIN -> {
                for (Lowering s : l.steps()) out.addAll(refs(s));
            }
            default -> { }
        }
        return out;
    }
}
