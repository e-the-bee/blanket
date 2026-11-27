// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's command line from a merged version JSON, as the official launcher builds it, offline: player
 * {@code Dev}, a fixed UUID, token {@code 0}. JSONs with {@code arguments} expand their {@code jvm}/{@code game}
 * lists (entries with rules only when the rules allow the OS); older JSONs get {@code -Djava.library.path},
 * {@code -cp} and the split {@code minecraftArguments}. Extra JVM arguments go right before the main class.
 */
public final class LaunchCommand {
    public static final String PLAYER = "Dev";
    static final String UUID = "7e3b2bcd5a124b1b9c8b1c1e0a5b9d3f";
    private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)}");
    /** The launcher flag the modern version JSONs list in {@code arguments.game} (run.py's scene join). */
    static final String QUICK_PLAY_MULTIPLAYER = "--quickPlayMultiplayer";

    /** Inputs of one launch. */
    public record Inputs(Path java, String versionId, Path gameDir, Path assetsDir, String assetIndex, Path natives,
            Path libraries, List<Path> classpath, Platform platform) {
    }

    private LaunchCommand() {
    }

    public static List<String> build(JsonObject merged, Inputs in, List<String> extraJvm, List<String> extraGame) {
        String cp = String.join(in.platform().pathSeparator(), in.classpath().stream().map(Path::toString).toList());
        Map<String, String> subs = new HashMap<>();
        subs.put("auth_player_name", PLAYER);
        subs.put("version_name", in.versionId());
        subs.put("game_directory", in.gameDir().toString());
        subs.put("assets_root", in.assetsDir().toString());
        subs.put("game_assets", in.assetsDir().toString());
        subs.put("assets_index_name", in.assetIndex());
        subs.put("auth_uuid", UUID);
        subs.put("auth_access_token", "0");
        subs.put("auth_session", "0");
        subs.put("user_type", merged.has("arguments") ? "msa" : "legacy");
        subs.put("version_type", "release");
        subs.put("user_properties", "{}");
        subs.put("clientid", "0");
        subs.put("auth_xuid", "0");
        subs.put("natives_directory", in.natives().toString());
        subs.put("launcher_name", "blanket-testkit");
        subs.put("launcher_version", "0");
        subs.put("classpath", cp);
        subs.put("classpath_separator", in.platform().pathSeparator());
        subs.put("library_directory", in.libraries().toString());

        List<String> jvm;
        List<String> game;
        if (merged.has("arguments")) {
            JsonObject a = merged.getAsJsonObject("arguments");
            jvm = expand(a.getAsJsonArray("jvm"), subs, in.platform().os());
            game = expand(a.getAsJsonArray("game"), subs, in.platform().os());
        } else {
            jvm = List.of("-Djava.library.path=" + in.natives(), "-cp", cp);
            game = new ArrayList<>();
            for (String s : merged.get("minecraftArguments").getAsString().split(" ")) {
                game.add(sub(s, subs));
            }
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(in.java().toString());
        cmd.add("-Xmx2G");
        cmd.addAll(jvm);
        cmd.addAll(extraJvm);
        cmd.add(merged.get("mainClass").getAsString());
        cmd.addAll(game);
        cmd.addAll(extraGame);
        return cmd;
    }

    static List<String> expand(JsonArray args, Map<String, String> subs, String os) {
        List<String> out = new ArrayList<>();
        if (args == null) {
            return out;
        }
        for (JsonElement e : args) {
            if (e.isJsonPrimitive()) {
                out.add(sub(e.getAsString(), subs));
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (!VersionJsons.rulesAllow(o.has("rules") ? o.getAsJsonArray("rules") : null, os)) {
                continue;
            }
            JsonElement v = o.get("value");
            if (v.isJsonArray()) {
                for (JsonElement x : v.getAsJsonArray()) {
                    out.add(sub(x.getAsString(), subs));
                }
            } else {
                out.add(sub(v.getAsString(), subs));
            }
        }
        return out;
    }

    /** Replaces {@code ${name}} by its value; unknown names stay as written. */
    static String sub(String s, Map<String, String> subs) {
        Matcher m = VAR.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = subs.get(m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(b);
        return b.toString();
    }

    /**
     * How the client joins the local server: {@code --quickPlayMultiplayer 127.0.0.1:<port>} when the version JSON's
     * {@code arguments.game} offers that flag, else {@code --server 127.0.0.1 --port <port>} (1.8.9 has no quick play).
     */
    public static List<String> joinArgs(JsonObject merged, int port) {
        if (merged.has("arguments") && merged.getAsJsonObject("arguments").has("game")) {
            JsonPrimitive flag = new JsonPrimitive(QUICK_PLAY_MULTIPLAYER);
            for (JsonElement e : merged.getAsJsonObject("arguments").getAsJsonArray("game")) {
                JsonElement v = e.isJsonObject() ? e.getAsJsonObject().get("value") : null;
                if (v != null && v.isJsonArray() && v.getAsJsonArray().contains(flag)) {
                    return List.of(QUICK_PLAY_MULTIPLAYER, "127.0.0.1:" + port);
                }
            }
        }
        return List.of("--server", "127.0.0.1", "--port", Integer.toString(port));
    }
}
