// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One lowering: how an API member becomes Minecraft code (binding-format.md section 4.3). Exactly one kind is
 * set. References and adapter names are kept as written here; resolving them is the RESOLVE stage's job.
 */
public final class Lowering {

    /**
     * The lowering kinds of section 4.3, plus {@code cast} (D-053) and {@code newarray} (D-056). Both are chain steps
     * only: a cast is a checked change of the static type of the value on top of the stack, a newarray pushes a new
     * primitive array.
     */
    public enum Kind {
        INVOKE("invoke"), GET("get"), PUT("put"), NEW("new"), CHAIN("chain"), ADAPTER("adapter"),
        CONST("const"), UNSUPPORTED("unsupported"), CAST("cast"), NEWARRAY("newarray");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    private final Kind kind;
    private final String ref;
    private final JsonPrimitive constant;
    private final List<Lowering> steps;
    private final int length;

    private Lowering(Kind kind, String ref, JsonPrimitive constant, List<Lowering> steps) {
        this(kind, ref, constant, steps, 0);
    }

    private Lowering(Kind kind, String ref, JsonPrimitive constant, List<Lowering> steps, int length) {
        this.kind = kind;
        this.ref = ref;
        this.constant = constant;
        this.steps = steps;
        this.length = length;
    }

    /**
     * Parses a {@code lower} object. Exactly one kind key may be present. Unknown keys and empty values are
     * errors.
     */
    public static Lowering parse(JsonElement e, String file, String path) {
        JsonObject o = Json.obj(e, file, path);
        if (o.size() != 1) {
            throw new BindingException(file, path, "a lowering has exactly one kind key (" + kindList() + "), found " + o.size());
        }
        String key = o.keySet().iterator().next();
        String at = path + "." + key;
        JsonElement v = o.get(key);
        switch (key) {
            case "invoke":
            case "get":
            case "put":
            case "new":
            case "adapter":
            case "unsupported":
                return new Lowering(kindOf(key), nonEmptyString(v, file, at), null, null);
            case "const":
                if (!v.isJsonPrimitive()) {
                    throw new BindingException(file, at, "const must be true, false, an integer or a string");
                }
                return new Lowering(Kind.CONST, null, v.getAsJsonPrimitive(), null);
            case "chain": {
                if (!v.isJsonArray() || v.getAsJsonArray().size() < 2) {
                    throw new BindingException(file, at, "chain must be an array of at least two lowerings");
                }
                List<Lowering> steps = new ArrayList<Lowering>();
                for (int i = 0; i < v.getAsJsonArray().size(); i++) {
                    steps.add(step(v.getAsJsonArray().get(i), file, at + "[" + i + "]", i));
                }
                checkPushedArguments(steps, file, at);
                return new Lowering(Kind.CHAIN, null, null, Collections.unmodifiableList(steps));
            }
            case "cast":
                throw new BindingException(file, at, "cast is only valid as a chain step (D-053)");
            case "newarray":
                throw new BindingException(file, at, "newarray is only valid as a chain step (D-056)");
            default:
                throw new BindingException(file, path, "unknown lowering kind '" + key + "' (expected one of " + kindList() + ")");
        }
    }

    /**
     * One chain step (binding-format.md section 4.3, D-053, D-056). The first step takes the receiver and arguments, so
     * it may be a direct lowering or a cast. Every later step works on the previous result: an invoke, a get, a cast,
     * or a const or newarray step that pushes an argument for the next invoke. Puts, nested chains and adapters cannot
     * be later steps. Argument counts are checked over the whole chain by {@link #checkPushedArguments}.
     */
    private static Lowering step(JsonElement e, String file, String path, int index) {
        JsonObject o = Json.obj(e, file, path);
        if (o.has("cast")) {
            if (o.size() != 1) {
                throw new BindingException(file, path, "a cast step has exactly one key, \"cast\", found " + o.size());
            }
            return new Lowering(Kind.CAST, nonEmptyString(o.get("cast"), file, path + ".cast"), null, null);
        }
        if (o.has("newarray")) {
            if (index == 0) {
                throw new BindingException(file, path, "the first chain step must be invoke, get, new, adapter or cast, found newarray");
            }
            return newArray(o, file, path);
        }
        Lowering l = parse(e, file, path);
        if (index == 0) {
            if (l.kind == Kind.PUT || l.kind == Kind.CONST || l.kind == Kind.UNSUPPORTED || l.kind == Kind.CHAIN) {
                throw new BindingException(file, path, "the first chain step must be invoke, get, new, adapter or cast, found " + l.kind.json);
            }
            return l;
        }
        if (l.kind != Kind.INVOKE && l.kind != Kind.GET && l.kind != Kind.CONST) {
            throw new BindingException(file, path, "chain step " + index + " must be an invoke, get, cast, const or newarray, found " + l.kind.json);
        }
        return l;
    }

    /** {@code {"newarray": "<primitive descriptor>", "length": n}} (D-056). */
    private static Lowering newArray(JsonObject o, String file, String path) {
        Json.checkKeys(o, file, path, new String[] {"newarray", "length"});
        String prim = nonEmptyString(o.get("newarray"), file, path + ".newarray");
        if (prim.length() != 1 || "ZBCSIJFD".indexOf(prim.charAt(0)) < 0) {
            throw new BindingException(file, path + ".newarray", "newarray takes one primitive descriptor (Z, B, C, S, I, J, F or D), found '" + prim + "'");
        }
        if (!o.has("length")) {
            throw new BindingException(file, path, "a newarray step needs \"length\"");
        }
        int n = Json.integer(o.get("length"), file, path + ".length");
        if (n < 0) {
            throw new BindingException(file, path + ".length", "length must not be negative");
        }
        return new Lowering(Kind.NEWARRAY, prim, null, null, n);
    }

    /**
     * D-056: every later invoke takes exactly the const and newarray steps pushed right before it, in order and with
     * matching types. A pushed value that no invoke consumes is an error.
     */
    private static void checkPushedArguments(List<Lowering> steps, String file, String path) {
        List<String> pushed = new ArrayList<String>();
        for (int i = 1; i < steps.size(); i++) {
            Lowering l = steps.get(i);
            String at = path + "[" + i + "]";
            if (l.kind == Kind.CONST || l.kind == Kind.NEWARRAY) {
                pushed.add(l.kind == Kind.NEWARRAY ? "[" + l.ref : null);
                continue;
            }
            if (l.kind == Kind.INVOKE) {
                List<String> params = parameters(l.ref, file, at);
                if (params.size() != pushed.size()) {
                    throw new BindingException(file, at, "chain step " + i + " takes " + params.size()
                            + " arguments but " + pushed.size() + " const/newarray steps come right before it: " + l.ref);
                }
                for (int k = 0; k < params.size(); k++) {
                    Lowering arg = steps.get(i - params.size() + k);
                    if (!fits(arg, params.get(k))) {
                        throw new BindingException(file, path + "[" + (i - params.size() + k) + "]", "argument " + k + " of "
                                + l.ref + " is " + params.get(k) + ", which " + arg + " does not give");
                    }
                }
            } else if (!pushed.isEmpty()) {
                throw new BindingException(file, at, "const/newarray steps before chain step " + i + " must be consumed by an invoke, found " + l.kind.json);
            }
            pushed.clear();
        }
        if (!pushed.isEmpty()) {
            throw new BindingException(file, path, "the last chain steps push values no invoke consumes");
        }
    }

    /** Whether a const or newarray step gives a value of the descriptor type. */
    private static boolean fits(Lowering arg, String type) {
        if (arg.kind == Kind.NEWARRAY) {
            return type.equals("[" + arg.ref);
        }
        JsonPrimitive c = arg.constant;
        if (c.isBoolean()) {
            return type.equals("Z");
        }
        if (c.isString()) {
            return type.equals("Ljava/lang/String;");
        }
        String text = c.getAsString();
        boolean integral = text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0;
        return integral ? "BSCIJFD".contains(type) && type.length() == 1 : type.equals("F") || type.equals("D");
    }

    /** Parameter descriptors of a method reference, in order. */
    private static List<String> parameters(String ref, String file, String at) {
        int open = ref.indexOf('(', Math.max(0, ref.indexOf('#')));
        int close = open < 0 ? -1 : ref.indexOf(')', open);
        if (close < 0) {
            throw new BindingException(file, at, "an invoke step needs a method descriptor: " + ref);
        }
        List<String> out = new ArrayList<String>();
        int i = open + 1;
        while (i < close) {
            int start = i;
            while (ref.charAt(i) == '[') {
                i++;
            }
            if (ref.charAt(i) == 'L') {
                i = ref.indexOf(';', i);
                if (i < 0 || i > close) {
                    throw new BindingException(file, at, "malformed descriptor: " + ref);
                }
            }
            i++;
            out.add(ref.substring(start, i));
        }
        return out;
    }

    private static String nonEmptyString(JsonElement v, String file, String at) {
        String s = Json.str(v, file, at);
        if (s.isEmpty()) {
            throw new BindingException(file, at, "must not be empty");
        }
        return s;
    }

    private static Kind kindOf(String json) {
        for (Kind k : Kind.values()) {
            if (k.json.equals(json)) {
                return k;
            }
        }
        throw new IllegalArgumentException(json);
    }

    private static String kindList() {
        StringBuilder b = new StringBuilder();
        for (Kind k : Kind.values()) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(k.json);
        }
        return b.toString();
    }

    public Kind kind() {
        return kind;
    }

    /** The reference, adapter name or reason for INVOKE, GET, PUT, NEW, ADAPTER, CAST and UNSUPPORTED; the element descriptor for NEWARRAY; null otherwise. */
    public String ref() {
        return ref;
    }

    /** The array length of a NEWARRAY step; 0 otherwise. The element descriptor is {@link #ref()}. */
    public int length() {
        return length;
    }

    /** The constant of a CONST lowering. */
    public JsonPrimitive constant() {
        return constant;
    }

    /** The steps of a CHAIN lowering, in order; empty otherwise. */
    public List<Lowering> steps() {
        return steps == null ? Collections.<Lowering>emptyList() : steps;
    }

    /** Writes this lowering back to its JSON form. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        switch (kind) {
            case CONST:
                o.add("const", constant);
                break;
            case CHAIN: {
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                for (Lowering s : steps) {
                    a.add(s.toJson());
                }
                o.add("chain", a);
                break;
            }
            case NEWARRAY:
                o.addProperty("newarray", ref);
                o.addProperty("length", length);
                break;
            default:
                o.addProperty(kind.json, ref);
        }
        return o;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
