// SPDX-License-Identifier: GPL-3.0-only
rootProject.name = "blanket"

include("blanket-api", "blanket-runtime", "blanket-bindings", "blanket-core", "blanket-cli")

// The M0 sample mod; blanket-emit's emitM0 task turns its jar into the six M0 jars.
include("samples:m0-spike")

// The M2 sample mod (api-tour); gate sources arrive with the TOUR slice.
include("samples:api-tour")

// blanket-emit is a separate, private git repository nested in this one (D-035, D-048).
if (file("blanket-emit").isDirectory) {
    include("blanket-emit")
}
