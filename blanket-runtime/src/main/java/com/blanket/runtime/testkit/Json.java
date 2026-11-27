// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON the testkit reads and writes. The embedded runtime has no JSON library, so this reads RFC 8259 text into
 * {@link Map} (key order kept), {@link List}, {@link String}, {@link Double}, {@link Boolean} and null.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** The value of the whole text; {@link IllegalArgumentException} names the offset of the first error. */
    static Object parse(String text) {
        Json p = new Json(text);
        p.space();
        Object v = p.value();
        p.space();
        if (p.i != text.length()) throw p.error("text after the value");
        return v;
    }

    /** {@code text} as a JSON string literal. */
    static String quote(String text) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private Object value() {
        if (i >= s.length()) throw error("a value");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("a value");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        i++;
        space();
        if (peek('}')) return out;
        while (true) {
            space();
            if (i >= s.length() || s.charAt(i) != '"') throw error("a member name");
            String key = string();
            space();
            expect(':');
            space();
            if (out.containsKey(key)) throw error("a member name not used before (\"" + key + "\" repeats)");
            out.put(key, value());
            space();
            if (peek('}')) return out;
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<Object>();
        i++;
        space();
        if (peek(']')) return out;
        while (true) {
            space();
            out.add(value());
            space();
            if (peek(']')) return out;
            expect(',');
        }
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("the end of the string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw error("no control character in a string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("an escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("four hex digits");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("four hex digits");
                    }
                    i += 4;
                    break;
                default:
                    throw error("an escape");
            }
        }
    }

    private Double number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        digits();
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            digits();
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            digits();
        }
        return Double.valueOf(s.substring(start, i));
    }

    private void digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == start) throw error("a digit");
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("a value");
        i += w.length();
        return v;
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("'" + c + "'");
    }

    private void space() {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
    }

    private IllegalArgumentException error(String expected) {
        return new IllegalArgumentException("JSON: expected " + expected + " at offset " + i);
    }
}
