// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.mapping.MappingDownloads;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code remapAdapters} (lowering-and-adapters.md section 5.2): the compiled adapter classes of a set, in authoring
 * names, become shipping names ({@code intermediary}, {@code lf} or {@code official}) with the full mapping of the
 * stub version, and move from {@code com/blanket/adapters/} to {@code com/blanket/adapters/<set>/}.
 *
 * <p>Member names are mapped inheritance-aware. A reference's declaring class is found in the stub jar first (javac
 * writes the static type as the owner, often a subclass), then the mapping is looked up on the classes of the member's
 * override family, nearest first, because a mapping file may hold the row only on the family's root (Fabric
 * intermediary does). A method declared in an adapter class that overrides a game method is renamed like that method.
 * Both facts are written to {@link AdapterRefs} so the generator can repeat them on runtime names.
 */
public final class RemapAdapters {
    private RemapAdapters() {}

    /** Package of the adapter sources. */
    public static final String SOURCE_PACKAGE = "com/blanket/adapters/";

    /** Descriptor of the compile-only {@code @Stubs} annotation; removed from the output. */
    public static final String STUBS_DESC = "Lcom/blanket/adapters/Stubs;";

    /**
     * The mapping chain from the set's authoring names to its shipping names on {@code version}. {@code mojang} to
     * {@code intermediary}: the ProGuard file (Mojang to obf), then Fabric intermediary (obf to intermediary).
     * {@code yarn-legacy} to {@code lf}: Legacy Yarn (named to LF intermediary). {@code official} to {@code official}: none.
     */
    public static List<StubJars.Table> stages(StubJars.AdapterSet set, String version, MappingDownloads downloads) throws IOException {
        String route = set.authoring() + "->" + set.shipping();
        switch (route) {
            case "mojang->intermediary":
                return List.of(StubJars.Table.read(downloads.clientMappings(version), 0, 1),
                        StubJars.Table.read(downloads.intermediary(version, false), 0, 1));
            case "yarn-legacy->lf":
                return List.of(StubJars.Table.read(downloads.legacyYarn(set.authoringMappings()), 1, 0));
            case "official->official":
                return List.of();
            default:
                throw new IllegalArgumentException("adapter set " + set.name() + ": no mapping route from " + set.authoring()
                        + " names to " + set.shipping() + " names");
        }
    }

    /** The version of a class's {@code @Stubs} annotation, or null when it has none. */
    public static String stubsVersion(byte[] classFile) {
        String[] value = new String[1];
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!STUBS_DESC.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object v) {
                        if ("value".equals(name)) value[0] = (String) v;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return value[0];
    }

    /** What one era of a set is remapped with: its stub jar, the jars beside it, and its mapping chain. */
    public record Era(Path stubJar, List<Path> libraries, List<? extends Remapper> stages) {}

    /** The era of a Minecraft version (the set's stubs version, or a {@code @Stubs} version). */
    @FunctionalInterface
    public interface EraSource {
        Era era(String version) throws IOException;
    }

    /** Remapped classes by shipping internal name, and the facts for {@code adapter-refs.json}. */
    record Output(Map<String, byte[]> classes, Map<String, String> declaring, Map<String, String> overrides) {}

    /**
     * Remaps a whole set and writes {@code out}: first deletes it, then writes {@code <shipping name>.class} for every
     * class and {@link AdapterRefs#FILE}. Classes are grouped by the {@code @Stubs} version of their top-level class
     * ({@code setStubs} when none); each group is remapped with {@code eras.era(version)}.
     *
     * @param classes the compiled classes in authoring names, by internal name
     */
    public static AdapterRefs remapSet(String set, String namespace, String setStubs, Map<String, byte[]> classes,
                                       EraSource eras, Path out) throws IOException {
        Map<String, Map<String, byte[]>> groups = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            String name = e.getKey();
            int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
            byte[] top = classes.get(dollar < 0 ? name : name.substring(0, dollar));
            String version = top == null ? null : stubsVersion(top);
            groups.computeIfAbsent(version == null ? setStubs : version, v -> new TreeMap<>()).put(name, e.getValue());
        }
        Map<String, byte[]> written = new TreeMap<>();
        Map<String, String> declaring = new TreeMap<>();
        Map<String, String> overrides = new TreeMap<>();
        for (Map.Entry<String, Map<String, byte[]>> g : groups.entrySet()) {
            Era era = eras.era(g.getKey());
            try (ClassIndex index = new ClassIndex(classes, era.stubJar(), era.libraries())) {
                Output o = remap(set, g.getValue(), index, era.stages());
                written.putAll(o.classes());
                declaring.putAll(o.declaring());
                overrides.putAll(o.overrides());
            }
        }
        AdapterRefs refs = new AdapterRefs(set, namespace, declaring, overrides);
        CompileAdapters.deleteTree(out);
        for (Map.Entry<String, byte[]> e : written.entrySet()) {
            Path file = out.resolve(e.getKey() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        refs.write(out.resolve(AdapterRefs.FILE));
        return refs;
    }

    /**
     * Remaps {@code group} (classes of one era, authoring names) to shipping names.
     *
     * @param index  the hierarchy in authoring names; it must hold every class of the set as {@code ADAPTER}
     * @param stages authoring to shipping names, first stage first; each is looked up by declaring class
     */
    static Output remap(String set, Map<String, byte[]> group, ClassIndex index, List<? extends Remapper> stages) {
        Shipping remapper = new Shipping(set, index, stages);
        Map<String, byte[]> out = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : group.entrySet()) {
            remapper.recordOverrides(e.getKey(), e.getValue());
            ClassWriter writer = new ClassWriter(0);
            ClassVisitor strip = new ClassVisitor(Opcodes.ASM9, new ClassRemapper(writer, remapper)) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return STUBS_DESC.equals(descriptor) ? null : super.visitAnnotation(descriptor, visible);
                }
            };
            new ClassReader(e.getValue()).accept(strip, 0);
            out.put(remapper.map(e.getKey()), writer.toByteArray());
        }
        return new Output(out, remapper.declaring, remapper.overrides);
    }

    /** Authoring to shipping names for one era of one set, collecting the facts of {@link AdapterRefs} on the way. */
    private static final class Shipping extends Remapper {
        private record Member(String name, String declaring) {}

        private final String set;
        private final ClassIndex index;
        private final List<? extends Remapper> stages;
        private final Map<String, Member> methods = new HashMap<>();
        private final Map<String, Member> fields = new HashMap<>();
        final Map<String, String> declaring = new TreeMap<>();
        final Map<String, String> overrides = new TreeMap<>();

        Shipping(String set, ClassIndex index, List<? extends Remapper> stages) {
            this.set = set;
            this.index = index;
            this.stages = stages;
        }

        @Override
        public String map(String internalName) {
            if (internalName.startsWith(SOURCE_PACKAGE)) {
                return SOURCE_PACKAGE + set + '/' + internalName.substring(SOURCE_PACKAGE.length());
            }
            String n = internalName;
            for (Remapper stage : stages) n = stage.map(n);
            return n;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<") || owner.startsWith("[")) return name;
            return methods.computeIfAbsent(owner + '.' + name + descriptor, k -> method(owner, name, descriptor)).name();
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            if (owner.startsWith("[")) return name;
            return fields.computeIfAbsent(owner + '.' + name + ':' + descriptor, k -> field(owner, name, descriptor)).name();
        }

        /** Adds an {@code overrides} entry for each method of an adapter class that overrides a game method. */
        void recordOverrides(String className, byte[] classFile) {
            List<String[]> declared = new ArrayList<>();
            new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !name.startsWith("<")) {
                        declared.add(new String[] {name, descriptor});
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (String[] m : declared) {
                List<String> family = overridden(className, m[0], m[1]);
                if (family.isEmpty()) continue;
                Member t = translate(family, m[0], m[1], false);
                overrides.put(AdapterRefs.methodKey(map(className), t.name(), mapMethodDesc(m[1])), t.declaring());
            }
        }

        private Member method(String owner, String name, String desc) {
            ClassIndex.Info d = index.resolveMethod(owner, name, desc);
            if (d == null) return new Member(name, null);
            int access = d.methods().get(name + desc);
            boolean virtual = (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
            switch (d.origin()) {
                case ADAPTER: {
                    // the adapter declares it: renamed only when the declaration overrides a game method
                    if (!virtual) return new Member(name, null);
                    List<String> family = overridden(d.name(), name, desc);
                    return family.isEmpty() ? new Member(name, null) : new Member(translate(family, name, desc, false).name(), null);
                }
                case LIBRARY: {
                    if (index.isGame(owner)) declaring.put(AdapterRefs.methodKey(map(owner), name, mapMethodDesc(desc)), d.name());
                    return new Member(name, null);
                }
                default: {
                    List<String> family = new ArrayList<>();
                    family.add(d.name());
                    if (virtual) family.addAll(gameDeclarers(d.name(), name, desc, false));
                    Member t = translate(family, name, desc, false);
                    if (!map(owner).equals(t.declaring())) {
                        declaring.put(AdapterRefs.methodKey(map(owner), t.name(), mapMethodDesc(desc)), t.declaring());
                    }
                    return t;
                }
            }
        }

        private Member field(String owner, String name, String desc) {
            ClassIndex.Info d = index.resolveField(owner, name, desc);
            if (d == null) return new Member(name, null);
            switch (d.origin()) {
                case ADAPTER:
                    return new Member(name, null);
                case LIBRARY: {
                    if (index.isGame(owner)) declaring.put(AdapterRefs.fieldKey(map(owner), name, mapDesc(desc)), d.name());
                    return new Member(name, null);
                }
                default: {
                    Member t = translate(List.of(d.name()), name, desc, true);
                    if (!map(owner).equals(t.declaring())) {
                        declaring.put(AdapterRefs.fieldKey(map(owner), t.name(), mapDesc(desc)), t.declaring());
                    }
                    return t;
                }
            }
        }

        /** The game classes above an adapter class that declare a method the adapter's declaration overrides, nearest first. */
        private List<String> overridden(String adapterClass, String name, String desc) {
            return gameDeclarers(adapterClass, name, desc, true);
        }

        /**
         * The game classes among the proper supertypes of {@code start} that declare {@code name desc} as an
         * overridable method, nearest first. {@code fromOutside}: the overrider is not in a game package, so
         * package-private methods do not count.
         */
        private List<String> gameDeclarers(String start, String name, String desc, boolean fromOutside) {
            List<String> out = new ArrayList<>();
            for (ClassIndex.Info s : index.supertypes(start)) {
                if (s.origin() != ClassIndex.Origin.GAME) continue;
                Integer access = s.methods().get(name + desc);
                if (access == null || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) continue;
                if (fromOutside && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) continue;
                out.add(s.name());
            }
            return out;
        }

        /**
         * Runs a member of {@code family} (authoring names, nearest first) through every stage. Each stage takes the
         * first class of the family that has a row. The declaring class reported is the class the last stage with a
         * row found it on, or the nearest class when no stage has a row (identity mappings, unmapped members).
         *
         * @return the shipping name and the declaring class in shipping names
         */
        private Member translate(List<String> family, String name, String desc, boolean field) {
            List<String> owners = new ArrayList<>(family);
            String n = name;
            String d = desc;
            int row = 0;
            for (Remapper stage : stages) {
                int hit = -1;
                String next = n;
                for (int i = 0; i < owners.size() && hit < 0; i++) {
                    String m = field ? stage.mapFieldName(owners.get(i), n, d) : stage.mapMethodName(owners.get(i), n, d);
                    if (!m.equals(n)) {
                        hit = i;
                        next = m;
                    }
                }
                if (hit >= 0) row = hit;
                for (int i = 0; i < owners.size(); i++) owners.set(i, stage.map(owners.get(i)));
                d = field ? stage.mapDesc(d) : stage.mapMethodDesc(d);
                n = next;
            }
            return new Member(n, owners.get(row));
        }
    }
}
