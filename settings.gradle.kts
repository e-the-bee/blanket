// SPDX-License-Identifier: GPL-3.0-only
rootProject.name = "blanket"

include("blanket-api", "blanket-runtime", "blanket-bindings", "blanket-core", "blanket-cli")

// blanket-emit is a separate, private git repository nested in this one (D-035, D-048).
if (file("blanket-emit").isDirectory) {
    include("blanket-emit")
}
