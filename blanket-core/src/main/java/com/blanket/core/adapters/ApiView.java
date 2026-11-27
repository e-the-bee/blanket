// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.bindings.Targets;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.bindings.Version;
import com.blanket.core.lookup.Matrix;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.BindingSet;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The API view of an adapter set (lowering-and-adapters.md section 5.2): the classes of {@code blanket-api} and
 * {@code blanket-runtime}, signature only, with every alias API type replaced by the Minecraft class it stands for, in
 * the set's authoring names. An adapter is written against the real classes, so javac must see
 * {@code Target.block(BlockPos, ...)} as taking the stub jar's {@code BlockPos}. Owned types stay as they are. The
 * jar is for javac only and never ships.
 */
public final class ApiView {
    private ApiView() {}

    /** The one supported target of {@code targets.json} that runs the set's stubs version; its loader picks the representation rule. */
    public static TargetSpec target(Targets targets, StubJars.AdapterSet set) {
        for (String id : targets.supported()) {
            TargetSpec t = Matrix.spec(targets, id);
            if (t.mc().equals(set.stubs())) return t;
        }
        throw new IllegalArgumentException("no supported target of targets.json runs " + set.stubs() + ", the stubs version of the "
                + set.name() + " adapter set");
    }

    /**
     * API type (internal name) to the class that represents it in the set's authoring names, for every type file whose
     * representation rule on {@code target} is an alias. Owned types are left out. A type file without a matching
     * representation rule is an error: the view could not say what the type is.
     */
    public static Map<String, String> aliases(BindingSet bindings, TargetSpec target, StubJars.AdapterSet set, List<StubJars.Table> stages) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Version mc = Version.parse(target.mc());
        for (TypeBinding tb : bindings.types().values()) {
            if (tb.representation().isEmpty()) continue;   // an owned facade (a type file with member rules only)
            TypeBinding.Representation chosen = null;
            for (TypeBinding.Representation r : tb.representation()) {
                if (r.when().matches(target.loader(), mc)) {
                    chosen = r;
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalStateException(tb.file() + ": no representation rule matches " + target.id());
            }
            if (chosen.alias() == null) continue;
            String anchor = tb.anchor() != null ? tb.anchor() : bindings.targets().anchor();
            Ref ref = Ref.parse(chosen.alias(), tb.file(), "$.representation");
            aliases.put(tb.api().replace('.', '/'), StubJars.authoringClass(set, ref, anchor, stages));
        }
        return aliases;
    }

    /**
     * Writes the view jar from the {@code com/blanket/} classes found in {@code sources} (jars or class folders; the
     * first source that holds a class wins). Alias types and their nested classes are dropped; every reference to an
     * alias type becomes its representation class.
     *
     * @return the number of classes written
     */
    public static int write(Path out, List<Path> sources, Map<String, String> aliases) throws IOException {
        Map<String, byte[]> found = new TreeMap<>();
        for (Path source : sources) collect(source, found);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                String m = aliases.get(internalName);
                return m == null ? internalName : m;
            }
        };
        Map<String, byte[]> view = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : found.entrySet()) {
            String name = e.getKey().substring(0, e.getKey().length() - ".class".length());
            if (isAlias(name, aliases)) continue;
            view.put(name + ".class", StubJars.signatures(e.getValue(), List.of(remapper)));
        }
        StubJars.writeJar(out, view);
        return view.size();
    }

    private static boolean isAlias(String name, Map<String, String> aliases) {
        if (aliases.containsKey(name)) return true;
        int dollar = name.indexOf('$');
        return dollar > 0 && aliases.containsKey(name.substring(0, dollar));
    }

    private static void collect(Path source, Map<String, byte[]> into) throws IOException {
        if (Files.isDirectory(source)) {
            try (Stream<Path> files = Files.walk(source)) {
                for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = source.relativize(p).toString().replace('\\', '/');
                    if (wanted(name)) into.putIfAbsent(name, Files.readAllBytes(p));
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(source.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory() || !wanted(e.getName())) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        into.putIfAbsent(e.getName(), in.readAllBytes());
                    }
                }
            }
        }
    }

    private static boolean wanted(String entry) {
        return entry.startsWith("com/blanket/") && entry.endsWith(".class");
    }
}
