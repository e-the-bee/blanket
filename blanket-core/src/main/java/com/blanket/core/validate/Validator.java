// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.lookup.Matrix;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code validateBindings}: checks C1, C2, C3, C7 and C8 (testing-strategy.md section 2) on every target of
 * {@code targets.json}, and reports every failure for every target at once (binding-format.md section 2.5).
 *
 * <p>Each check reports with one of the section 2.5 codes, and its message begins with the check name:
 * <ul>
 *   <li>C1 coverage: {@link ErrorCode#UNBOUND}</li>
 *   <li>C2 resolution: {@link ErrorCode#UNRESOLVED} (the namespace step that failed, or {@code jar})</li>
 *   <li>C3 shape: {@link ErrorCode#SHAPE}</li>
 *   <li>C7 hooks: {@link ErrorCode#UNRESOLVED} for a hook target, call-site count or native event class that is missing;
 *       {@link ErrorCode#UNSUPPORTED} for a Mixin recipe on an era without Mixin</li>
 *   <li>C8 mapping terms: {@link ErrorCode#UNSUPPORTED} (the mapping source can no longer be used under Blanket's conditions)</li>
 * </ul>
 */
public final class Validator {
    private final BindingSet bindings;
    private final TargetData data;
    private final ApiClasses api;

    public Validator(BindingSet bindings, TargetData data, ApiClasses api) {
        this.bindings = bindings;
        this.data = data;
        this.api = api;
    }

    /** Runs every check. Data that cannot be obtained (a download fails) is an {@link UncheckedIOException}, not a diagnostic. */
    public Report run() {
        List<Diagnostic> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<TargetSpec> specs = new ArrayList<>();
        for (String id : bindings.targets().supported()) {
            try {
                specs.add(Matrix.spec(bindings.targets(), id));
            } catch (IllegalArgumentException e) {
                errors.add(new Diagnostic(ErrorCode.UNBOUND, id, "targets.json", null, null, "C9 matrix: " + e.getMessage(),
                        "give targets.json an era (\"loaders\") and a \"versions\" row for " + id + ", or remove it from \"supported\" (binding-format.md section 3)"));
            }
        }
        try {
            new Coverage(bindings, specs, api, errors, warnings).run();
            for (TargetSpec t : specs) new TargetChecks(bindings, data, api, t, errors, warnings).run();
            MappingTerms.check(bindings.targets(), specs, data, errors);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Report(errors, new ArrayList<>(new java.util.LinkedHashSet<>(warnings)));
    }
}
