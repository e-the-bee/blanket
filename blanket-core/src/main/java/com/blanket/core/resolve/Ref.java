// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import com.blanket.core.bindings.BindingException;

/**
 * A symbol reference as bindings write it (binding-format.md section 2.3): {@code [ns[@version]:]class[#member]}.
 * Class names are internal names ({@code net/minecraft/class_310}); a member is a method with a descriptor or a field
 * with its type.
 */
public record Ref(String text, String ns, String version, String owner, String name, String desc, boolean field) {

    /** Namespaces a reference may use. */
    public static final java.util.Set<String> NAMESPACES = java.util.Set.of("mojang", "intermediary", "lf", "official", "loader");

    /** Parses {@code text}. {@code file} and {@code jsonPath} locate the error when the text is malformed. */
    public static Ref parse(String text, String file, String jsonPath) {
        String ns = "mojang";
        String version = null;
        String rest = text;
        int colon = text.indexOf(':');
        int hash = text.indexOf('#');
        if (colon >= 0 && (hash < 0 || colon < hash)) {
            String prefix = text.substring(0, colon);
            int at = prefix.indexOf('@');
            ns = at >= 0 ? prefix.substring(0, at) : prefix;
            version = at >= 0 ? prefix.substring(at + 1) : null;
            rest = text.substring(colon + 1);
            if (!NAMESPACES.contains(ns)) {
                throw new BindingException(file, jsonPath, "unknown namespace '" + ns + "' in '" + text + "'");
            }
            hash = rest.indexOf('#');
        }
        String cls = hash < 0 ? rest : rest.substring(0, hash);
        if (cls.isEmpty()) {
            throw new BindingException(file, jsonPath, "empty class name in '" + text + "'");
        }
        boolean slashed = ns.equals("intermediary") || ns.equals("lf");
        String owner = slashed ? cls.replace('.', '/') : cls.replace('.', '/');
        if (hash < 0) {
            return new Ref(text, ns, version, owner, null, null, false);
        }
        String member = rest.substring(hash + 1);
        int paren = member.indexOf('(');
        if (paren >= 0) {
            return new Ref(text, ns, version, owner, member.substring(0, paren), member.substring(paren), false);
        }
        int c = member.indexOf(':');
        if (c < 0) {
            throw new BindingException(file, jsonPath, "member '" + member + "' needs a descriptor (name(args)ret) or a field type (name:T) in '" + text + "'");
        }
        return new Ref(text, ns, version, owner, member.substring(0, c), member.substring(c + 1), true);
    }

    public boolean isClass() {
        return name == null;
    }

    /** The reference in the same namespace with another version (anchor fallbacks use this). */
    public Ref withVersion(String v) {
        return new Ref(text, ns, v, owner, name, desc, field);
    }
}
