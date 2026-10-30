// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads binding files from disk with strict checks, and writes them back in canonical form. */
public final class BindingFiles {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BindingFiles() {}

    /** Parses one JSON file. Syntax errors name the file. */
    public static JsonElement readJson(Path file) {
        String name = file.toString();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r);
        } catch (JsonSyntaxException e) {
            throw new BindingException(name, "$", "invalid JSON: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new BindingException(name, "$", "cannot read file: " + e.getMessage(), e);
        }
    }

    public static Targets readTargets(Path file) {
        return Targets.parse(readJson(file), file.toString());
    }

    public static TypeBinding readType(Path file) {
        return TypeBinding.parse(readJson(file), file.toString());
    }

    public static Hooks readHooks(Path file) {
        return Hooks.parse(readJson(file), file.toString());
    }

    public static EventBinding readEvent(Path file) {
        return EventBinding.parse(readJson(file), file.toString());
    }

    /** Canonical pretty JSON with a trailing newline, for committing files. */
    public static String format(JsonObject o) {
        return PRETTY.toJson(o) + "\n";
    }
}
