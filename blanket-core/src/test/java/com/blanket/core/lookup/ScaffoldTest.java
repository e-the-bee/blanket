// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.Targets;
import com.blanket.core.bindings.TypeBinding;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaffoldTest {
    private static final String PLAYER_JAVA = "// SPDX-License-Identifier: GPL-3.0-only\n"
            + "package com.blanket.api;\n\n"
            + "public final class Player {\n"
            + "    private Player() {}\n\n"
            + "    public double x() { throw new UnsupportedOperationException(\"compile-time stub\"); }\n"
            + "}\n";

    private static final String PLAYER_JSON = "{\n"
            + "  \"schema\": 1,\n"
            + "  \"api\": \"com.blanket.api.Player\",\n"
            + "  \"representation\": [\n"
            + "    { \"when\": {}, \"alias\": \"net.example.Player\" }\n"
            + "  ],\n"
            + "  \"members\": {\n"
            + "    \"x\": [\n"
            + "      { \"when\": {}, \"lower\": { \"get\": \"net.example.Player#x:D\" } }\n"
            + "    ]\n"
            + "  }\n"
            + "}\n";

    private static final String HOOKS_JSON = "{\n"
            + "  \"schema\": 1,\n"
            + "  \"hooks\": {\n"
            + "    \"tick.end\": [\n"
            + "      { \"when\": {}, \"method\": \"net.example.Client#tick()V\", \"at\": \"RETURN\" }\n"
            + "    ]\n"
            + "  }\n"
            + "}\n";

    @TempDir Path tmp;
    Path bindings;
    Path api;

    @BeforeEach
    void setUp() throws IOException {
        bindings = tmp.resolve("bindings");
        api = tmp.resolve("api");
        Files.createDirectories(bindings.resolve("types"));
        Files.createDirectories(api);
        Files.copy(LookupFixture.resource("targets.json"), bindings.resolve("targets.json"));
        Files.writeString(api.resolve("Player.java"), PLAYER_JAVA);
        Files.writeString(bindings.resolve("types").resolve("Player.json"), PLAYER_JSON);
    }

    private Scaffold scaffold() {
        Targets targets = LookupFixture.targets();
        return new Scaffold(bindings, api, targets, new Lookup(targets, LookupFixture.files()));
    }

    private String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void memberArmorIsPrefilledOnTargetsWhereLookupFindsIt() throws IOException {
        Path typeFile = bindings.resolve("types").resolve("Player.json");
        List<Path> files = scaffold().member("Player", "int armor()");

        assertEquals(List.of(api.resolve("Player.java"), typeFile), files);
        assertTrue(read(api.resolve("Player.java")).contains("public int armor()"));
        String after = read(typeFile);
        assertTrue(after.startsWith(PLAYER_JSON.substring(0, PLAYER_JSON.lastIndexOf("\n  }"))), "existing lines are untouched");

        TypeBinding tb = BindingFiles.readType(typeFile);
        List<TypeBinding.MemberRule> rules = tb.members().get("armor");
        assertEquals(3, rules.size());
        assertEquals(Set.of("legacy-fabric"), rules.get(0).when().loaders());
        assertEquals("1.8.9", rules.get(0).when().mc());
        assertEquals(Lowering.Kind.UNSUPPORTED, rules.get(0).lower().kind());
        assertTrue(rules.get(0).lower().ref().startsWith("TODO legacy-fabric:1.8.9: "));
        assertEquals(Set.of("forge"), rules.get(1).when().loaders());
        assertEquals(Lowering.Kind.UNSUPPORTED, rules.get(1).lower().kind());
        assertEquals(Lowering.Kind.INVOKE, rules.get(2).lower().kind());
        assertEquals("net.example.Player#armor()I", rules.get(2).lower().ref());
    }

    @Test
    void memberWithNothingFoundGetsOneTodoRule() throws IOException {
        scaffold().member("Player", "float nothingLikeThis()");

        TypeBinding tb = BindingFiles.readType(bindings.resolve("types").resolve("Player.json"));
        List<TypeBinding.MemberRule> rules = tb.members().get("nothingLikeThis");
        assertEquals(1, rules.size());
        assertEquals(Lowering.Kind.UNSUPPORTED, rules.get(0).lower().kind());
        assertTrue(rules.get(0).lower().ref().startsWith("TODO: bind Player.nothingLikeThis"));
        assertTrue(read(api.resolve("Player.java")).contains("public float nothingLikeThis()"));
    }

    @Test
    void overloadedMemberUsesDescriptorKey() throws IOException {
        scaffold().member("Player", "double x(int n)");

        TypeBinding tb = BindingFiles.readType(bindings.resolve("types").resolve("Player.json"));
        assertTrue(tb.members().containsKey("x"));
        assertTrue(tb.members().containsKey("x(I)D"));
        assertTrue(read(api.resolve("Player.java")).contains("public double x(int n)"));
    }

    @Test
    void duplicateMemberKeyIsRefused() throws IOException {
        String javaBefore = read(api.resolve("Player.java"));
        String jsonBefore = read(bindings.resolve("types").resolve("Player.json"));

        assertThrows(IllegalArgumentException.class, () -> scaffold().member("Player", "double x()"));
        assertThrows(IllegalArgumentException.class, () -> scaffold().member("Gadget", "int size()"));

        assertEquals(javaBefore, read(api.resolve("Player.java")));
        assertEquals(jsonBefore, read(bindings.resolve("types").resolve("Player.json")));
    }

    @Test
    void typeAliasCreatesStubAndFile() throws IOException {
        List<Path> files = scaffold().type("Client", "net.example.Client", false);

        assertEquals(2, files.size());
        String java = read(api.resolve("Client.java"));
        assertTrue(java.startsWith("// SPDX-License-Identifier: GPL-3.0-only\npackage com.blanket.api;"));
        assertTrue(java.contains("public final class Client"));
        assertTrue(java.contains("private Client() {}"));
        assertTrue(java.contains("Aliased to {@code net.example.Client}"));

        TypeBinding tb = BindingFiles.readType(bindings.resolve("types").resolve("Client.json"));
        assertEquals("com.blanket.api.Client", tb.api());
        List<TypeBinding.Representation> rep = tb.representation();
        assertEquals("TODO:legacy-fabric:1.8.9", rep.get(0).alias());
        assertEquals("TODO:forge:1.8.9", rep.get(1).alias());
        assertEquals("net.example.Client", rep.get(rep.size() - 1).alias());
        assertTrue(rep.get(rep.size() - 1).when().loaders() == null && rep.get(rep.size() - 1).when().mc() == null);

        assertThrows(IllegalArgumentException.class, () -> scaffold().type("Client", "net.example.Client", false));
    }

    @Test
    void typeOwnedHasNoRepresentation() throws IOException {
        scaffold().type("Gadget", null, true);

        assertTrue(read(api.resolve("Gadget.java")).contains("Owned type."));
        Path json = bindings.resolve("types").resolve("Gadget.json");
        assertFalse(read(json).contains("\"representation\""));
        TypeBinding tb = BindingFiles.readType(json);
        assertEquals("com.blanket.api.Gadget", tb.api());
        assertTrue(tb.representation().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> scaffold().type("Other", "net.example.Client", true));
    }

    @Test
    void eventCreatesFileAndHook() throws IOException {
        Path hooksFile = bindings.resolve("hooks.json");
        Files.writeString(hooksFile, HOOKS_JSON);

        List<Path> files = scaffold().event("HUD_RENDER");

        Path eventFile = bindings.resolve("events").resolve("HudRender.json");
        assertEquals(List.of(eventFile, hooksFile), files);
        assertEquals("HUD_RENDER", BindingFiles.readEvent(eventFile).event());
        assertTrue(read(eventFile).contains("\"hook\": \"hud.render\""));
        String hooks = read(hooksFile);
        assertTrue(hooks.startsWith(HOOKS_JSON.substring(0, HOOKS_JSON.lastIndexOf("\n  }"))));
        assertTrue(BindingFiles.readHooks(hooksFile).hooks().containsKey("hud.render"));
        assertTrue(BindingFiles.readHooks(hooksFile).hooks().containsKey("tick.end"));
        assertTrue(hooks.contains("\"method\": \"TODO:hud.render\""));

        assertThrows(IllegalArgumentException.class, () -> scaffold().event("HUD_RENDER"));
        assertThrows(IllegalArgumentException.class, () -> scaffold().event("hudRender"));
    }

    @Test
    void featureCreatesEnumAndRow() throws IOException {
        scaffold().feature("OFFHAND");

        String enumText = read(api.resolve("Feature.java"));
        assertTrue(enumText.contains("public enum Feature"));
        assertTrue(enumText.contains("    OFFHAND\n"));
        JsonObject features = JsonParser.parseString(read(bindings.resolve("features.json"))).getAsJsonObject();
        assertEquals(0, features.getAsJsonObject("features").getAsJsonObject("OFFHAND").getAsJsonArray("available").size());

        scaffold().feature("SNEAK");

        assertTrue(read(api.resolve("Feature.java")).contains("    OFFHAND,\n    SNEAK\n}"));
        JsonObject both = JsonParser.parseString(read(bindings.resolve("features.json"))).getAsJsonObject().getAsJsonObject("features");
        assertTrue(both.has("OFFHAND") && both.has("SNEAK"));
        assertThrows(IllegalArgumentException.class, () -> scaffold().feature("SNEAK"));
    }

    @Test
    void targetAddsVersionRow() throws IOException {
        Path file = bindings.resolve("targets.json");

        assertEquals(List.of(file), scaffold().target("1.21.4", 21));

        Targets t = BindingFiles.readTargets(file);
        Targets.VersionRow row = t.versions().get("1.21.4");
        assertEquals(21, row.java());
        assertEquals(Boolean.TRUE, row.mapping("mojang"));
        assertEquals("net.fabricmc:intermediary:1.21.4:v2", row.mapping("intermediary"));
        assertEquals("modern", row.adapters());
        assertEquals(LookupFixture.targets().supported(), t.supported());
        assertThrows(IllegalArgumentException.class, () -> scaffold().target("1.21.4", 21));
    }

    @Test
    void packetKindIsRefusedByMain() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = ScaffoldMain.run(new String[] {"--kind", "packet", "--name", "Foo"}, new PrintStream(out), new PrintStream(err));

        assertEquals(2, code);
        assertEquals("scaffold: --kind packet is not supported yet: TypeBinding has no \"packet\" block (binding-format.md 4.6)",
                err.toString(StandardCharsets.UTF_8).trim());
    }
}
