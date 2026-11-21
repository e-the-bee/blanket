// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Suggestions for a reference that did not resolve (contributing.md section 2): classes with the same simple name,
 * members of the same owner with the same name and another descriptor, and members with the same descriptor and
 * another name. A whole mapping file is read into memory, so this runs only after a failure.
 */
final class Candidates {
    private static final int LIMIT = 8;

    private Candidates() {}

    /** The namespaces of a resolver file key in file order (mirrors the resolver's own table). */
    static String[] canonical(String fileKey) {
        String kind = fileKey.substring(0, fileKey.indexOf(':'));
        switch (kind) {
            case "pg":
                return new String[] {"named", "obf"};
            case "int":
            case "lfint":
                return new String[] {"obf", "id"};
            case "tsrg":
                return new String[] {"obf", "srg", "id"};
            default:
                throw new IllegalArgumentException(fileKey);
        }
    }

    private static int nsId(String[] canonical, String ns) {
        for (int i = 0; i < canonical.length; i++) {
            if (canonical[i].equals(ns)) return i == 0 ? MappingTree.SRC_NAMESPACE_ID : i - 1;
        }
        throw new IllegalArgumentException("namespace " + ns + " is not in the file");
    }

    private static MemoryMappingTree read(Path file) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(file, tree);
        return tree;
    }

    /** Names of the classes whose last path segment is {@code simple} (compared without case when nothing matches exactly). */
    static List<String> classesNamed(Path file, String[] canonical, String ns, String simple) throws IOException {
        MemoryMappingTree tree = read(file);
        int id = nsId(canonical, ns);
        List<String> exact = new ArrayList<>();
        List<String> loose = new ArrayList<>();
        String tail = "/" + simple;
        String lower = simple.toLowerCase(Locale.ROOT);
        for (MappingTree.ClassMapping c : tree.getClasses()) {
            String n = c.getName(id);
            if (n == null) continue;
            if (n.equals(simple) || n.endsWith(tail)) {
                exact.add(n);
            } else if (n.toLowerCase(Locale.ROOT).endsWith(lower) || n.toLowerCase(Locale.ROOT).contains(lower)) {
                loose.add(n);
            }
        }
        List<String> out = exact.isEmpty() ? loose : exact;
        out.sort(null);
        return out.size() > LIMIT * 4 ? out.subList(0, LIMIT * 4) : out;
    }

    /** Every member name of {@code owner} in namespace {@code ns} that contains {@code part} (case-insensitive). */
    static List<String> membersContaining(Path file, String[] canonical, String ns, String owner, String part) throws IOException {
        MemoryMappingTree tree = read(file);
        int id = nsId(canonical, ns);
        MappingTree.ClassMapping c = tree.getClass(owner, id);
        List<String> out = new ArrayList<>();
        if (c == null) return out;
        String lower = part.toLowerCase(Locale.ROOT);
        for (MappingTree.MethodMapping m : c.getMethods()) {
            String n = m.getName(id);
            if (n != null && n.toLowerCase(Locale.ROOT).contains(lower)) out.add(owner + "#" + n + nullToEmpty(m.getDesc(id)));
        }
        for (MappingTree.FieldMapping f : c.getFields()) {
            String n = f.getName(id);
            if (n != null && n.toLowerCase(Locale.ROOT).contains(lower)) out.add(owner + "#" + n + ":" + nullToEmpty(f.getDesc(id)));
        }
        out.sort(null);
        return out.size() > LIMIT ? out.subList(0, LIMIT) : out;
    }

    /**
     * Members of {@code owner} that are near the wanted one: the same name with another descriptor first, then the same
     * descriptor with another name. Empty when the owner itself is not in the file.
     */
    static List<String> membersLike(Path file, String[] canonical, String ns, String owner, String name, String desc, boolean field)
            throws IOException {
        MemoryMappingTree tree = read(file);
        int id = nsId(canonical, ns);
        MappingTree.ClassMapping c = tree.getClass(owner, id);
        List<String> sameName = new ArrayList<>();
        List<String> sameDesc = new ArrayList<>();
        if (c == null) return sameName;
        if (!field) {
            for (MappingTree.MethodMapping m : c.getMethods()) {
                String n = m.getName(id);
                String d = m.getDesc(id);
                if (n == null || d == null) continue;
                if (n.equals(name) && !d.equals(desc)) sameName.add(owner + "#" + n + d);
                else if (d.equals(desc) && !n.equals(name)) sameDesc.add(owner + "#" + n + d);
            }
        } else {
            for (MappingTree.FieldMapping f : c.getFields()) {
                String n = f.getName(id);
                String d = f.getDesc(id);
                if (n == null) continue;
                if (n.equals(name) && d != null && !d.equals(desc)) sameName.add(owner + "#" + n + ":" + d);
                else if (d != null && d.equals(desc) && !n.equals(name)) sameDesc.add(owner + "#" + n + ":" + d);
            }
        }
        sameName.sort(null);
        sameDesc.sort(null);
        List<String> out = new ArrayList<>();
        for (String s : sameName) {
            if (out.size() < LIMIT) out.add("same name, other descriptor: " + s);
        }
        for (String s : sameDesc) {
            if (out.size() < LIMIT) out.add("same descriptor, other name: " + s);
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
