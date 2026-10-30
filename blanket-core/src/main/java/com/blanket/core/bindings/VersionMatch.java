// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code mc} part of a target selector: an exact release id, an inclusive range, an open range, or a
 * regex between slashes (binding-format.md section 2.1). Regexes are matched with {@code find()}, so
 * {@code /^1\.20\./} anchors where it is written to.
 */
public abstract class VersionMatch {

    /** Matches a release id. */
    public abstract boolean matches(Version v);

    /** Parses the text of an {@code mc} value. {@code file} and {@code jsonPath} locate errors. */
    public static VersionMatch parse(String text, String file, String jsonPath) {
        if (text == null) {
            throw new BindingException(file, jsonPath, "version match must be a string");
        }
        String t = text.trim();
        if (t.isEmpty()) {
            throw new BindingException(file, jsonPath, "version match is empty");
        }
        if (t.length() >= 2 && t.charAt(0) == '/' && t.charAt(t.length() - 1) == '/') {
            return regex(t.substring(1, t.length() - 1), file, jsonPath);
        }
        int dots = t.indexOf("..");
        if (dots < 0) {
            return new Exact(version(t, file, jsonPath));
        }
        if (t.indexOf("..", dots + 2) >= 0) {
            throw new BindingException(file, jsonPath, "range '" + t + "' has more than one '..'");
        }
        String lo = t.substring(0, dots).trim();
        String hi = t.substring(dots + 2).trim();
        if (lo.isEmpty() && hi.isEmpty()) {
            throw new BindingException(file, jsonPath, "range '" + t + "' has no bounds; omit \"mc\" to match any version");
        }
        Version low = lo.isEmpty() ? null : version(lo, file, jsonPath);
        Version high = hi.isEmpty() ? null : version(hi, file, jsonPath);
        if (low != null && high != null && low.compareTo(high) > 0) {
            throw new BindingException(file, jsonPath, "range '" + t + "' starts above its end");
        }
        return new Range(low, high);
    }

    private static Version version(String s, String file, String jsonPath) {
        try {
            return Version.parse(s);
        } catch (IllegalArgumentException e) {
            throw new BindingException(file, jsonPath, e.getMessage(), e);
        }
    }

    private static VersionMatch regex(final String body, String file, String jsonPath) {
        final Pattern p;
        try {
            p = Pattern.compile(body);
        } catch (PatternSyntaxException e) {
            throw new BindingException(file, jsonPath, "invalid regex /" + body + "/: " + e.getDescription(), e);
        }
        return new VersionMatch() {
            @Override
            public boolean matches(Version v) {
                return p.matcher(v.toString()).find();
            }

            @Override
            public String toString() {
                return "/" + body + "/";
            }
        };
    }

    static final class Exact extends VersionMatch {
        private final Version version;

        Exact(Version version) {
            this.version = version;
        }

        @Override
        public boolean matches(Version v) {
            return version.equals(v);
        }

        @Override
        public String toString() {
            return version.toString();
        }
    }

    static final class Range extends VersionMatch {
        private final Version low;
        private final Version high;

        Range(Version low, Version high) {
            this.low = low;
            this.high = high;
        }

        @Override
        public boolean matches(Version v) {
            return (low == null || v.compareTo(low) >= 0) && (high == null || v.compareTo(high) <= 0);
        }

        @Override
        public String toString() {
            return (low == null ? "" : low.toString()) + ".." + (high == null ? "" : high.toString());
        }
    }
}
