// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.scan;

import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

/**
 * The used set of one mod jar (architecture.md section 3.1, pseudocode.md section 2): the API members and types the
 * mod's classes reference, and the events they subscribe to. All sets are sorted, so output is stable.
 */
public final class Scan {

    /**
     * One API member reference: the owner (internal name, {@code com/blanket/api/...}), the name and the JVM descriptor
     * as the mod's bytecode wrote them. {@code isStatic} comes from the opcode or method handle kind. API types have
     * no fields (accessors only), so a field reference is kept only to let RESOLVE report it.
     */
    public record ApiMember(String owner, String name, String desc, boolean isStatic, boolean isField) {}

    private static final Comparator<ApiMember> ORDER = Comparator.comparing(ApiMember::owner)
            .thenComparing(ApiMember::name).thenComparing(ApiMember::desc);

    private final Set<ApiMember> members = new TreeSet<>(ORDER);
    private final Set<String> types = new TreeSet<>();
    private final Set<String> events = new TreeSet<>();
    private final Set<String> entries = new TreeSet<>();
    private int classes;

    /** API members referenced by the mod, sorted by owner, name and descriptor. */
    public Set<ApiMember> members() {
        return Collections.unmodifiableSet(members);
    }

    /** API types mentioned anywhere: descriptors, signatures, casts, annotations, {@code ldc}, supertypes. Internal names. */
    public Set<String> types() {
        return Collections.unmodifiableSet(types);
    }

    /** Event constants read from {@code com/blanket/api/Events} ({@code CLIENT_TICK_END}), the events the mod subscribes to. */
    public Set<String> events() {
        return Collections.unmodifiableSet(events);
    }

    /** Mod classes that implement {@code com/blanket/api/ClientMod} directly (internal names): the mod's entry candidates. */
    public Set<String> entries() {
        return Collections.unmodifiableSet(entries);
    }

    /** Number of mod classes read. */
    public int classCount() {
        return classes;
    }

    void addMember(ApiMember m) {
        members.add(m);
    }

    void addType(String internalName) {
        types.add(internalName);
    }

    void addEvent(String name) {
        events.add(name);
    }

    void addEntry(String internalName) {
        entries.add(internalName);
    }

    void classRead() {
        classes++;
    }
}
