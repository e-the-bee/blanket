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
     * The lowering kinds of section 4.3, plus {@code cast} (D-053). A cast is a chain step only: a checked change of
     * the static type of the value on top of the stack.
     */
    public enum Kind {
        INVOKE("invoke"), GET("get"), PUT("put"), NEW("new"), CHAIN("chain"), ADAPTER("adapter"),
        CONST("const"), UNSUPPORTED("unsupported"), CAST("cast");

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

    private Lowering(Kind kind, String ref, JsonPrimitive constant, List<Lowering> steps) {
        this.kind = kind;
        this.ref = ref;
        this.constant = constant;
        this.steps = steps;
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
                return new Lowering(Kind.CHAIN, null, null, Collections.unmodifiableList(steps));
            }
            case "cast":
                throw new BindingException(file, at, "cast is only valid as a chain step (D-053)");
            default:
                throw new BindingException(file, path, "unknown lowering kind '" + key + "' (expected one of " + kindList() + ")");
        }
    }

    /**
     * One chain step (binding-format.md section 4.3, D-053). The first step takes the receiver and arguments, so it may
     * be a direct lowering or a cast. Every later step works on the previous result: it is a zero-argument invoke, a
     * get or a cast. Constants, puts, nested chains and adapters cannot be later steps.
     */
    private static Lowering step(JsonElement e, String file, String path, int index) {
        JsonObject o = Json.obj(e, file, path);
        if (o.has("cast")) {
            if (o.size() != 1) {
                throw new BindingException(file, path, "a cast step has exactly one key, \"cast\", found " + o.size());
            }
            return new Lowering(Kind.CAST, nonEmptyString(o.get("cast"), file, path + ".cast"), null, null);
        }
        Lowering l = parse(e, file, path);
        if (index == 0) {
            if (l.kind == Kind.PUT || l.kind == Kind.CONST || l.kind == Kind.UNSUPPORTED || l.kind == Kind.CHAIN) {
                throw new BindingException(file, path, "the first chain step must be invoke, get, new, adapter or cast, found " + l.kind.json);
            }
            return l;
        }
        if (l.kind != Kind.INVOKE && l.kind != Kind.GET) {
            throw new BindingException(file, path, "chain step " + index + " must be a zero-argument invoke, get or cast, found " + l.kind.json);
        }
        if (l.kind == Kind.INVOKE) {
            int open = l.ref.indexOf('(', Math.max(0, l.ref.indexOf('#')));
            if (open < 0 || open + 1 >= l.ref.length() || l.ref.charAt(open + 1) != ')') {
                throw new BindingException(file, path, "chain step " + index + " must take no arguments (its descriptor has parameters): " + l.ref);
            }
        }
        return l;
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

    /** The reference, adapter name or reason for INVOKE, GET, PUT, NEW, ADAPTER and UNSUPPORTED; null otherwise. */
    public String ref() {
        return ref;
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
