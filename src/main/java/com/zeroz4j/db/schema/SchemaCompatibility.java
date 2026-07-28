/*
 * Copyright 2026 Franz Schöning
 * Project: https://www.zeroz4j.com
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zeroz4j.db.schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Build-time enforcement of schema compatibility — the answer to "how would I ever catch a
 * release that breaks rollback?".
 * <p>
 * Commit a {@link SchemaDescriptor} for the deployed release, then fail the build when the
 * current classes change it in a way that is not backwards-compatible:
 *
 * <pre>{@code
 * @Test
 * void schemaStaysRollbackCompatible() {
 *     SchemaCompatibility.check(Path.of("src/test/resources/schema-baseline.txt"),
 *             List.of(ShopRoot.class, Product.class, Customer.class));
 * }
 * }</pre>
 *
 * When a change is intentional, regenerate the baseline
 * ({@link SchemaCompatibility#writeBaseline}) in the same commit — so the diff of the schema is
 * reviewed like any other API change, and never happens by accident.
 *
 * <h2>What is classified how</h2>
 * <ul>
 *   <li><b>Added field / added class</b> — safe. Old code reads the data, ignoring what it does
 *       not know; new code sees a default. Rollback stays possible. (Proven by
 *       {@code SchemaEvolutionTest}.)</li>
 *   <li><b>Removed field, changed type, removed class</b> — breaks rollback. Once the new
 *       release rewrites a record, the previous release can no longer reconstruct it.</li>
 *   <li><b>Removed and added field of the same type in one class</b> — <b>critical</b>. This is
 *       the silent one: EclipseStore's legacy mapping pairs leftover fields by type, so the
 *       removed field's data lands in the added field with no error and no log. Intended
 *       renames look identical, which is exactly why a human must confirm it at build time.</li>
 * </ul>
 */
public final class SchemaCompatibility {

    public enum Severity {
        /** Backwards-compatible: the previous release can still read this store. */
        SAFE,
        /** The previous release can no longer read data this release writes. */
        ROLLBACK_BREAKING,
        /** Silent data movement between fields. Must be reviewed by a human. */
        CRITICAL
    }

    public record Change(Severity severity, String className, String detail) {
        @Override
        public String toString() {
            return severity + "  " + className + " — " + detail;
        }
    }

    public record Report(List<Change> changes) {

        public boolean isRollbackCompatible() {
            return changes.stream().allMatch(c -> c.severity() == Severity.SAFE);
        }

        public List<Change> problems() {
            return changes.stream().filter(c -> c.severity() != Severity.SAFE).toList();
        }

        public String format() {
            if (changes.isEmpty()) {
                return "schema unchanged";
            }
            StringBuilder text = new StringBuilder();
            changes.stream()
                    .sorted(java.util.Comparator.comparing((Change c) -> c.severity()).reversed())
                    .forEach(change -> text.append("  ").append(change).append('\n'));
            return text.toString();
        }
    }

    public static Report compare(SchemaDescriptor baseline, SchemaDescriptor current) {
        List<Change> changes = new ArrayList<>();
        Set<String> allClasses = new TreeSet<>(baseline.classes().keySet());
        allClasses.addAll(current.classes().keySet());

        for (String className : allClasses) {
            List<SchemaDescriptor.FieldShape> before = baseline.classes().get(className);
            List<SchemaDescriptor.FieldShape> after = current.classes().get(className);

            if (before == null) {
                changes.add(new Change(Severity.SAFE, className, "new class"));
                continue;
            }
            if (after == null) {
                changes.add(new Change(Severity.ROLLBACK_BREAKING, className,
                        "class removed; stored instances can no longer be loaded"));
                continue;
            }
            changes.addAll(compareFields(className, before, after));
        }
        return new Report(List.copyOf(changes));
    }

    private static List<Change> compareFields(String className,
                                              List<SchemaDescriptor.FieldShape> before,
                                              List<SchemaDescriptor.FieldShape> after) {
        List<Change> changes = new ArrayList<>();
        Map<String, String> beforeTypes = types(before);
        Map<String, String> afterTypes = types(after);

        Set<String> removed = new LinkedHashSet<>(beforeTypes.keySet());
        removed.removeAll(afterTypes.keySet());
        Set<String> added = new LinkedHashSet<>(afterTypes.keySet());
        added.removeAll(beforeTypes.keySet());

        for (String name : beforeTypes.keySet()) {
            String afterType = afterTypes.get(name);
            if (afterType != null && !afterType.equals(beforeTypes.get(name))) {
                changes.add(new Change(Severity.ROLLBACK_BREAKING, className,
                        "field '" + name + "' changed type: " + beforeTypes.get(name)
                                + " -> " + afterType));
            }
        }

        // The dangerous pattern first: a removal and an addition of the same type in one class.
        Set<String> teleportTypes = new HashSet<>();
        for (String removedName : removed) {
            String type = beforeTypes.get(removedName);
            for (String addedName : added) {
                if (type.equals(afterTypes.get(addedName))) {
                    teleportTypes.add(type);
                    changes.add(new Change(Severity.CRITICAL, className,
                            "field '" + removedName + "' removed while '" + addedName
                                    + "' of the same type (" + type + ") was added — stored data "
                                    + "will be silently carried from one into the other. If this "
                                    + "is a rename, confirm it and regenerate the baseline; if "
                                    + "not, split it across two releases."));
                }
            }
        }

        for (String name : removed) {
            if (!teleportTypes.contains(beforeTypes.get(name))) {
                changes.add(new Change(Severity.ROLLBACK_BREAKING, className,
                        "field '" + name + "' removed; the previous release cannot restore it "
                                + "once this release rewrites the record"));
            }
        }
        for (String name : added) {
            if (!teleportTypes.contains(afterTypes.get(name))) {
                changes.add(new Change(Severity.SAFE, className,
                        "field '" + name + "' added (" + afterTypes.get(name) + ")"));
            }
        }
        return changes;
    }

    private static Map<String, String> types(List<SchemaDescriptor.FieldShape> fields) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        fields.forEach(field -> map.put(field.name(), field.type()));
        return map;
    }

    /**
     * Fails with a descriptive error when the current classes are not backwards-compatible with
     * the committed baseline. Creates the baseline on first run.
     *
     * @throws SchemaIncompatibleException if any change is not {@link Severity#SAFE}
     */
    public static Report check(Path baselineFile, Collection<Class<?>> types) {
        SchemaDescriptor current = SchemaDescriptor.of(types);
        if (!Files.exists(baselineFile)) {
            current.write(baselineFile);
            return new Report(List.of());
        }
        Report report = compare(SchemaDescriptor.read(baselineFile), current);
        if (!report.isRollbackCompatible()) {
            throw new SchemaIncompatibleException(baselineFile, report);
        }
        return report;
    }

    /** Accepts the current shape as the new baseline. Run deliberately, commit the diff. */
    public static void writeBaseline(Path baselineFile, Collection<Class<?>> types) {
        SchemaDescriptor.of(types).write(baselineFile);
    }

    public static final class SchemaIncompatibleException extends RuntimeException {
        private final transient Report report;

        SchemaIncompatibleException(Path baselineFile, Report report) {
            super("Schema is not backwards-compatible with " + baselineFile + ":\n"
                    + report.format()
                    + "\nIf these changes are intended, regenerate the baseline with "
                    + "SchemaCompatibility.writeBaseline(...) and commit it — but note that a "
                    + "rollback past this release will not be able to read the new data.");
            this.report = report;
        }

        public Report report() {
            return report;
        }
    }

    private SchemaCompatibility() {
    }
}
