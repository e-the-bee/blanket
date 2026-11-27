// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.api.Key;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-16: every {@link Key} constant maps in each set's {@code KeyCodes} adapter to a constant of the window library the
 * set's game uses (LWJGL 2 {@code Keyboard} for legacy, GLFW for modern and unobf), the codes are distinct, {@code NONE}
 * maps to the library's "no key" code, and {@code key(int)} is the exact inverse. Constant values come from the real
 * library jar of the set's stubs version (the jars {@code stubJars} lists in {@code build/stubs/<set>-libs.txt}).
 */
class KeyCodesTablesTest {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    /** {@code case A: return Keyboard.KEY_A;} in {@code code(Key)}. */
    private static final Pattern TO_CODE = Pattern.compile("case\\s+(\\w+)\\s*:\\s*return\\s+(\\w+)\\.(\\w+)\\s*;");
    /** {@code case Keyboard.KEY_A: return Key.A;} in {@code key(int)}. */
    private static final Pattern TO_KEY = Pattern.compile("case\\s+(\\w+)\\.(\\w+)\\s*:\\s*return\\s+Key\\.(\\w+)\\s*;");
    private static final Pattern DEFAULT = Pattern.compile("default\\s*:\\s*return\\s+(\\w+)\\.(\\w+)\\s*;");

    @ParameterizedTest
    @CsvSource({
            "legacy, org/lwjgl/input/Keyboard, KEY_NONE, 0",
            "modern, org/lwjgl/glfw/GLFW, GLFW_KEY_UNKNOWN, -1",
            "unobf, org/lwjgl/glfw/GLFW, GLFW_KEY_UNKNOWN, -1"})
    void everyKeyMapsToADistinctLibraryCodeAndBack(String set, String library, String none, int noneValue) throws IOException {
        Map<String, Integer> constants = constants(set, library);
        String simple = library.substring(library.lastIndexOf('/') + 1);
        assertEquals(noneValue, constants.get(none), simple + "." + none);

        String source = Files.readString(BINDINGS.resolve("adapters/" + set + "/src/com/blanket/adapters/KeyCodes.java"));
        int codeAt = source.indexOf("public static int code(Key key)");
        int keyAt = source.indexOf("public static Key key(int code)");
        assertTrue(codeAt >= 0 && keyAt >= 0, set + " KeyCodes has code(Key) and key(int)");
        String codeBody = codeAt > keyAt ? source.substring(codeAt) : source.substring(codeAt, keyAt);
        String keyBody = keyAt > codeAt ? source.substring(keyAt) : source.substring(keyAt, codeAt);

        Map<Key, Integer> code = new LinkedHashMap<>();
        Matcher m = TO_CODE.matcher(codeBody);
        while (m.find()) {
            assertEquals(simple, m.group(2), set + " code(" + m.group(1) + ")");
            Integer v = constants.get(m.group(3));
            assertNotNull(v, set + ": " + simple + "." + m.group(3) + " is not a constant of the real " + library);
            assertEquals(null, code.put(Key.valueOf(m.group(1)), v), set + ": two cases for Key." + m.group(1));
        }
        Matcher d = DEFAULT.matcher(codeBody);
        assertTrue(d.find(), set + " code(Key) has a default");
        assertEquals(none, d.group(2), set + ": code(Key.NONE) and unknown keys give the library's no-key code");

        Map<Integer, Key> back = new HashMap<>();
        for (Key k : Key.values()) {
            if (k == Key.NONE) {
                assertTrue(!code.containsKey(k) || code.get(k) == noneValue, set + ": NONE is the no-key code");
                continue;
            }
            Integer v = code.get(k);
            assertNotNull(v, set + ": Key." + k + " has no case in code(Key)");
            assertTrue(v != noneValue, set + ": Key." + k + " maps to the no-key code");
            Key clash = back.put(v, k);
            assertEquals(null, clash, set + ": Key." + k + " and Key." + clash + " share code " + v);
        }

        Map<Integer, Key> inverse = new HashMap<>();
        Matcher r = TO_KEY.matcher(keyBody);
        while (r.find()) {
            assertEquals(simple, r.group(1), set + " key(" + r.group(2) + ")");
            Integer v = constants.get(r.group(2));
            assertNotNull(v, set + ": " + simple + "." + r.group(2) + " is not a constant of the real " + library);
            assertEquals(null, inverse.put(v, Key.valueOf(r.group(3))), set + ": two cases for code " + v + " in key(int)");
        }
        assertEquals(back, inverse, set + ": key(int) is the inverse of code(Key)");
    }

    /** The {@code static final int} constants of {@code library} in the set's library jars. */
    private static Map<String, Integer> constants(String set, String library) throws IOException {
        Path libs = BINDINGS.resolve("build/stubs/" + set + "-libs.txt");
        Assumptions.assumeTrue(Files.isRegularFile(libs), "no " + libs + " (stubJars did not run: offline?)");
        for (String line : Files.readAllLines(libs)) {
            if (line.isBlank()) continue;
            try (ZipFile z = new ZipFile(line.trim())) {
                ZipEntry e = z.getEntry(library + ".class");
                if (e == null) continue;
                ClassNode n = new ClassNode();
                new ClassReader(z.getInputStream(e).readAllBytes()).accept(n, ClassReader.SKIP_CODE);
                Map<String, Integer> out = new HashMap<>();
                for (FieldNode f : n.fields) if (f.value instanceof Integer i) out.put(f.name, i);
                return out;
            }
        }
        throw new AssertionError(library + " is in none of the jars of " + libs);
    }
}
