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
package com.zeroz4j.db.evolution;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a release upgrade — and more importantly a <em>rollback</em> — actually does to stored
 * data. Each step runs in its own JVM against its own compiled version of {@code evolving.Product},
 * because that is the only faithful way to reproduce "deploy v2, then roll back to v1".
 */
class SchemaEvolutionTest {

    private static final String V1_FIELDS = "    public String sku;";
    private static final String V2_FIELDS = "    public String sku;\n    public String category;";
    private static final String V2_REMOVED = "    public String category;";
    private static final String V2_RENAMED = "    public String productCode;";

    /** The additive case: v2 adds a field, and rolling back to v1 still reads the old data. */
    @Test
    void addingAFieldKeepsRollbackPossible() throws Exception {
        Path base = base("evo-additive");
        Path storeDir = base.resolve("store");
        Path v1 = ClassVersions.compile(base, "v1", V1_FIELDS);
        Path v2 = ClassVersions.compile(base, "v2", V2_FIELDS);

        // Release 1 writes with the old class.
        assertTrue(run(v1, "write", storeDir, "p1", "sku=SKU-1").contains("OK"));

        // Release 2 (upgrade): the new class reads old data; the added field defaults to null.
        String upgraded = run(v2, "read", storeDir, "p1", "sku", "category");
        assertTrue(upgraded.contains("sku=SKU-1"), "existing data survives the upgrade: " + upgraded);
        assertTrue(upgraded.contains("category=null"), "added field defaults: " + upgraded);

        // Release 2 writes data that uses the new field.
        assertTrue(run(v2, "write", storeDir, "p2", "sku=SKU-2", "category=office").contains("OK"));

        // ROLLBACK to release 1: the old class must still read both records.
        String rolledBackOld = run(v1, "read", storeDir, "p1", "sku");
        String rolledBackNew = run(v1, "read", storeDir, "p2", "sku");
        assertTrue(rolledBackOld.contains("sku=SKU-1"),
                "rollback must still read pre-upgrade data: " + rolledBackOld);
        assertTrue(rolledBackNew.contains("sku=SKU-2"),
                "rollback must still read data written by the newer release: " + rolledBackNew);

        // And the rolled-back release can keep writing.
        assertTrue(run(v1, "write", storeDir, "p3", "sku=SKU-3").contains("OK"));
        assertTrue(run(v2, "read", storeDir, "p3", "sku", "category").contains("sku=SKU-3"),
                "and rolling forward again still works");
    }

    /**
     * With EclipseStore's own (lenient) matching, a rename is carried automatically by the
     * type-similarity fallback. Convenient, and the reason the next test matters.
     */
    @Test
    void renamingAFieldCarriesTheDataUnderLenientMatching() throws Exception {
        Path base = base("evo-rename");
        Path storeDir = base.resolve("store");
        Path v1 = ClassVersions.compile(base, "v1", V1_FIELDS);
        Path v2 = ClassVersions.compile(base, "v2renamed", V2_RENAMED);

        assertTrue(run(v1, "write", storeDir, "p1", "sku=SKU-1", "matching=lenient").contains("OK"));

        String upgraded = run(v2, "read", storeDir, "p1", "productCode", "matching=lenient");
        assertTrue(upgraded.contains("productCode=SKU-1"),
                "the value follows the rename via type-similarity mapping: " + upgraded);
    }

    /**
     * The library's default policy refuses that heuristic, so the same removal-plus-addition
     * leaves the new field unset instead of silently filling it with unrelated data.
     */
    @Test
    void strictMatchingRefusesToTeleportData() throws Exception {
        Path base = base("evo-strict");
        Path storeDir = base.resolve("store");
        Path v1 = ClassVersions.compile(base, "v1pair",
                "    public String sku;\n    public String title;");
        Path v2 = ClassVersions.compile(base, "v2pair",
                "    public String title;\n    public String category;");

        assertTrue(run(v1, "write", storeDir, "p1", "sku=SKU-1", "title=Widget").contains("OK"));

        String upgraded = run(v2, "read", storeDir, "p1", "title", "category");
        assertTrue(upgraded.contains("title=Widget"), "named matches still work: " + upgraded);
        assertTrue(upgraded.contains("category=null"),
                "strict matching must leave the unrelated added field unset: " + upgraded);
    }

    /**
     * The hazard this whole area exists for. Dropping one field and adding an unrelated field
     * of the same type in the same release makes the old field's data <em>teleport</em> into
     * the new one — silently, with no error, because the mapper matches leftovers by type.
     * <p>
     * Nothing at runtime can distinguish this from an intended rename, which is exactly why the
     * check belongs at build time; see {@code SchemaCompatibility}.
     */
    @Test
    void droppingOneFieldAndAddingAnotherOfTheSameTypeTeleportsData() throws Exception {
        Path base = base("evo-teleport");
        Path storeDir = base.resolve("store");
        Path v1 = ClassVersions.compile(base, "v1pair",
                "    public String sku;\n    public String title;");
        Path v2 = ClassVersions.compile(base, "v2pair",
                "    public String title;\n    public String category;");

        assertTrue(run(v1, "write", storeDir, "p1", "sku=SKU-1", "title=Widget",
                "matching=lenient").contains("OK"));

        String upgraded = run(v2, "read", storeDir, "p1", "title", "category",
                "matching=lenient");
        assertTrue(upgraded.contains("title=Widget"), "named match is honoured: " + upgraded);
        assertTrue(upgraded.contains("category=SKU-1"),
                "the dropped field's value silently lands in the unrelated added field: "
                        + upgraded);
    }

    private static Path base(String name) throws Exception {
        Path base = Path.of("target", name + "-" + System.nanoTime());
        Files.createDirectories(base);
        return base;
    }

    /** Runs the probe in a child JVM whose classpath carries the given class version first. */
    private static String run(Path versionClasses, String mode, Path storeDir, String key,
                              String... fields) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", versionClasses + java.io.File.pathSeparator + System.getProperty("java.class.path"),
                EvolutionProbe.class.getName(), mode, storeDir.toString(),
                ClassVersions.CLASS_NAME, key));
        command.addAll(List.of(fields));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("OK") || line.startsWith("FAILED")) {
                    result = line;
                }
            }
        }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "probe JVM did not finish");
        assertEquals(0, process.exitValue(), "probe failed: " + result);
        return result == null ? "" : result;
    }
}
