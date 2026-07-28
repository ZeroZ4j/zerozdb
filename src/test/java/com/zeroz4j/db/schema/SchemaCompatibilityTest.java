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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaCompatibilityTest {

    private static SchemaDescriptor descriptor(String text) {
        return SchemaDescriptor.parse(text);
    }

    @Test
    void addingAFieldIsSafe() {
        SchemaDescriptor before = descriptor("""
                shop.Product sku java.lang.String
                """);
        SchemaDescriptor after = descriptor("""
                shop.Product sku java.lang.String
                shop.Product category java.lang.String
                """);
        SchemaCompatibility.Report report = SchemaCompatibility.compare(before, after);
        assertTrue(report.isRollbackCompatible(), report.format());
        assertEquals(1, report.changes().size());
        assertEquals(SchemaCompatibility.Severity.SAFE, report.changes().get(0).severity());
    }

    @Test
    void removingAFieldBreaksRollback() {
        SchemaDescriptor before = descriptor("""
                shop.Product sku java.lang.String
                shop.Product price int
                """);
        SchemaDescriptor after = descriptor("""
                shop.Product sku java.lang.String
                """);
        SchemaCompatibility.Report report = SchemaCompatibility.compare(before, after);
        assertFalse(report.isRollbackCompatible());
        assertEquals(SchemaCompatibility.Severity.ROLLBACK_BREAKING,
                report.problems().get(0).severity());
    }

    /** The silent-data-movement case proven in SchemaEvolutionTest. */
    @Test
    void swappingFieldsOfTheSameTypeIsCritical() {
        SchemaDescriptor before = descriptor("""
                shop.Product sku java.lang.String
                shop.Product title java.lang.String
                """);
        SchemaDescriptor after = descriptor("""
                shop.Product title java.lang.String
                shop.Product category java.lang.String
                """);
        SchemaCompatibility.Report report = SchemaCompatibility.compare(before, after);
        assertFalse(report.isRollbackCompatible());
        assertEquals(SchemaCompatibility.Severity.CRITICAL, report.problems().get(0).severity());
        assertTrue(report.problems().get(0).detail().contains("silently carried"),
                report.format());
    }

    @Test
    void changingAFieldTypeBreaksRollback() {
        SchemaDescriptor before = descriptor("shop.Product price int\n");
        SchemaDescriptor after = descriptor("shop.Product price long\n");
        SchemaCompatibility.Report report = SchemaCompatibility.compare(before, after);
        assertEquals(SchemaCompatibility.Severity.ROLLBACK_BREAKING,
                report.problems().get(0).severity());
    }

    @Test
    void newClassesAreSafeAndRemovedClassesAreNot() {
        SchemaDescriptor before = descriptor("shop.Product sku java.lang.String\n");
        SchemaDescriptor after = descriptor("""
                shop.Product sku java.lang.String
                shop.Coupon code java.lang.String
                """);
        assertTrue(SchemaCompatibility.compare(before, after).isRollbackCompatible());
        assertFalse(SchemaCompatibility.compare(after, before).isRollbackCompatible());
    }

    @Test
    void descriptorRoundTripsThroughText() {
        SchemaDescriptor captured = SchemaDescriptor.of(List.of(SampleRoot.class, SampleItem.class));
        SchemaDescriptor reparsed = SchemaDescriptor.parse(captured.toText());
        assertEquals(captured.toText(), reparsed.toText());
        assertTrue(captured.toText().contains("items"), captured.toText());
    }

    @Test
    void checkCreatesBaselineThenGuardsIt() throws Exception {
        Path baseline = Path.of("target", "schema-" + System.nanoTime(), "baseline.txt");

        // First run writes the baseline and passes.
        SchemaCompatibility.check(baseline, List.of(SampleItem.class));
        assertTrue(java.nio.file.Files.exists(baseline));

        // Same classes: still compatible.
        SchemaCompatibility.check(baseline, List.of(SampleItem.class));

        // Simulate a release that drops a field, by comparing against a richer baseline.
        SchemaCompatibility.writeBaseline(baseline, List.of(SampleRoot.class, SampleItem.class));
        SchemaCompatibility.SchemaIncompatibleException failure =
                assertThrows(SchemaCompatibility.SchemaIncompatibleException.class,
                        () -> SchemaCompatibility.check(baseline, List.of(SampleItem.class)));
        assertTrue(failure.getMessage().contains("SampleRoot"), failure.getMessage());
        assertTrue(failure.getMessage().contains("regenerate the baseline"));
    }

    public static class SampleRoot {
        public java.util.Map<String, SampleItem> items = new java.util.HashMap<>();
        public long nextId;
    }

    public static class SampleItem {
        public String name;
        public int quantity;
        public transient String cachedLabel;      // must not appear in the descriptor
    }

    @Test
    void transientAndStaticFieldsAreIgnored() {
        String text = SchemaDescriptor.of(List.of(SampleItem.class)).toText();
        assertTrue(text.contains("name"));
        assertTrue(text.contains("quantity"));
        assertFalse(text.contains("cachedLabel"), "transient fields are not persisted: " + text);
    }
}
