// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.lower.Hierarchy;
import com.blanket.core.mapping.MappingFile;
import com.blanket.core.mapping.MappingIndex;
import com.blanket.core.resolve.ClassIndex;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.TargetData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The class hierarchy of one target's game, its libraries and the JDK, asked and answered in runtime names
 * (lowering-and-adapters.md section 6). The jars are in the target's jar names (obfuscated before 26.x), so a class is
 * translated runtime to jar name, read from the client jar, the loader jar, the JDK or (lazily) the library jar the
 * version json lists for its package, and its supertypes are translated back.
 *
 * <p>Translation starts from the class pairs the plan's resolution already produced; any other class goes through the
 * class rows of the one mapping file between the jar and the runtime namespace (the resolver's last step,
 * binding-format.md section 2.4), read once on first need without member rows. A class without a row keeps its name
 * (JDK, libraries, unobfuscated game classes). Mod, embedded and adapter classes are not known here; the lower stage
 * layers them on top.
 */
public final class TargetHierarchy implements Hierarchy {

    private final TargetSpec target;
    private final TargetData data;
    private final ClassIndex base;
    private final Map<String, String> runtimeToJar;
    private final Map<String, String> jarToRuntime = new HashMap<>();
    /** The jar-to-runtime step, or null when the runtime names are the jar names (obf, official). */
    private final Resolver.Hop hop;
    private MappingIndex classRows;
    private final Map<Path, ClassIndex> libraries = new LinkedHashMap<>();
    private final Set<String> askedLibraries = new HashSet<>();
    private final Map<String, Optional<Facts>> facts = new HashMap<>();

    private TargetHierarchy(TargetSpec target, TargetData data, ClassIndex base, Map<String, String> runtimeToJar, Resolver.Hop hop) {
        this.target = target;
        this.data = data;
        this.base = base;
        this.runtimeToJar = Map.copyOf(runtimeToJar);
        for (Map.Entry<String, String> e : runtimeToJar.entrySet()) jarToRuntime.putIfAbsent(e.getValue(), e.getKey());
        this.hop = hop;
    }

    /**
     * Opens the hierarchy over {@code data.classpath(target)} and the JDK; the caller closes it.
     *
     * @param runtimeToJar class pairs already known (runtime name to jar name), for example from the plan's resolution
     */
    public static TargetHierarchy open(TargetSpec target, TargetData data, Map<String, String> runtimeToJar) throws IOException {
        // The resolver's own route from obfuscated names to the runtime namespace, for a class: one hop or none.
        Ref probe = new Ref("", "obf", null, "", null, null, false);
        List<Resolver.Hop> hops = new Resolver(target, null, data.mappingFiles(), null).route(probe);
        if (hops.size() > 1) throw new IllegalStateException("more than one class step from jar to runtime names on " + target.id() + ": " + hops);
        return new TargetHierarchy(target, data, ClassIndex.open(data.classpath(target), true), runtimeToJar, hops.isEmpty() ? null : hops.get(0));
    }

    @Override
    public Optional<Facts> get(String runtimeName) {
        Optional<Facts> f = facts.get(runtimeName);
        if (f == null) {
            String jar = toJar(runtimeName);
            f = jar == null ? Optional.empty()
                    : jarInfo(jar).map(i -> new Facts(toRuntime(i.superName()), i.interfaces().stream().map(this::toRuntime).toList(), i.isInterface()));
            facts.put(runtimeName, f);
        }
        return f;
    }

    /**
     * The jar name of a runtime class name; itself when the mapping has no row for it; null when it is the jar name
     * of a class whose runtime name differs (an obfuscated name asked as a runtime name).
     */
    String toJar(String runtimeName) {
        String known = runtimeToJar.get(runtimeName);
        if (known != null) return known;
        if (hop == null || isJdk(runtimeName)) return runtimeName;
        String jar = classRows().mapClass(hop.to(), runtimeName, hop.from());
        if (jar != null) return jar;
        String renamed = classRows().mapClass(hop.from(), runtimeName, hop.to());
        return renamed == null || renamed.equals(runtimeName) ? runtimeName : null;
    }

    /** The runtime name of a jar class name (null stays null); itself when the mapping has no row for it. */
    String toRuntime(String jarName) {
        if (jarName == null) return null;
        String known = jarToRuntime.get(jarName);
        if (known != null) return known;
        if (hop == null || isJdk(jarName)) return jarName;
        String runtime = classRows().mapClass(hop.from(), jarName, hop.to());
        return runtime != null ? runtime : jarName;
    }

    /** A class by jar name from the client jar, the loader jar, the JDK, or the library jar of its package. */
    Optional<ClassIndex.Info> jarInfo(String jarName) {
        Optional<ClassIndex.Info> found = base.get(jarName);
        if (found.isPresent() || isJdk(jarName)) return found;
        if (askedLibraries.add(jarName)) {
            try {
                for (Path p : data.libraryJars(target, Set.of(jarName))) {
                    if (!libraries.containsKey(p)) libraries.put(p, ClassIndex.open(List.of(p), false));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot obtain the library jar of " + jarName + " for " + target.id() + ": " + e.getMessage(), e);
            }
        }
        for (ClassIndex lib : libraries.values()) {
            found = lib.get(jarName);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /**
     * Whether a member ({@code name+desc}, or {@code name:desc} for a field) is declared in the jar class
     * {@code jarOwner}, or, when {@code inherited}, in one of its supertypes.
     */
    boolean jarDeclares(String jarOwner, String member, boolean field, boolean inherited) {
        List<String> todo = new ArrayList<>(List.of(jarOwner));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < todo.size(); i++) {
            String c = todo.get(i);
            if (c == null || !seen.add(c)) continue;
            Optional<ClassIndex.Info> info = jarInfo(c);
            if (info.isEmpty()) continue;
            if ((field ? info.get().fields() : info.get().methods()).containsKey(member)) return true;
            if (!inherited) return false;
            todo.add(info.get().superName());
            todo.addAll(info.get().interfaces());
        }
        return false;
    }

    private MappingIndex classRows() {
        if (classRows == null) {
            try {
                Path p = data.mappingFiles().path(hop.file());
                // Namespaces in file order, as the resolver opens the same files.
                MappingFile file = switch (hop.file().substring(0, hop.file().indexOf(':'))) {
                    case "pg" -> MappingFile.open(p, "named", "obf");
                    case "int", "lfint" -> MappingFile.open(p, "obf", "id");
                    case "tsrg" -> MappingFile.open(p, "obf", "srg", "id");
                    default -> throw new IllegalArgumentException(hop.file());
                };
                classRows = file.read(Set.of());   // every class row, no member row
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read mapping file " + hop.file() + " for " + target.id() + ": " + e.getMessage(), e);
            }
        }
        return classRows;
    }

    private static boolean isJdk(String name) {
        return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/");
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (ClassIndex c : libraries.values()) {
            try {
                c.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        base.close();
        if (first != null) throw first;
    }
}
