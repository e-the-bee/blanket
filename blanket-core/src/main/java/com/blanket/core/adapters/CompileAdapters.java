// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.mapping.MappingDownloads;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code compileAdapters} (lowering-and-adapters.md section 5.2): javac {@code --release 8} over the sources of one
 * adapter set, against the set's era stub jar, its API view jar and the game's libraries. A source whose class carries
 * {@code @Stubs("<version>")} is compiled in its own javac run against the stub jar of that version instead (binding
 * example 5), after the set's other classes, which it may use.
 */
public final class CompileAdapters {
    private CompileAdapters() {}

    /** The javac options of every run. {@code -Xlint:-options} silences the "source 8 is obsolete" warning of newer JDKs. */
    static final List<String> OPTIONS = List.of("--release", "8", "-encoding", "UTF-8", "-proc:none", "-implicit:none",
            "-g", "-parameters", "-Xlint:-options");

    private static final Pattern STUBS = Pattern.compile("@Stubs\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"\\s*\\)");

    /** The jars one era is compiled and remapped against: the era stub jar and the game's library jars. */
    public record EraFiles(Path stubJar, List<Path> libraries) {}

    /**
     * The era files of {@code version} for {@code set}. The set's own stubs version is read from {@code stubsDir} (the
     * output of {@code stubJars}: {@code <version>-<authoring>.jar}, {@code <set>-libs.txt}). Another version (a
     * {@code @Stubs} class) is read from {@code extraDir} ({@code <version>-<authoring>.jar},
     * {@code <version>-<authoring>-libs.txt}, libraries copied to {@code libs/}); those files are built here when
     * {@code downloads} is given and they are missing.
     */
    public static EraFiles eraFiles(StubJars.AdapterSet set, String version, Path stubsDir, Path extraDir,
                                    MappingDownloads downloads, PrintStream out) throws IOException {
        String stem = version + "-" + set.authoring();
        boolean own = version.equals(set.stubs());
        Path jar = (own ? stubsDir : extraDir).resolve(stem + ".jar");
        Path libs = own ? stubsDir.resolve(set.name() + "-libs.txt") : extraDir.resolve(stem + "-libs.txt");
        if (!own && downloads != null && (!Files.isRegularFile(jar) || !Files.isRegularFile(libs))) {
            StubJars.AdapterSet other = new StubJars.AdapterSet(set.name(), version, set.authoring(), set.authoringMappings(), set.shipping());
            int n = StubJars.write(downloads.clientJar(version), StubJars.stages(other, downloads), jar);
            out.println("compileAdapters: " + set.name() + ": @Stubs(\"" + version + "\"): " + jar + " (" + n + " classes)");
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, Path> lib : downloads.libraryJars(version).entrySet()) {
                // as in StubJarsMain: the cache names a file by its checksum, javac wants a .jar
                Path copy = extraDir.resolve("libs").resolve(lib.getKey().replace(':', '-') + ".jar");
                if (!Files.isRegularFile(copy) || Files.size(copy) != Files.size(lib.getValue())) {
                    Files.createDirectories(copy.getParent());
                    Files.copy(lib.getValue(), copy, StandardCopyOption.REPLACE_EXISTING);
                }
                text.append(copy.toAbsolutePath()).append('\n');
            }
            Files.writeString(libs, text.toString(), StandardCharsets.UTF_8);
        }
        String task = own ? "stubJars" : "compileAdapters";
        if (!Files.isRegularFile(jar)) throw new IOException("missing stub jar " + jar + "; run " + task + " first");
        if (!Files.isRegularFile(libs)) throw new IOException("missing library list " + libs + "; run " + task + " first");
        List<Path> list = new ArrayList<>();
        for (String line : Files.readAllLines(libs, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) list.add(Path.of(line.trim()));
        }
        return new EraFiles(jar, list);
    }

    /** The {@code @Stubs} version a source file declares, or null. */
    static String stubsVersion(Path source) throws IOException {
        Matcher m = STUBS.matcher(Files.readString(source, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : null;
    }

    /** Every {@code .java} file under {@code dir}, sorted, by {@code @Stubs} version (key null: no annotation). */
    static Map<String, List<Path>> units(Path dir) throws IOException {
        Map<String, List<Path>> units = new TreeMap<>((a, b) -> a == null ? (b == null ? 0 : -1) : b == null ? 1 : a.compareTo(b));
        if (!Files.isDirectory(dir)) return units;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                units.computeIfAbsent(stubsVersion(p), v -> new ArrayList<>()).add(p);
            }
        }
        return units;
    }

    /**
     * Compiles one adapter set: the classes without {@code @Stubs} against {@code eras.era(null)}, then each
     * {@code @Stubs} group against its own era, with the output so far on the classpath. Writes into {@code out},
     * which is emptied first.
     *
     * @param sourceDir the set's {@code src} folder
     * @param common    class folders and jars every run sees (the compiled {@code Stubs} annotation, the API view jar)
     * @param eras      the era files of a version; null means the set's stubs version
     * @param work      a folder for class-version-capped copies of jars (see {@link #capped}) and the {@link #recordShim}
     * @return the number of source files compiled
     */
    public static int compileSet(Path sourceDir, List<Path> common, EraLookup eras, Path work, Path out) throws IOException {
        deleteTree(out);
        Files.createDirectories(out);
        int count = 0;
        for (Map.Entry<String, List<Path>> unit : units(sourceDir).entrySet()) {
            EraFiles era = eras.era(unit.getKey());
            List<Path> classpath = new ArrayList<>(common);
            classpath.add(out);
            if (era.stubJar() != null) classpath.add(era.stubJar());
            classpath.addAll(era.libraries());
            classpath.add(recordShim(work));
            compile(unit.getValue(), capped(classpath, work), out);
            count += unit.getValue().size();
        }
        return count;
    }

    /**
     * A jar holding a signature-only {@code java/lang/Record} (Java 8 class file). With {@code --release 8} javac
     * reads the JDK from the Java 8 platform data, which has no {@code Record}, and refuses any use of a game record
     * class ("class file for java.lang.Record not found"); 1.21.1 and 26.x have records (26.x input events are
     * records). javac takes the missing class from the classpath; adapter class files only name the record classes.
     */
    static Path recordShim(Path work) throws IOException {
        Path jar = work.resolve("record-shim.jar");
        if (Files.isRegularFile(jar)) return jar;
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_SUPER, "java/lang/Record", null, "java/lang/Object", null);
        w.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "()V", null, null).visitEnd();
        int abstractPublic = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
        w.visitMethod(abstractPublic, "equals", "(Ljava/lang/Object;)Z", null, null).visitEnd();
        w.visitMethod(abstractPublic, "hashCode", "()I", null, null).visitEnd();
        w.visitMethod(abstractPublic, "toString", "()Ljava/lang/String;", null, null).visitEnd();
        w.visitEnd();
        StubJars.writeJar(jar, Map.of("java/lang/Record.class", w.toByteArray()));
        return jar;
    }

    /** The era files of a {@code @Stubs} version, or of the set's stubs version for null. */
    @FunctionalInterface
    public interface EraLookup {
        EraFiles era(String version) throws IOException;
    }

    /** javac reported errors; the message holds them. */
    public static final class JavacException extends IOException {
        JavacException(String message) {
            super(message);
        }
    }

    /** One javac run with {@link #OPTIONS}; javac's messages are in the {@link JavacException} when it fails. */
    public static void compile(List<Path> sources, List<Path> classpath, Path out) throws IOException {
        if (sources.isEmpty()) return;
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IOException("no Java compiler in this runtime (" + System.getProperty("java.home") + "); run on a JDK");
        List<String> args = new ArrayList<>(OPTIONS);
        StringBuilder cp = new StringBuilder();
        for (Path p : classpath) {
            if (cp.length() > 0) cp.append(java.io.File.pathSeparatorChar);
            cp.append(p.toAbsolutePath());
        }
        args.add("-classpath");
        args.add(cp.toString());
        args.add("-d");
        args.add(out.toAbsolutePath().toString());
        for (Path s : sources) args.add(s.toAbsolutePath().toString());
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        int code = javac.run(null, messages, messages, args.toArray(new String[0]));
        if (code != 0) throw new JavacException("javac failed (exit " + code + "):\n" + messages.toString(StandardCharsets.UTF_8));
    }

    /**
     * The newest class-file major version the running javac reads ({@code 44 + feature release}). javac refuses a
     * newer class file, and the 26.x client is Java 25 bytecode, so newer jars are handed over as {@link #capped} copies.
     */
    static int maxMajor() {
        return 44 + Runtime.version().feature();
    }

    /**
     * {@code classpath} with every jar that holds a class newer than {@link #maxMajor} replaced by a copy in
     * {@code work} whose classes say {@code maxMajor}. Only the version bytes change: the stub jars hold no code, and
     * javac reads signatures of any version it knows; {@code META-INF/versions/} entries are copied as they are.
     */
    static List<Path> capped(List<Path> classpath, Path work) throws IOException {
        int max = maxMajor();
        List<Path> out = new ArrayList<>();
        for (Path p : classpath) {
            out.add(Files.isRegularFile(p) && newest(p) > max ? cap(p, work, max) : p);
        }
        return out;
    }

    private static int newest(Path jar) throws IOException {
        int newest = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!isBaseClass(e)) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] head = in.readNBytes(8);
                    if (head.length == 8) newest = Math.max(newest, ((head[6] & 0xFF) << 8) | (head[7] & 0xFF));
                }
            }
        }
        return newest;
    }

    private static boolean isBaseClass(ZipEntry e) {
        return !e.isDirectory() && e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/");
    }

    private static Path cap(Path jar, Path work, int max) throws IOException {
        Path copy = work.resolve("capped-" + max).resolve(jar.getFileName().toString());
        if (Files.isRegularFile(copy) && Files.getLastModifiedTime(copy).compareTo(Files.getLastModifiedTime(jar)) >= 0) return copy;
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (e.isDirectory()) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                if (isBaseClass(e) && bytes.length >= 8 && (((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF)) > max) {
                    bytes[4] = 0;
                    bytes[5] = 0;
                    bytes[6] = (byte) (max >> 8);
                    bytes[7] = (byte) max;
                }
                entries.put(e.getName(), bytes);
            }
        }
        StubJars.writeJar(copy, entries);
        return copy;
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
