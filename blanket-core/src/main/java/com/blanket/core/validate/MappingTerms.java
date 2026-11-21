// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.Targets;
import com.blanket.core.resolve.TargetSpec;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * C8 mapping terms (testing-strategy.md section 2, mappings-and-versions.md section 8): the header of every Mojang
 * mappings file Blanket reads must be the text its legal conditions were written for, and the MCPConfig license must still
 * be the zlib-style license of research-log.md R-MAP-16. Texts are compared with whitespace collapsed.
 */
final class MappingTerms {
    /** The header of every checked {@code client_mappings.txt} (mappings-and-versions.md section 8, V R-MAP-08), without the {@code #}. */
    static final String MOJANG_HEADER =
            "(c) 2020 Microsoft Corporation. These mappings are provided \"as-is\" and you bear the risk of using them. "
                    + "You may copy and use the mappings for development purposes, but you may not redistribute the mappings "
                    + "complete and unmodified. Microsoft makes no warranties, express or implied, with respect to the mappings "
                    + "provided here. Use and modification of this document or the source code (in any form) of Minecraft: Java "
                    + "Edition is governed by the Minecraft End User License Agreement available at "
                    + "https://account.mojang.com/documents/minecraft_eula.";

    /**
     * The MCPConfig {@code LICENSE} (R-MAP-16, read from the file named there on 2026-11-20): use for any purpose, alter and
     * redistribute, provided the origin is not misrepresented, altered versions are marked and the notice stays.
     */
    static final String MCP_LICENSE =
            "Copyright (c) 2018 Forge Development LLC "
                    + "This software is provided 'as-is', without any express or implied warranty. In no event will the authors be held "
                    + "liable for any damages arising from the use of this software or data. "
                    + "Permission is granted to anyone to use this software and data for any purpose, including commercial applications, "
                    + "and to alter it and redistribute it freely, subject to the following restrictions: "
                    + "1. The origin of this software and data must not be misrepresented; you must not claim that you wrote the original "
                    + "software or data. If you use this software or data in a product, an acknowledgment in the product documentation "
                    + "would be appreciated but is not required. "
                    + "2. Altered versions must be plainly marked as such, and must not be misrepresented as being the original. "
                    + "3. This notice may not be removed or altered from any distribution.";

    private MappingTerms() {}

    static void check(Targets targets, List<TargetSpec> specs, TargetData data, List<Diagnostic> errors) throws IOException {
        TreeSet<String> versions = new TreeSet<>();
        boolean srg = false;
        for (TargetSpec t : specs) {
            versions.add(t.mc());
            srg |= t.runtime().equals("srg") || t.runtime().equals("srgmoj");
        }
        for (String mc : versions) {
            Optional<Path> file = data.mojangMappings(mc);
            if (file.isEmpty()) continue;
            String header = header(file.get());
            if (!collapse(header).equals(collapse(MOJANG_HEADER))) {
                errors.add(new Diagnostic(ErrorCode.UNSUPPORTED, "*:" + mc, "mojang mappings " + mc, null, "header",
                        "C8 mapping terms: the header of Mojang's mappings for " + mc + " is not the text Blanket's legal conditions rely on: \"" + collapse(header) + "\"",
                        "read the new header and decide with the project owner whether mappings-and-versions.md section 8 still holds; "
                                + "then update the text there and MappingTerms.MOJANG_HEADER together"));
            }
        }
        if (srg) {
            Optional<String> license = data.mcpLicense();
            if (license.isPresent() && !collapse(license.get()).equals(collapse(MCP_LICENSE))) {
                errors.add(new Diagnostic(ErrorCode.UNSUPPORTED, "*", "MCPConfig LICENSE", null, "license",
                        "C8 mapping terms: the MCPConfig LICENSE is not the zlib-style text recorded as R-MAP-16: \"" + collapse(license.get()) + "\"",
                        "read the new license; if it still allows use, alteration and redistribution with the notice kept, update research-log.md R-MAP-16 "
                                + "and MappingTerms.MCP_LICENSE, otherwise stop using joined.tsrg (decisions.md D-033)"));
            }
        }
    }

    /** The leading {@code #} comment lines of a mappings file, joined, without the {@code #}. */
    static String header(Path file) throws IOException {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && line.startsWith("#")) b.append(line.substring(1)).append(' ');
        }
        return b.toString();
    }

    static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
