// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import java.io.File;
import java.util.Locale;

/**
 * The launcher's view of the machine: {@code os} is the name version-JSON rules use ({@code windows}, {@code osx},
 * {@code linux}). Tests pass other values to check rule handling for every OS on any machine.
 */
public record Platform(String os, String pathSeparator) {

    public static Platform current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = name.startsWith("windows") ? "windows" : name.startsWith("mac") ? "osx" : "linux";
        return new Platform(os, File.pathSeparator);
    }

    public static Platform of(String os) {
        return new Platform(os, os.equals("windows") ? ";" : ":");
    }

    public boolean windows() {
        return os.equals("windows");
    }

    /** The java launcher's file name in a JDK's {@code bin} folder. */
    public String javaBinary() {
        return windows() ? "java.exe" : "java";
    }
}
