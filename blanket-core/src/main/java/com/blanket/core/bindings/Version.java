// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import java.util.Arrays;

/**
 * A Minecraft release id as numeric segments. Ordering is numeric and left-aligned: a shorter id is padded
 * with zeros, so {@code 1.16 == 1.16.0 < 1.16.5}, and {@code 26.1 > 1.21.11} with no special case
 * (binding-format.md section 2.1). Snapshot ids such as {@code 26.4-snapshot-6} are rejected: Blanket
 * targets release ids only (mappings-and-versions.md section 7).
 */
public final class Version implements Comparable<Version> {
    private final int[] segments;
    private final String raw;

    private Version(int[] segments, String raw) {
        this.segments = segments;
        this.raw = raw;
    }

    /** Parses a release id. Throws {@link IllegalArgumentException} with the reason otherwise. */
    public static Version parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("version is null");
        }
        String s = text.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("version is empty");
        }
        if (s.indexOf('-') >= 0 || s.indexOf('w') >= 0) {
            throw new IllegalArgumentException("'" + s + "' looks like a snapshot id; only release ids are valid targets");
        }
        String[] parts = s.split("\\.", -1);
        int[] segs = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || !p.chars().allMatch(c -> c >= '0' && c <= '9') || p.length() > 9) {
                throw new IllegalArgumentException("'" + s + "' is not a release id (dotted numbers such as 1.21.1)");
            }
            segs[i] = Integer.parseInt(p);
        }
        return new Version(segs, s);
    }

    /** True if {@code text} parses as a release id. */
    public static boolean isReleaseId(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public int compareTo(Version other) {
        int n = Math.max(segments.length, other.segments.length);
        for (int i = 0; i < n; i++) {
            int a = i < segments.length ? segments[i] : 0;
            int b = i < other.segments.length ? other.segments[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version && compareTo((Version) o) == 0;
    }

    @Override
    public int hashCode() {
        int trimmed = segments.length;
        while (trimmed > 1 && segments[trimmed - 1] == 0) {
            trimmed--;
        }
        return Arrays.hashCode(Arrays.copyOf(segments, trimmed));
    }

    @Override
    public String toString() {
        return raw;
    }
}
