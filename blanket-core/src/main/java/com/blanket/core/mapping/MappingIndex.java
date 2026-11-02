// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of one mapping file that a resolution round asked for. Every class row is kept (class maps are small);
 * member rows are kept only when one of their names was wanted. Names are canonical namespace names (see
 * {@link MappingFile}).
 */
public final class MappingIndex {

    /** One class in every namespace of the file (index parallel to {@link #namespaces}). */
    public static final class ClassRow {
        final String[] names;
        ClassRow(String[] names) { this.names = names; }
    }

    /** One field or method row. Descriptors are filled in once the whole file has been read. */
    public static final class MemberRow {
        final boolean method;
        final ClassRow owner;
        final String[] names;
        final String[] descs;
        MemberRow(boolean method, ClassRow owner, String[] names, String[] descs) {
            this.method = method;
            this.owner = owner;
            this.names = names;
            this.descs = descs;
        }
        public boolean method() { return method; }
        public String name(int ns) { return names[ns]; }
        public String desc(int ns) { return descs[ns]; }
        public String owner(int ns) { return owner.names[ns]; }
    }

    private final List<String> namespaces;
    private final List<ClassRow> classes = new ArrayList<>();
    private final List<Map<String, ClassRow>> classByName = new ArrayList<>();
    private final Map<String, List<MemberRow>> members = new HashMap<>();

    MappingIndex(List<String> namespaces) {
        this.namespaces = namespaces;
        for (int i = 0; i < namespaces.size(); i++) classByName.add(new HashMap<>());
    }

    public List<String> namespaces() { return namespaces; }

    public int ns(String canonical) {
        int i = namespaces.indexOf(canonical);
        if (i < 0) throw new IllegalArgumentException("namespace " + canonical + " is not in " + namespaces);
        return i;
    }

    void addClass(ClassRow row) {
        classes.add(row);
        for (int i = 0; i < row.names.length; i++) {
            if (row.names[i] != null) classByName.get(i).put(row.names[i], row);
        }
    }

    static String key(int ns, String owner, String name) {
        return ns + "\u0000" + owner + "\u0000" + name;
    }

    void addMember(MemberRow row) {
        for (int i = 0; i < row.names.length; i++) {
            if (row.names[i] == null || row.owner.names[i] == null) continue;
            members.computeIfAbsent(key(i, row.owner.names[i], row.names[i]), k -> new ArrayList<>()).add(row);
        }
    }

    /** The name of a class in another namespace, or null when the file has no row for it. */
    public String mapClass(String fromNs, String name, String toNs) {
        ClassRow row = classByName.get(ns(fromNs)).get(name);
        return row == null ? null : row.names[ns(toNs)];
    }

    public boolean hasClass(String ns, String name) {
        return classByName.get(ns(ns)).containsKey(name);
    }

    /** A member row keyed by its name and descriptor in {@code fromNs}, or null. */
    public MemberRow member(String fromNs, String owner, String name, String desc) {
        int ns = ns(fromNs);
        List<MemberRow> rows = members.get(key(ns, owner, name));
        if (rows == null) return null;
        for (MemberRow r : rows) {
            if (desc.equals(r.descs[ns])) return r;
        }
        return null;
    }

    /** Whether a row for this owner and name was loaded (used to tell "absent from file" from "wrong descriptor"). */
    public List<MemberRow> membersNamed(String fromNs, String owner, String name) {
        List<MemberRow> rows = members.get(key(ns(fromNs), owner, name));
        return rows == null ? List.of() : rows;
    }

    /** Translates every class in a descriptor from {@code fromNs} to {@code toNs}; unknown classes stay as they are. */
    public String translateDesc(String fromNs, String desc, String toNs) {
        return Desc.mapClasses(desc, c -> {
            String m = mapClass(fromNs, c, toNs);
            return m == null ? c : m;
        });
    }

    /** Fills the descriptors of every member row in every namespace; called once by {@link MappingFile}. */
    void finishDescs(int srcNs, Map<MemberRow, String> srcDescs) {
        for (Map.Entry<MemberRow, String> e : srcDescs.entrySet()) {
            MemberRow row = e.getKey();
            for (int i = 0; i < namespaces.size(); i++) {
                row.descs[i] = i == srcNs ? e.getValue() : translateDescIndexed(srcNs, e.getValue(), i);
            }
        }
    }

    private String translateDescIndexed(int from, String desc, int to) {
        return Desc.mapClasses(desc, c -> {
            ClassRow row = classByName.get(from).get(c);
            return row == null || row.names[to] == null ? c : row.names[to];
        });
    }

    public int classCount() { return classes.size(); }
    public int memberCount() { return members.size(); }
}
