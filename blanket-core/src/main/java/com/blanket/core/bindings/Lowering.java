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

    /** The lowering kinds of section 4.3. */
    public enum Kind {
        INVOKE("invoke"), GET("get"), PUT("put"), NEW("new"), CHAIN("chain"), ADAPTER("adapter"),
        CONST("const"), UNSUPPORTED("unsupported");

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
                    steps.add(parse(v.getAsJsonArray().get(i), file, at + "[" + i + "]"));
                }
                return new Lowering(Kind.CHAIN, null, null, Collections.unmodifiableList(steps));
            }
            default:
                throw new BindingException(file, path, "unknown lowering kind '" + key + "' (expected one of " + kindList() + ")");
        }
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
