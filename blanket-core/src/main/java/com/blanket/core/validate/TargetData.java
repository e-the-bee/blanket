// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The downloaded game data validation needs. {@link DownloadedTargetData} serves it from the Blanket cache; tests serve fixtures. */
public interface TargetData {

    /** The mapping files, under the resolver's keys. */
    Resolver.MappingFiles mappingFiles();

    /** The jars a class of this target lives in, in priority order: the client jar first, then the loader's own jars. */
    List<Path> classpath(TargetSpec target) throws IOException;

    /** Whether {@link #classpath} includes the loader's jar, so a missing loader class is an error and not a gap in the data. */
    boolean hasLoaderJar(TargetSpec target);

    /**
     * Whether {@link #libraryJars} knows the target's libraries (JOML, LWJGL and the rest of the version's library list),
     * so a library class or member missing from the index is an error and not a gap in the data.
     */
    default boolean coversLibraries(TargetSpec target) {
        return false;
    }

    /**
     * The jars of the libraries the target ships beside its client jar that provide the given classes (internal names
     * in the names of the library, which are the same in every namespace). Only libraries that provide one of them are
     * returned, so nothing else is downloaded.
     */
    default List<Path> libraryJars(TargetSpec target, Set<String> owners) throws IOException {
        return List.of();
    }

    /** Mojang's {@code client_mappings} of a release, or empty when Mojang publishes none for it. */
    Optional<Path> mojangMappings(String mc) throws IOException;

    /** The text of the MCPConfig {@code LICENSE}, or empty when it cannot be obtained. */
    Optional<String> mcpLicense() throws IOException;
}
