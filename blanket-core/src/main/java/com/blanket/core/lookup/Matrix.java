// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.Targets;
import com.blanket.core.bindings.Version;
import com.blanket.core.resolve.TargetSpec;

import java.util.ArrayList;
import java.util.List;

/** Turns the {@code supported} ids of targets.json ({@code loader:mc}) into the {@link TargetSpec}s the resolver takes. */
public final class Matrix {
    private Matrix() {}

    /** The specs of every supported target, in the order of {@code supported}. */
    public static List<TargetSpec> supported(Targets targets) {
        List<TargetSpec> out = new ArrayList<>();
        for (String id : targets.supported()) out.add(spec(targets, id));
        return out;
    }

    /**
     * One target: the first era of its loader whose selector matches gives the runtime namespace and emitter
     * (binding-format.md section 3); the Java level comes from the {@code versions} row.
     *
     * @throws IllegalArgumentException when the id is malformed, no era matches, or the version has no row
     */
    public static TargetSpec spec(Targets targets, String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            throw new IllegalArgumentException("target '" + id + "' is not loader:mc");
        }
        String loader = id.substring(0, colon);
        String mc = id.substring(colon + 1);
        Version v = Version.parse(mc);
        Targets.Era era = null;
        for (Targets.Era e : targets.eras(loader)) {
            if (e.when().matches(loader, v)) {
                era = e;
                break;
            }
        }
        if (era == null) {
            throw new IllegalArgumentException("targets.json has no era of loader '" + loader + "' that matches " + mc);
        }
        Targets.VersionRow row = targets.versions().get(mc);
        if (row == null) {
            throw new IllegalArgumentException("targets.json has no versions row for " + mc);
        }
        return new TargetSpec(loader, mc, era.runtime(), era.emitter(), row.java(), mcpVersion(targets, mc));
    }

    /** The MCPConfig version of a release ({@code de.oceanlabs.mcp:mcp_config:<v>} gives {@code <v>}), or null. */
    public static String mcpVersion(Targets targets, String mc) {
        Targets.VersionRow row = targets.versions().get(mc);
        Object coordinate = row == null ? null : row.mapping("srg");
        if (!(coordinate instanceof String s)) return null;
        return s.substring(s.lastIndexOf(':') + 1);
    }
}
