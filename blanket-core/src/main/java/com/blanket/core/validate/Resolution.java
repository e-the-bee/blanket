// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.lookup.Routes;
import com.blanket.core.resolve.GameClasses;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The references of one target resolved through {@link Resolver}, keyed by anchor and reference text. A reference that
 * cannot be routed, or whose mapping file cannot be read, becomes a {@link Resolver.Problem} instead of aborting the run.
 */
final class Resolution {
    private final Map<String, Resolver.Resolved> resolved = new HashMap<>();
    private final Map<String, Resolver.Problem> problems = new HashMap<>();

    private Resolution() {}

    static String key(String anchor, String text) {
        return anchor + "\u0000" + text;
    }

    Resolver.Resolved resolved(String anchor, String text) {
        return resolved.get(key(anchor, text));
    }

    Resolver.Problem problem(String anchor, String text) {
        return problems.get(key(anchor, text));
    }

    /** The same target with the runtime namespace replaced by the obfuscated one, whose names are the ones in the client jar. */
    static TargetSpec jarNames(TargetSpec t) {
        return new TargetSpec(t.loader(), t.mc(), "obf", t.emitter(), t.java(), t.mcpVersion());
    }

    /**
     * Resolves {@code refsByAnchor} (anchor to references) on {@code target}. Each anchor is one resolver run, so every
     * mapping file is streamed once per round.
     */
    static Resolution of(TargetSpec target, Map<String, List<Ref>> refsByAnchor, Resolver.MappingFiles files, GameClasses game) {
        Resolution r = new Resolution();
        for (Map.Entry<String, List<Ref>> e : refsByAnchor.entrySet()) {
            String anchor = e.getKey();
            Map<String, Ref> routable = new LinkedHashMap<>();
            for (Ref ref : e.getValue()) {
                String route = Routes.problem(target, ref, game);
                if (route != null) {
                    r.problems.putIfAbsent(key(anchor, ref.text()), new Resolver.Problem("UNRESOLVED", target.id(), ref.text(), Routes.STEP, route));
                } else {
                    routable.putIfAbsent(ref.text(), ref);
                }
            }
            r.resolveAll(target, anchor, new ArrayList<>(routable.values()), files, game);
        }
        return r;
    }

    private void resolveAll(TargetSpec target, String anchor, List<Ref> refs, Resolver.MappingFiles files, GameClasses game) {
        if (refs.isEmpty()) return;
        List<Resolver.Problem> found = new ArrayList<>();
        try {
            Map<String, Resolver.Resolved> out = new Resolver(target, anchor, files, game).resolve(refs, found);
            for (Map.Entry<String, Resolver.Resolved> e : out.entrySet()) resolved.put(key(anchor, e.getKey()), e.getValue());
            for (Resolver.Problem p : found) problems.putIfAbsent(key(anchor, p.ref()), p);
        } catch (IllegalStateException batch) {
            // One unreadable mapping file fails the whole batch: ask again one reference at a time to find the culprits.
            for (Ref ref : refs) {
                List<Resolver.Problem> one = new ArrayList<>();
                try {
                    Map<String, Resolver.Resolved> out = new Resolver(target, anchor, files, game).resolve(List.of(ref), one);
                    for (Map.Entry<String, Resolver.Resolved> e : out.entrySet()) resolved.put(key(anchor, e.getKey()), e.getValue());
                    for (Resolver.Problem p : one) problems.putIfAbsent(key(anchor, p.ref()), p);
                } catch (IllegalStateException single) {
                    problems.putIfAbsent(key(anchor, ref.text()), new Resolver.Problem("UNRESOLVED", target.id(), ref.text(), "mapping file", single.getMessage()));
                }
            }
        }
    }
}
