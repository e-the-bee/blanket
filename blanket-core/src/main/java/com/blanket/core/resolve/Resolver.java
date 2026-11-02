// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import com.blanket.core.mapping.Desc;
import com.blanket.core.mapping.MappingFile;
import com.blanket.core.mapping.MappingIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RESOLVE for one target (pseudocode.md section 3.1, binding-format.md section 2.4). Every reference is routed in two
 * stages: first to the target's obfuscated (or official) name, then to the runtime namespace of the target's era. Each
 * stage runs in rounds: in every round each mapping file is streamed once and answers all requests that need it.
 *
 * <p>Descriptors are translated class by class through the same routes (P-04), so a member's runtime descriptor is
 * built from the runtime names of its classes, never copied from a mapping row.
 */
public final class Resolver {

    /** Gives the local file for a file key: {@code pg:<mc>}, {@code int:<mc>}, {@code lfint:<mc>} or {@code tsrg:<mc>}. */
    public interface MappingFiles {
        Path path(String fileKey) throws IOException;
    }

    /** A reference resolved on the target. {@code name} is null for a class; {@code desc} is null for a class. */
    public record Resolved(String ref, String owner, String name, String desc, boolean field) {}

    /** A reference that did not resolve: the error code, the target, the reference, the failing step and a message. */
    public record Problem(String code, String target, String ref, String step, String message) {}

    /** One streaming step: the mapping file, and the namespaces it translates between. */
    record Hop(String file, String from, String to) {
        String group() {
            return file + "|" + from + "|" + to;
        }
    }

    /** One name being translated. Classes have no name or descriptor. */
    private static final class Job {
        final boolean member;
        final String ns;
        final String version;
        final String origin;      // the first reference that needed this job, for error messages
        String owner;
        String name;
        String desc;
        String obfDesc;           // the descriptor in obfuscated names, kept for composing the runtime descriptor
        final boolean field;
        List<Hop> hops = List.of();
        String failStep;
        String failMessage;
        String runtimeOwner;      // loader members: literal owner
        String runtimeName;

        Job(boolean member, String ns, String version, String origin, String owner, String name, String desc, boolean field) {
            this.member = member;
            this.ns = ns;
            this.version = version;
            this.origin = origin;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.field = field;
        }

        boolean failed() {
            return failStep != null;
        }
    }

    private final TargetSpec target;
    private final String anchor;
    private final MappingFiles files;
    private final Map<String, MappingFile> opened = new HashMap<>();

    public Resolver(TargetSpec target, String anchor, MappingFiles files) {
        this.target = target;
        this.anchor = anchor;
        this.files = files;
    }

    /**
     * Resolves every reference. Failures are appended to {@code problems} with the failing step; references that
     * resolved are returned keyed by their text.
     */
    public Map<String, Resolved> resolve(List<Ref> refs, List<Problem> problems) {
        Map<String, Job> classes = new LinkedHashMap<>();
        List<Job> members = new ArrayList<>();
        Map<String, Ref> byText = new LinkedHashMap<>();

        // Jobs for the owners and the descriptor classes that the references name.
        for (Ref ref : refs) {
            byText.put(ref.text(), ref);
            if (ref.isClass()) {
                classJob(classes, ref.ns(), ref.version(), ref.text(), ref.owner());
                continue;
            }
            classJob(classes, ref.ns(), ref.version(), ref.text(), ref.owner());
            Job m = new Job(true, ref.ns(), ref.version(), ref.text(), ref.owner(), ref.name(), ref.desc(), ref.field());
            if (ref.ns().equals("loader")) {
                m.runtimeOwner = ref.owner();
                m.runtimeName = ref.name();
                for (String c : Desc.classesIn(ref.desc())) classJob(classes, "mojang", anchor, ref.text(), c);
            }
            members.add(m);
        }

        // Stage A: references to the target's obfuscated (or official) names.
        List<Job> stageA = new ArrayList<>(classes.values());
        for (Job m : members) {
            if (m.runtimeOwner == null) stageA.add(m);
        }
        for (Job j : stageA) {
            j.hops = hopsToTargetObf(j.ns, j.version);
        }
        run(stageA);

        // Descriptors of members are obfuscated now; their classes need runtime names as well.
        for (Job m : members) {
            if (m.runtimeOwner != null || m.failed()) continue;
            m.obfDesc = m.desc;
            for (String c : Desc.classesIn(m.obfDesc)) classJob(classes, "obf", null, m.origin, c);
        }

        // Stage B: obfuscated (or official) names to the runtime namespace of the era. Hops are replaced, not
        // appended, because stage A has already run.
        List<Job> stageB = new ArrayList<>();
        for (Job j : classes.values()) {
            if (j.failed()) continue;
            j.hops = runtimeClassHops();
            if (j.hops.isEmpty()) j.runtimeName = j.owner;
            stageB.add(j);
        }
        for (Job m : members) {
            if (m.runtimeOwner != null || m.failed()) continue;
            m.hops = runtimeMemberHops();
            if (m.hops.isEmpty()) m.runtimeName = m.name;
            stageB.add(m);
        }
        run(stageB);

        Map<String, Resolved> out = new LinkedHashMap<>();
        for (Job m : members) {
            Ref ref = byText.get(m.origin);
            if (m.runtimeOwner != null) {
                String desc = Desc.mapClasses(ref.desc(), c -> classRuntime(classes, "mojang", anchor, c, ref.text(), problems));
                out.put(ref.text(), new Resolved(ref.text(), m.runtimeOwner, m.runtimeName, desc, ref.field()));
                continue;
            }
            Job ownerJob = classes.get(jobKey(ref.ns(), ref.version(), ref.owner()));
            if (m.failed()) {
                problems.add(problem(ref.text(), m.failStep, m.failMessage));
                continue;
            }
            if (ownerJob.failed()) {
                problems.add(problem(ref.text(), ownerJob.failStep, ownerJob.failMessage));
                continue;
            }
            String desc = Desc.mapClasses(m.obfDesc, c -> classRuntime(classes, "obf", null, c, ref.text(), problems));
            out.put(ref.text(), new Resolved(ref.text(), ownerJob.runtimeName, m.runtimeName, desc, ref.field()));
        }
        for (Ref ref : refs) {
            if (!ref.isClass()) continue;
            Job j = classes.get(jobKey(ref.ns(), ref.version(), ref.owner()));
            if (j.failed()) {
                problems.add(problem(ref.text(), j.failStep, j.failMessage));
            } else {
                out.put(ref.text(), new Resolved(ref.text(), j.runtimeName, null, null, false));
            }
        }
        return out;
    }

    private Problem problem(String ref, String step, String message) {
        return new Problem("UNRESOLVED", target.id(), ref, step, message);
    }

    /** The runtime name of a descriptor class; classes outside the mapping files (JDK, loader) are kept as they are. */
    private String classRuntime(Map<String, Job> classes, String ns, String version, String c, String origin, List<Problem> problems) {
        Job j = classes.get(jobKey(ns, version, c));
        if (j == null) return c;
        if (j.failed()) {
            problems.add(problem(origin, j.failStep, j.failMessage));
            return c;
        }
        return j.runtimeName;
    }

    private static String jobKey(String ns, String version, String owner) {
        return ns + "@" + (version == null ? "" : version) + ":" + owner;
    }

    private Job classJob(Map<String, Job> classes, String ns, String version, String origin, String owner) {
        return classes.computeIfAbsent(jobKey(ns, version, owner), k -> new Job(false, ns, version, origin, owner, null, null, false));
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ routes

    /** Hops from a reference's namespace to the target's obfuscated (or official) names. */
    List<Hop> hopsToTargetObf(String ns, String version) {
        String t = target.mc();
        List<Hop> hops = new ArrayList<>();
        switch (ns) {
            case "mojang" -> {
                String v = version == null ? anchor : version;
                if (v.equals(t)) {
                    hops.add(new Hop("pg:" + t, "named", "obf"));
                } else {
                    hops.add(new Hop("pg:" + v, "named", "obf"));
                    hops.add(new Hop("int:" + v, "obf", "id"));
                    hops.add(new Hop("int:" + t, "id", "obf"));
                }
            }
            case "intermediary" -> hops.add(new Hop("int:" + t, "id", "obf"));
            case "lf" -> hops.add(new Hop("lfint:" + t, "id", "obf"));
            case "official", "loader", "obf" -> { }
            default -> throw new IllegalArgumentException(ns);
        }
        return hops;
    }

    /** Hops from the target's obfuscated names to the runtime namespace, for a class. */
    List<Hop> runtimeClassHops() {
        String t = target.mc();
        return switch (target.runtime()) {
            case "intermediary" -> List.of(new Hop("int:" + t, "obf", "id"));
            case "lf" -> List.of(new Hop("lfint:" + t, "obf", "id"));
            case "mojang", "srgmoj" -> List.of(new Hop("pg:" + t, "obf", "named"));
            case "srg" -> List.of(new Hop("tsrg:" + t, "obf", "srg"));
            case "official", "obf" -> List.of();
            default -> throw new IllegalArgumentException(target.runtime());
        };
    }

    /** Hops for a member: as for classes, except SRG-named members come from the TSRG table on srgmoj and srg. */
    List<Hop> runtimeMemberHops() {
        String t = target.mc();
        return switch (target.runtime()) {
            case "srgmoj", "srg" -> List.of(new Hop("tsrg:" + t, "obf", "srg"));
            default -> runtimeClassHops();
        };
    }

    // ------------------------------------------------------------------------------------------------ rounds

    /** Runs each job's hops in order, one streaming read per file and round. */
    private void run(List<Job> jobs) {
        int rounds = jobs.stream().mapToInt(j -> j.hops.size()).max().orElse(0);
        for (int r = 0; r < rounds; r++) {
            Map<String, List<Job>> byGroup = new LinkedHashMap<>();
            Map<String, Hop> hopOf = new HashMap<>();
            for (Job j : jobs) {
                if (j.failed() || r >= j.hops.size()) continue;
                Hop h = j.hops.get(r);
                byGroup.computeIfAbsent(h.group(), k -> new ArrayList<>()).add(j);
                hopOf.put(h.group(), h);
            }
            for (Map.Entry<String, List<Job>> e : byGroup.entrySet()) {
                Hop hop = hopOf.get(e.getKey());
                MappingIndex index = read(hop, e.getValue());
                for (Job j : e.getValue()) apply(j, hop, index);
            }
        }
    }

    private MappingIndex read(Hop hop, List<Job> jobs) {
        try {
            MappingFile file = open(hop.file());
            Set<String> wanted = new HashSet<>();
            for (Job j : jobs) {
                if (j.member) wanted.add(file.key(hop.from(), j.owner, j.name));
            }
            return file.read(wanted);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mapping file " + hop.file() + ": " + e.getMessage(), e);
        }
    }

    private MappingFile open(String fileKey) throws IOException {
        MappingFile f = opened.get(fileKey);
        if (f != null) return f;
        Path p = files.path(fileKey);
        String kind = fileKey.substring(0, fileKey.indexOf(':'));
        f = switch (kind) {
            case "pg" -> MappingFile.open(p, "named", "obf");
            case "int", "lfint" -> MappingFile.open(p, "obf", "id");
            case "tsrg" -> MappingFile.open(p, "obf", "srg", "id");
            default -> throw new IllegalArgumentException(fileKey);
        };
        opened.put(fileKey, f);
        return f;
    }

    private static void apply(Job j, Hop hop, MappingIndex index) {
        String ownerTo = index.mapClass(hop.from(), j.owner, hop.to());
        if (ownerTo == null) {
            if (isMinecraft(j.owner)) {
                j.failStep = hop.group();
                j.failMessage = "class " + j.owner + " has no row in " + hop.file();
                return;
            }
            ownerTo = j.owner;   // JDK, loader and already-runtime classes are not in the mapping file
        }
        if (!j.member) {
            j.owner = ownerTo;
            j.runtimeName = ownerTo;
            return;
        }
        MappingIndex.MemberRow row = index.member(hop.from(), j.owner, j.name, j.desc);
        if (row == null) {
            j.failStep = hop.group();
            j.failMessage = (j.field ? "field " : "method ") + j.owner + "." + j.name + j.desc + " has no row in " + hop.file();
            return;
        }
        int to = index.ns(hop.to());
        j.owner = ownerTo;
        j.name = row.name(to);
        j.desc = row.desc(to) != null ? row.desc(to) : index.translateDesc(hop.from(), j.desc, hop.to());
        j.runtimeName = j.name;
    }

    static boolean isMinecraft(String owner) {
        return owner.startsWith("net/minecraft/") || owner.matches("[a-z][a-z0-9]{0,3}");
    }
}
