// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Duplicate name+descriptor after remapping (lowering-and-adapters.md section 4.8). */
public final class Collisions {
    private Collisions() {}

    /** Adds one {@code COLLISION} per member that lands on a name+descriptor another member of the class already has. */
    public static void check(ClassNode apiNamed, Remapper map, String target, List<Diagnostic> errors) {
        Map<String, String> methods = new HashMap<>();
        for (MethodNode m : apiNamed.methods) {
            String mapped = map.mapMethodName(apiNamed.name, m.name, m.desc) + map.mapMethodDesc(m.desc);
            report(apiNamed, "method", signature(m.name, m.desc), mapped, methods, target, errors);
        }
        Map<String, String> fields = new HashMap<>();
        for (FieldNode f : apiNamed.fields) {
            String mapped = map.mapFieldName(apiNamed.name, f.name, f.desc) + ":" + map.mapDesc(f.desc);
            report(apiNamed, "field", f.name + ":" + f.desc, mapped, fields, target, errors);
        }
    }

    private static String signature(String name, String desc) {
        return name + desc;
    }

    private static void report(ClassNode cls, String kind, String source, String mapped, Map<String, String> seen,
                               String target, List<Diagnostic> errors) {
        String first = seen.putIfAbsent(mapped, source);
        if (first == null) return;
        String name = Type.getObjectType(cls.name).getClassName();
        errors.add(new Diagnostic(ErrorCode.COLLISION, target, name, null, null,
                "C-collision remapping: " + kind + "s " + first + " and " + source + " of " + name
                        + " both become " + mapped + " after the type remap",
                "rename or change the parameters of one of the two " + kind + "s so they differ after remapping"));
    }
}
