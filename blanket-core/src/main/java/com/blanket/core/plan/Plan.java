// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.resolve.TargetSpec;

import java.util.List;
import java.util.Map;

/**
 * The RESOLVE result for one target (architecture.md section 3): only runtime names and ready instructions. blanket-emit
 * builds the target's jar from this alone and never reads binding files.
 *
 * @param target  the target
 * @param era     the loader-era id of loader-emitters.md section 1 ({@code fabric-int}, {@code forge-srgmoj}, ...)
 * @param entry   internal name of the mod's {@code ClientMod} class; the generated classes go under its package plus
 *                {@code /blanket/}
 * @param glImpl  internal name of the {@code com/blanket/runtime/Gl} implementation to embed
 *                ({@code com/blanket/runtime/Lwjgl3Gl}); the other implementations are left out
 * @param core    whether the GL backend uses the core profile path (also bound as glue)
 * @param types   aliased API type (internal name) to runtime class (internal name)
 * @param members API member key {@code owner.name+desc} (API internal names, e.g.
 *                {@code com/blanket/api/Client.get()Lcom/blanket/api/Client;}) for methods, or
 *                {@code owner.name:desc} for alias enum constants, to its lowering
 * @param features Feature constant to whether it is available on this target
 * @param classVersionMax highest class-file major version the target's Java accepts (52, 60, 61, 65, 69)
 * @param adapters adapter closure: game names already runtime, classes still {@code com/blanket/adapters/<set>/...}
 * @param glue    runtime glue stub bodies
 * @param hooks   one per used event and hook point
 */
public record Plan(TargetSpec target, String era, String entry, String glImpl, boolean core, Map<String, String> types,
                   Map<String, Lowered> members, Map<String, Boolean> features, int classVersionMax,
                   Map<String, byte[]> adapters, List<Glue> glue, List<Hook> hooks) {}
