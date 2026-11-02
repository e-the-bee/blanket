// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One mapping file (ProGuard, Tiny v2 or TSRG2) read through mapping-io. Each read streams the whole file once and
 * keeps all class rows and only the member rows named in the wanted set ([mappings-and-versions.md] section 4).
 *
 * <p>Namespaces are given canonical names when the file is opened, by position: mapping-io reports the source
 * namespace first (ProGuard: the Mojang names; Tiny: the first header column), then the destinations.
 */
public final class MappingFile {
    private final Path path;
    private final List<String> namespaces;

    private MappingFile(Path path, List<String> namespaces) {
        this.path = path;
        this.namespaces = namespaces;
    }

    /** Opens a file and names its namespaces; {@code canonical} must list one name per namespace in file order. */
    public static MappingFile open(Path path, String... canonical) throws IOException {
        List<String> raw = MappingReader.getNamespaces(path);
        if (raw.size() != canonical.length) {
            throw new IOException(path + " has namespaces " + raw + ", expected " + List.of(canonical));
        }
        return new MappingFile(path, List.of(canonical));
    }

    public Path path() { return path; }

    public List<String> namespaces() { return namespaces; }

    /** The key a caller puts in a wanted set: namespace (canonical), owner (namespace's name), member name. */
    public String key(String ns, String owner, String name) {
        return MappingIndex.key(namespaces.indexOf(ns), owner, name);
    }

    /** Streams the file. A member row is kept when any of its names is in {@code wanted} (see {@link #key}). */
    public MappingIndex read(Set<String> wanted) throws IOException {
        MappingIndex index = new MappingIndex(namespaces);
        Reader reader = new Reader(index, wanted);
        MappingReader.read(path, reader);
        index.finishDescs(0, reader.srcDescs);
        return index;
    }

    private static final class Reader implements MappingVisitor {
        private final MappingIndex index;
        private final Set<String> wanted;
        final Map<MappingIndex.MemberRow, String> srcDescs = new HashMap<>();
        private MappingIndex.ClassRow currentClass;
        private boolean pendingClass;
        private String[] pendingNames;
        private String pendingDesc;
        private boolean pendingMethod;
        private boolean pendingMember;
        private String mappedSrcNs;

        Reader(MappingIndex index, Set<String> wanted) {
            this.index = index;
            this.wanted = wanted;
        }

        @Override
        public void visitNamespaces(String src, List<String> dst) {
            // The index already holds the canonical names; only the count matters here.
            if (1 + dst.size() != index.namespaces().size()) {
                throw new IllegalStateException("namespace count differs from the opened file: " + src + " " + dst);
            }
            mappedSrcNs = src;
        }

        @Override
        public boolean visitClass(String src) throws IOException {
            finishMember();
            finishClass();
            currentClass = new MappingIndex.ClassRow(new String[index.namespaces().size()]);
            currentClass.names[0] = src;
            pendingClass = true;
            return true;
        }

        @Override
        public boolean visitField(String src, String desc) {
            finishMember();
            startMember(false, src, desc);
            return true;
        }

        @Override
        public boolean visitMethod(String src, String desc) {
            finishMember();
            startMember(true, src, desc);
            return true;
        }

        private void startMember(boolean method, String src, String desc) {
            pendingNames = new String[index.namespaces().size()];
            pendingNames[0] = src;
            pendingDesc = desc;
            pendingMethod = method;
            pendingMember = true;
        }

        @Override
        public boolean visitMethodArg(int a, int b, String c) { return true; }

        @Override
        public boolean visitMethodVar(int a, int b, int c, int d, String e) { return true; }

        @Override
        public void visitDstName(MappedElementKind kind, int namespace, String name) {
            int ns = namespace + 1;
            if (kind == MappedElementKind.CLASS) {
                currentClass.names[ns] = name;
            } else if (pendingMember && (kind == MappedElementKind.FIELD || kind == MappedElementKind.METHOD)) {
                pendingNames[ns] = name;
            }
        }

        @Override
        public void visitComment(MappedElementKind kind, String comment) {}

        @Override
        public boolean visitEnd() {
            finishMember();
            finishClass();
            return true;
        }

        private void finishClass() {
            if (!pendingClass) return;
            pendingClass = false;
            index.addClass(currentClass);
        }

        private void finishMember() {
            if (!pendingMember) return;
            pendingMember = false;
            if (currentClass == null || currentClass.names[0] == null) return;
            // Keep the row if any of its names was asked for; derive the rest from the class map afterwards.
            boolean keep = false;
            for (int i = 0; i < pendingNames.length && !keep; i++) {
                if (pendingNames[i] != null && currentClass.names[i] != null) {
                    keep = wanted.contains(MappingIndex.key(i, currentClass.names[i], pendingNames[i]));
                }
            }
            if (!keep) return;
            String[] descs = new String[pendingNames.length];
            MappingIndex.MemberRow row = new MappingIndex.MemberRow(pendingMethod, currentClass, pendingNames, descs);
            srcDescs.put(row, pendingDesc);
            index.addMember(row);
        }
    }
}
