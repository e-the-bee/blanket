// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Which class names are the game's own: names under the game packages, and obfuscated names. Both come from
 * {@code targets.json} ({@code gamePackages}, {@code obfuscatedClass}; binding-format.md section 3), so no game name is
 * written in code. A game class with no mapping row is a failed reference, never a library class.
 */
public final class GameClasses {
    private final List<String> packages;
    private final String obfuscatedClass;
    private final Pattern obfuscated;

    private GameClasses(List<String> packages, String obfuscatedClass, Pattern obfuscated) {
        this.packages = packages;
        this.obfuscatedClass = obfuscatedClass;
        this.obfuscated = obfuscated;
    }

    /**
     * @param packages internal-name prefixes that end with {@code /}
     * @param obfuscatedClass a regex that matches a whole obfuscated internal name, inner-class suffix included
     * @throws java.util.regex.PatternSyntaxException when the regex does not compile
     */
    public static GameClasses of(List<String> packages, String obfuscatedClass) {
        return new GameClasses(List.copyOf(packages), obfuscatedClass, Pattern.compile(obfuscatedClass));
    }

    /** True for a game class: under a game package, or an obfuscated name. */
    public boolean isGame(String internalName) {
        for (String p : packages) {
            if (internalName.startsWith(p)) return true;
        }
        return obfuscated.matcher(internalName).matches();
    }

    public List<String> packages() {
        return packages;
    }

    public String obfuscatedClass() {
        return obfuscatedClass;
    }
}
