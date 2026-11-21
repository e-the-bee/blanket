// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.testsupport.RealData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C1, C2, C3, C7 and C8 on the real {@code blanket-bindings} and the real downloaded game data (skipped offline). */
class ValidatorRealDataTest {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");

    private static MappingDownloads downloads() {
        return new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));
    }

    @Test
    void realBindingsAreGreenOnEverySupportedTarget() throws IOException {
        BindingSet bindings = BindingSet.load(BINDINGS);
        TargetData data = new DownloadedTargetData(downloads(), bindings.targets(), BINDINGS);
        Report report;
        try {
            report = new Validator(bindings, data, ApiClasses.of(ValidatorRealDataTest.class.getClassLoader())).run();
        } catch (UncheckedIOException e) {
            throw RealData.skipIfOffline(e);
        }
        assertTrue(report.ok(), report.format());
    }

    @Test
    void mojangsRealHeaderIsTheTextOfMappingsAndVersionsSection8() throws IOException {
        Path file;
        try {
            file = downloads().clientMappings("1.21.1");
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "Mojang mappings unavailable (offline?): " + e.getMessage());
            return;
        }
        assertEquals(MappingTerms.collapse(MappingTerms.MOJANG_HEADER), MappingTerms.collapse(MappingTerms.header(file)));
    }

    @Test
    void theRealMcpConfigLicenseIsTheRecordedOne() throws IOException {
        BindingSet bindings = BindingSet.load(BINDINGS);
        TargetData data = new DownloadedTargetData(downloads(), bindings.targets(), BINDINGS);
        Optional<String> license;
        try {
            license = data.mcpLicense();
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "MCPConfig license unavailable (offline?): " + e.getMessage());
            return;
        }
        assertEquals(MappingTerms.collapse(MappingTerms.MCP_LICENSE), MappingTerms.collapse(license.orElseThrow()));
    }
}
