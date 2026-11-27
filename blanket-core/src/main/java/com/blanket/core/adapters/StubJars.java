// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.Ref;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Era stub jars (lowering-and-adapters.md section 5.1): a signature-only copy of one Minecraft version's client jar in
 * the authoring names of an adapter set. Classes are read with {@code SKIP_CODE}, so methods keep no {@code Code};
 * fields, the generic {@code Signature}, {@code InnerClasses}, annotations and the enum flags stay. The classes are
 * remapped through a chain of {@link Table}s, one per mapping file. This is maintainer tooling: the output never ships.
 */
public final class StubJars {
    private StubJars() {}

    /** The authoring names an adapter set is written in. */
    public static final Set<String> AUTHORING = Set.of("mojang", "official", "yarn-legacy");

    /** The runtime namespaces an adapter set ships in (binding-format.md section 5). */
    public static final Set<String> SHIPPING = Set.of("intermediary", "lf", "official");

    private static final Set<String> SET_KEYS = Set.of("schema", "name", "stubs", "authoring", "authoringMappings", "shipping", "covers");

    /**
     * One {@code adapters/<set>/adapter-set.json}. {@code authoringMappings} is the Maven coordinate of the mapping
     * file the authoring names come from ({@code yarn-legacy} only), else null.
     */
    public record AdapterSet(String name, String stubs, String authoring, String authoringMappings, String shipping) {}

    /** Reads one {@code adapter-set.json}; unknown keys and bad values are errors that name the file and the key. */
    public static AdapterSet readSet(Path file) {
        String f = file.toString();
        JsonElement root = BindingFiles.readJson(file);
        if (!root.isJsonObject()) throw new BindingException(f, "$", "expected an object");
        JsonObject o = root.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!SET_KEYS.contains(key)) throw new BindingException(f, "$." + key, "unknown key; allowed: " + new java.util.TreeSet<>(SET_KEYS));
        }
        AdapterSet set = new AdapterSet(text(o, "name", f, true), text(o, "stubs", f, true), text(o, "authoring", f, true),
                text(o, "authoringMappings", f, false), text(o, "shipping", f, true));
        if (!AUTHORING.contains(set.authoring())) {
            throw new BindingException(f, "$.authoring", "'" + set.authoring() + "' is not one of " + new java.util.TreeSet<>(AUTHORING));
        }
        if (!SHIPPING.contains(set.shipping())) {
            throw new BindingException(f, "$.shipping", "'" + set.shipping() + "' is not one of " + new java.util.TreeSet<>(SHIPPING));
        }
        if (set.authoring().equals("yarn-legacy") && set.authoringMappings() == null) {
            throw new BindingException(f, "$.authoringMappings", "required when authoring is yarn-legacy (a Maven coordinate group:artifact:version)");
        }
        return set;
    }

    private static String text(JsonObject o, String key, String file, boolean required) {
        JsonElement e = o.get(key);
        if (e == null) {
            if (required) throw new BindingException(file, "$." + key, "missing");
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isEmpty()) {
            throw new BindingException(file, "$." + key, "expected a non-empty string");
        }
        return e.getAsString();
    }

    /** Every set of {@code <bindings>/adapters/}, sorted by name (a folder without {@code adapter-set.json}, like {@code common}, is skipped). */
    public static List<AdapterSet> readSets(Path bindingsDir) throws IOException {
        Path adapters = bindingsDir.resolve("adapters");
        List<AdapterSet> sets = new ArrayList<>();
        if (!Files.isDirectory(adapters)) return sets;
        try (Stream<Path> dirs = Files.list(adapters)) {
            for (Path dir : dirs.sorted().toList()) {
                Path file = dir.resolve("adapter-set.json");
                if (!Files.isRegularFile(file)) continue;
                AdapterSet set = readSet(file);
                if (!set.name().equals(dir.getFileName().toString())) {
                    throw new BindingException(file.toString(), "$.name", "'" + set.name() + "' differs from the folder name '" + dir.getFileName() + "'");
                }
                sets.add(set);
            }
        }
        return sets;
    }

    /**
     * The mapping chain from the names inside the client jar of {@code set.stubs()} to the set's authoring names.
     * {@code mojang}: the ProGuard file, obf to Mojang. {@code official}: none (the 26.x jar is unobfuscated).
     * {@code yarn-legacy}: LF intermediary (obf to intermediary), then Legacy Yarn (intermediary to named).
     */
    public static List<Table> stages(AdapterSet set, MappingDownloads downloads) throws IOException {
        switch (set.authoring()) {
            case "mojang":
                return List.of(Table.read(downloads.clientMappings(set.stubs()), 1, 0));
            case "official":
                return List.of();
            case "yarn-legacy":
                return List.of(Table.read(downloads.intermediary(set.stubs(), true), 0, 1),
                        Table.read(downloads.legacyYarn(set.authoringMappings()), 0, 1));
            default:
                throw new IllegalArgumentException("unknown authoring names '" + set.authoring() + "'");
        }
    }

    /**
     * The class an alias reference of a type file stands for in the authoring names of {@code set}. The reference must
     * be written in a namespace the set can show: {@code lf} for {@code yarn-legacy} (translated with the last stage),
     * {@code official} for {@code official}, {@code mojang} at the set's stubs version for {@code mojang}.
     *
     * @param anchor the Mojang version a {@code mojang} reference without {@code @version} means
     */
    public static String authoringClass(AdapterSet set, Ref ref, String anchor, List<Table> stages) {
        if (!ref.isClass()) throw new IllegalArgumentException("'" + ref.text() + "' is a member, not a class");
        String want;
        switch (set.authoring()) {
            case "yarn-legacy":
                want = "lf";
                break;
            case "official":
                want = "official";
                break;
            default:
                want = "mojang";
        }
        if (!ref.ns().equals(want)) {
            throw new IllegalArgumentException("'" + ref.text() + "' is written in the " + ref.ns() + " namespace, but the " + set.name()
                    + " adapter set (authoring " + set.authoring() + ") needs " + want + " names");
        }
        if (want.equals("mojang")) {
            String version = ref.version() == null ? anchor : ref.version();
            if (!version.equals(set.stubs())) {
                throw new IllegalArgumentException("'" + ref.text() + "' names Mojang " + version + ", but the " + set.name()
                        + " adapter set's stubs are " + set.stubs() + "; write the alias for the stubs version");
            }
            return ref.owner();
        }
        if (want.equals("lf")) return stages.get(stages.size() - 1).map(ref.owner());
        return ref.owner();
    }

    /**
     * Writes the stub jar: every class of {@code clientJar} (nothing else), without code, remapped through
     * {@code stages} (first stage first). Entries are sorted and carry a fixed time, so equal inputs give equal bytes.
     *
     * @return the number of classes written
     */
    public static int write(Path clientJar, List<Table> stages, Path out) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            List<Table> chain = withHierarchy(zip, stages);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!isClass(e)) continue;
                byte[] stub;
                try (InputStream in = zip.getInputStream(e)) {
                    stub = signatures(in.readAllBytes(), chain);
                } catch (RuntimeException ex) {
                    throw new IOException("cannot read " + name + " of " + clientJar + ": " + ex.getMessage(), ex);
                }
                classes.put(new ClassReader(stub).getClassName() + ".class", stub);
            }
        }
        writeJar(out, classes);
        return classes.size();
    }

    private static boolean isClass(ZipEntry e) {
        String name = e.getName();
        return !e.isDirectory() && name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class");
    }

    /** The stages with the jar's class hierarchy (superclass, then interfaces) in each stage's own input names. */
    private static List<Table> withHierarchy(ZipFile zip, List<Table> stages) throws IOException {
        Map<String, List<String>> hierarchy = new HashMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!isClass(e)) continue;
            try (InputStream in = zip.getInputStream(e)) {
                ClassReader r = new ClassReader(in);
                List<String> supers = new ArrayList<>();
                if (r.getSuperName() != null) supers.add(r.getSuperName());
                supers.addAll(List.of(r.getInterfaces()));
                hierarchy.put(r.getClassName(), supers);
            }
        }
        List<Table> out = new ArrayList<>(stages.size());
        for (Table stage : stages) {
            out.add(stage.withHierarchy(hierarchy));
            hierarchy = stage.mapHierarchy(hierarchy);
        }
        return out;
    }

    /** One class file without code, remapped through {@code stages} (first stage first). */
    static byte[] signatures(byte[] classFile, List<? extends Remapper> stages) {
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor chain = writer;
        for (int i = stages.size() - 1; i >= 0; i--) chain = new ClassRemapper(chain, stages.get(i));
        new ClassReader(classFile).accept(chain, ClassReader.SKIP_CODE);
        return writer.toByteArray();
    }

    /** A zip of {@code entries} in key order with a fixed time, written next to {@code out} and moved into place. */
    static void writeJar(Path out, Map<String, byte[]> entries) throws IOException {
        Path dir = out.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, out.getFileName().toString(), ".part");
        try {
            try (OutputStream file = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(file)) {
                for (Map.Entry<String, byte[]> e : new TreeMap<>(entries).entrySet()) {
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    zip.putNextEntry(entry);
                    zip.write(e.getValue());
                    zip.closeEntry();
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * One mapping file as a {@link Remapper}: classes, and fields and methods by declaring class, name and descriptor,
     * all in the names of the {@code from} namespace and mapped to the {@code to} namespace. A name without a row stays
     * as it is. A table made with {@link #withHierarchy} also finds a method row declared on a supertype of the owner.
     */
    public static final class Table extends Remapper {
        private final Map<String, String> classes;
        private final Map<String, String> fields;
        private final Map<String, String> methods;
        private final Map<String, List<String>> supers;

        Table(Map<String, String> classes, Map<String, String> fields, Map<String, String> methods) {
            this(classes, fields, methods, Map.of());
        }

        private Table(Map<String, String> classes, Map<String, String> fields, Map<String, String> methods, Map<String, List<String>> supers) {
            this.classes = classes;
            this.fields = fields;
            this.methods = methods;
            this.supers = supers;
        }

        /**
         * Reads a ProGuard, Tiny or TSRG2 file. Namespace numbers are positions in the file: 0 is the source
         * namespace (ProGuard: the Mojang names; Tiny: the first column), 1 the first destination, and so on.
         */
        public static Table read(Path file, int from, int to) throws IOException {
            Rows rows = new Rows();
            MappingReader.read(file, rows);
            return rows.table(file, from, to);
        }

        @Override
        public String map(String internalName) {
            String m = classes.get(internalName);
            return m == null ? internalName : m;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String m = fields.get(owner + '.' + name + ':' + descriptor);
            return m == null ? name : m;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String key = name + descriptor;
            String m = methods.get(owner + '.' + key);
            if (m == null && !supers.isEmpty() && !name.startsWith("<")) m = inherited(owner, key, new java.util.HashSet<>());
            return m == null ? name : m;
        }

        /** The row of {@code name+descriptor} on the nearest supertype (superclass first, then interfaces) of {@code owner}. */
        private String inherited(String owner, String key, Set<String> seen) {
            for (String sup : supers.getOrDefault(owner, List.of())) {
                if (!seen.add(sup)) continue;
                String m = methods.get(sup + '.' + key);
                if (m == null) m = inherited(sup, key, seen);
                if (m != null) return m;
            }
            return null;
        }

        /**
         * A copy that also finds a method row declared on a supertype, like a standard remapper's hierarchy lookup.
         * {@code supers} maps a class to its superclass and interfaces, all in this table's {@code from} names.
         */
        public Table withHierarchy(Map<String, List<String>> supers) {
            return new Table(classes, fields, methods, supers);
        }

        /** This table's class map applied to a hierarchy: both sides renamed, so it fits the next stage. */
        public Map<String, List<String>> mapHierarchy(Map<String, List<String>> hierarchy) {
            Map<String, List<String>> out = new HashMap<>();
            for (Map.Entry<String, List<String>> e : hierarchy.entrySet()) {
                List<String> list = new ArrayList<>(e.getValue().size());
                for (String s : e.getValue()) list.add(map(s));
                out.put(map(e.getKey()), list);
            }
            return out;
        }

        /** Whether {@code owner.name+descriptor} has a row, on the class or a supertype. */
        public boolean mapsMethod(String owner, String name, String descriptor) {
            return !mapMethodName(owner, name, descriptor).equals(name);
        }

        public int classCount() {
            return classes.size();
        }
    }

    /** The rows of a mapping file, kept whole: every class and member in every namespace. */
    private static final class Rows implements MappingVisitor {
        private record ClassRow(String[] names) {}

        private record MemberRow(ClassRow owner, boolean method, String[] names, String srcDesc) {}

        private final List<ClassRow> classes = new ArrayList<>();
        private final List<MemberRow> members = new ArrayList<>();
        private int namespaces;
        private ClassRow currentClass;
        private MemberRow currentMember;

        @Override
        public void visitNamespaces(String src, List<String> dst) {
            namespaces = 1 + dst.size();
        }

        @Override
        public boolean visitClass(String src) {
            currentClass = new ClassRow(new String[namespaces]);
            currentClass.names[0] = src;
            currentMember = null;
            classes.add(currentClass);
            return true;
        }

        @Override
        public boolean visitField(String src, String desc) {
            return member(false, src, desc);
        }

        @Override
        public boolean visitMethod(String src, String desc) {
            return member(true, src, desc);
        }

        private boolean member(boolean method, String src, String desc) {
            String[] names = new String[namespaces];
            names[0] = src;
            currentMember = new MemberRow(currentClass, method, names, desc);
            members.add(currentMember);
            return true;
        }

        @Override
        public boolean visitMethodArg(int argPosition, int lvIndex, String srcName) { return false; }

        @Override
        public boolean visitMethodVar(int lvtRowIndex, int lvIndex, int startOpIdx, int endOpIdx, String srcName) { return false; }

        @Override
        public void visitDstName(MappedElementKind kind, int namespace, String name) {
            if (kind == MappedElementKind.CLASS) {
                currentClass.names[namespace + 1] = name;
            } else if (currentMember != null && (kind == MappedElementKind.FIELD || kind == MappedElementKind.METHOD)) {
                currentMember.names[namespace + 1] = name;
            }
        }

        @Override
        public void visitComment(MappedElementKind kind, String comment) {}

        Table table(Path file, int from, int to) throws IOException {
            if (from < 0 || to < 0 || from >= namespaces || to >= namespaces || from == to) {
                throw new IOException(file + " has " + namespaces + " namespaces; cannot map " + from + " to " + to);
            }
            Map<String, String> srcToFrom = new HashMap<>();
            Map<String, String> classMap = new HashMap<>();
            for (ClassRow c : classes) {
                String f = c.names[from];
                if (f == null) continue;
                srcToFrom.put(c.names[0], f);
                classMap.put(f, c.names[to] == null ? f : c.names[to]);
            }
            Map<String, String> fieldMap = new HashMap<>();
            Map<String, String> methodMap = new HashMap<>();
            for (MemberRow m : members) {
                String owner = m.owner.names[from];
                String name = m.names[from];
                String target = m.names[to];
                if (owner == null || name == null || target == null || target.equals(name)) continue;
                String desc = from == 0 ? m.srcDesc : translate(m.srcDesc, srcToFrom);
                if (m.method) methodMap.put(owner + '.' + name + desc, target);
                else fieldMap.put(owner + '.' + name + ':' + desc, target);
            }
            return new Table(Collections.unmodifiableMap(classMap), Collections.unmodifiableMap(fieldMap), Collections.unmodifiableMap(methodMap));
        }

        /** The class names of a descriptor looked up in {@code map}; a name without a row stays. */
        private static String translate(String desc, Map<String, String> map) {
            if (desc.indexOf('L') < 0) return desc;
            StringBuilder out = new StringBuilder(desc.length() + 16);
            int i = 0;
            while (i < desc.length()) {
                char c = desc.charAt(i);
                if (c == 'L') {
                    int end = desc.indexOf(';', i);
                    String name = desc.substring(i + 1, end);
                    out.append('L').append(map.getOrDefault(name, name)).append(';');
                    i = end + 1;
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }
    }
}
