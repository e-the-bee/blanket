// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One L4 run: provision the target, start the local server, launch the game with the mod (if any) and the testkit
 * system properties, wait for {@code testkit-result.json}, then judge it ({@link Verdict}). Files written to the
 * output folder: {@code game.log}, {@code server.log}, {@code command.txt}, {@code verdict.txt}, {@code shot.png}
 * (Linux, best effort) and the testkit's {@code testkit-result.json}.
 */
public final class Harness {
    /** The run's settings; {@code jar} and {@code probes} may be null (a launch without a mod or without probes). */
    public record Options(Target target, Path jar, Path probes, Path out, int timeoutSeconds, Path home) {
    }

    public static final String PROBES_PROPERTY = "blanket.testkit.probes";
    public static final String OUT_PROPERTY = "blanket.testkit.out";
    public static final String RESULT_FILE = "testkit-result.json";
    /** run.py's options: no pause on focus loss, no accessibility onboarding, no multiplayer warning or tutorial. */
    static final String OPTIONS = "pauseOnLostFocus:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\n"
            + "joinedFirstServer:true\ntutorialStep:none\n";
    /** Log lines worth printing after the run (run.py's list without the M0 sample's tag). */
    static final Pattern IMPORTANT = Pattern.compile(
            "\\[blanket\\]|\\[testkit\\]|Mixin|ERROR|Exception|Caused by|crash|FATAL|Sound engine started",
            Pattern.CASE_INSENSITIVE);
    private static final long SERVER_START_SECONDS = 600;
    private static final long QUIT_GRACE_SECONDS = 30;

    private final PrintStream out;
    private final Jdks jdks;
    private final Platform platform;

    public Harness(PrintStream out, Jdks jdks, Platform platform) {
        this.out = out;
        this.jdks = jdks;
        this.platform = platform;
    }

    /** Runs and returns the exit code: 0 pass, 1 fail. Provisioning problems throw. */
    public int run(Options o) throws IOException, HarnessException, InterruptedException {
        Target t = o.target();
        Path outDir = o.out().toAbsolutePath();
        Files.createDirectories(outDir);
        Layout layout = new Layout(o.home().toAbsolutePath());
        Fetcher fetcher = new Fetcher(out);
        Instance instance = new Instance(layout, fetcher, jdks, platform, out);

        long t0 = System.currentTimeMillis();
        out.println("installing " + t.id() + " into " + layout.mc());
        String vid = instance.install(t);
        Instance.Prepared prep = instance.prepare(vid);
        out.println("provisioned " + vid + " (" + secondsSince(t0) + " s)");

        Path game = layout.gameDir(t).toAbsolutePath();
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        try (Stream<Path> s = Files.list(mods)) {
            for (Path p : s.toList()) {
                LocalServer.deleteTree(p);
            }
        }
        if (o.jar() != null) {
            Files.copy(o.jar(), mods.resolve(o.jar().getFileName()));
        }
        Files.writeString(game.resolve("options.txt"), OPTIONS);

        JsonObject vanilla = instance.vanilla(t.mc());
        Path serverJar = fetcher.fetch(vanilla.getAsJsonObject("downloads").getAsJsonObject("server").get("url")
                .getAsString(), layout.cache().resolve("server-" + t.mc() + ".jar"));
        boolean legacyLevelType = !vanilla.has("arguments");
        Path result = outDir.resolve(RESULT_FILE);
        Files.deleteIfExists(result);
        List<String> jvmExtra = o.probes() == null ? List.of()
                : List.of("-D" + PROBES_PROPERTY + "=" + o.probes().toAbsolutePath(), "-D" + OUT_PROPERTY + "=" + result);
        List<String> cmd = LaunchCommand.build(prep.merged(),
                new LaunchCommand.Inputs(prep.java(), vid, game, layout.assets(), prep.assetIndex(), prep.natives(),
                        layout.libraries(), prep.classpath(), platform),
                jvmExtra, LaunchCommand.joinArgs(prep.merged(), t.port()));
        Files.write(outDir.resolve("command.txt"), cmd, StandardCharsets.UTF_8);

        out.println("starting the local server " + t.mc() + " on 127.0.0.1:" + t.port());
        String state;
        Path gameLog = outDir.resolve("game.log");
        try (LocalServer server = LocalServer.start(jdks.javaFor(Instance.majorVersion(vanilla)), serverJar,
                layout.serverDir(t.mc()), legacyLevelType, t.port(), outDir.resolve("server.log"))) {
            if (!server.awaitReady(SERVER_START_SECONDS)) {
                throw new HarnessException("the local server did not get ready; see " + outDir.resolve("server.log"));
            }
            out.println("launching " + vid + " -> " + gameLog);
            long launched = System.currentTimeMillis();
            Process p = new ProcessBuilder(cmd).directory(game.toFile()).redirectErrorStream(true).start();
            GamePump pump = new GamePump(p, gameLog);
            pump.start();
            boolean joinedShown = false;
            while (true) {
                if (!joinedShown && server.joinedAtMillis() != 0) {
                    out.println("player joined the server after " + (server.joinedAtMillis() - launched) / 1000 + " s");
                    joinedShown = true;
                }
                if (Files.exists(result)) {
                    state = "result written";
                    break;
                }
                if (pump.crashed) {
                    state = "crashed";
                    break;
                }
                if (!p.isAlive()) {
                    state = "exited with " + p.exitValue();
                    break;
                }
                if (System.currentTimeMillis() - launched > o.timeoutSeconds() * 1000L) {
                    state = "timeout";
                    break;
                }
                Thread.sleep(1000);
            }
            long seconds = secondsSince(launched);
            if ((state.equals("result written") || state.equals("timeout")) && p.isAlive()) {
                screenshot(outDir.resolve("shot.png"));
            }
            if (state.equals("result written")) {
                p.waitFor(QUIT_GRACE_SECONDS, TimeUnit.SECONDS);
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            int exit = p.waitFor();
            pump.join(10_000);
            out.println("result: " + state + " (" + seconds + " s after launch, game exit code " + exit + ")");
            state = state + " after " + seconds + " s";
        }

        List<String> lines = Files.readAllLines(gameLog, StandardCharsets.UTF_8);
        JsonObject resultJson = null;
        String missing = state.startsWith("timeout") ? "the game wrote none within " + o.timeoutSeconds() + " s"
                : "the game " + state;
        if (Files.exists(result)) {
            try {
                resultJson = Instance.readJson(result);
            } catch (JsonParseException | IllegalStateException e) {
                missing = "the file is not a JSON object: " + e.getMessage();
            }
        }
        int shown = 0;
        for (String l : lines) {
            if (IMPORTANT.matcher(l).find() && shown++ < 60) {
                out.println("   " + (l.length() > 300 ? l.substring(0, 300) : l));
            }
        }
        Verdict v = Verdict.of(resultJson, missing, lines);
        List<String> report = new ArrayList<>();
        report.add((v.pass() ? "PASS " : "FAIL ") + t.id());
        for (String f : v.failures()) {
            report.add("  " + f);
        }
        Files.write(outDir.resolve("verdict.txt"), report, StandardCharsets.UTF_8);
        report.forEach(out::println);
        return v.pass() ? 0 : 1;
    }

    /** Linux/macOS under X: ImageMagick's {@code import -window root}; evidence only, never part of the verdict. */
    private void screenshot(Path png) {
        if (platform.windows() || System.getenv("DISPLAY") == null) {
            out.println("screenshot: skipped (no X display)");
            return;
        }
        try {
            Process p = new ProcessBuilder("import", "-window", "root", png.toString()).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor(30, TimeUnit.SECONDS);
            out.println("screenshot: " + (p.exitValue() == 0 ? png : "failed " + o));
        } catch (IOException | InterruptedException | IllegalThreadStateException e) {
            out.println("screenshot: skipped, " + e.getMessage());
        }
    }

    private static long secondsSince(long millis) {
        return (System.currentTimeMillis() - millis) / 1000;
    }

    /** Copies the game's output to its log file and notices crash markers. */
    private static final class GamePump extends Thread {
        private final Process process;
        private final Path log;
        volatile boolean crashed;

        GamePump(Process process, Path log) {
            super("game-log");
            setDaemon(true);
            this.process = process;
            this.log = log;
        }

        @Override
        public void run() {
            try (Writer w = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
                    BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    w.write(line);
                    w.write('\n');
                    w.flush();
                    if (Verdict.CRASHED.matcher(line).find()) {
                        crashed = true;
                    }
                }
            } catch (IOException ignored) {
                // the game stopped
            }
        }
    }
}
