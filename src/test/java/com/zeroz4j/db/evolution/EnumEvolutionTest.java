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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What changing an enum's constant list does to data already stored. Each step runs in its own JVM
 * against its own compiled version of {@code evolving.Status}, because a release that reorders an
 * enum can only be reproduced by actually compiling two different orderings.
 * <p>
 * The short version, proven below: an enum value is persisted as its <em>ordinal</em>, and the
 * declaration order is recorded in the type dictionary. Appending a constant is safe. Any other
 * edit — inserting, removing, reordering — is refused at load time, and the whole store becomes
 * unreadable until the classes are put back or a manual mapping is declared. That is why a
 * catch-all constant such as {@code OTHER} belongs <em>first</em> in the declaration: last, it
 * makes every future addition either a lie about the ordering or a store-breaking change.
 */
class EnumEvolutionTest {

    /**
     * The only safe edit. Ordinals 0..n-1 keep their constants, so every stored value still
     * resolves — and this is the whole reason a catch-all constant must be declared first rather
     * than last, since appending is the only room a release ever has.
     */
    @Test
    void appendingAConstantAtTheEndKeepsStoredValuesReadable() throws Exception {
        Path base = base("enum-append");
        Path storeDir = base.resolve("store");
        Path v1 = EnumVersions.compile(base, "v1", "OTHER, OPEN, CLOSED");
        Path v2 = EnumVersions.compile(base, "v2", "OTHER, OPEN, CLOSED, ARCHIVED");

        assertTrue(run(v1, "write", storeDir, "h1", "CLOSED").startsWith("OK"),
                "release 1 stores a value");

        String upgraded = run(v2, "read", storeDir, "h1");
        assertTrue(upgraded.contains("status=CLOSED"),
                "the stored constant must survive an appended constant: " + upgraded);
        assertTrue(upgraded.contains("ordinal=2"),
                "and keep the ordinal it was stored with: " + upgraded);

        // The appended constant is usable, and rolling forward again still reads everything.
        assertTrue(run(v2, "write", storeDir, "h2", "ARCHIVED").startsWith("OK"));
        assertTrue(run(v2, "read", storeDir, "h2").contains("status=ARCHIVED"));
        assertTrue(run(v2, "read", storeDir, "h1").contains("status=CLOSED"));
    }

    /**
     * Inserting shifts every later ordinal. EclipseStore matches the persisted constant names to
     * the current ones, sees that a name would move to a different ordinal, and refuses — the
     * store will not open. Loud, but total: no value in the store is readable, not just the
     * affected one.
     */
    @Test
    void insertingAConstantInTheMiddleMakesTheStoreUnreadable() throws Exception {
        Path base = base("enum-insert");
        Path storeDir = base.resolve("store");
        Path v1 = EnumVersions.compile(base, "v1", "OTHER, OPEN, CLOSED");
        Path v2 = EnumVersions.compile(base, "v2", "OTHER, OPEN, PENDING, CLOSED");

        assertTrue(run(v1, "write", storeDir, "h1", "CLOSED").startsWith("OK"));

        String upgraded = run(v2, "read", storeDir, "h1");
        assertTrue(upgraded.startsWith("FAILED"),
                "an inserted constant must not be silently absorbed: " + upgraded);
        assertTrue(upgraded.contains("PersistenceExceptionTypeConsistencyEnum"),
                "and the refusal must be the enum ordinal check: " + upgraded);

        // The value did not silently become a different constant, which is the failure mode that
        // would be far worse than refusing to open.
        assertTrue(!upgraded.contains("status=PENDING"),
                "the stored value must never resolve to the wrong constant: " + upgraded);

        // It is the store that will not open, not merely this one value that will not read. That
        // distinction is what makes this a deployment-stopping bug rather than a data bug.
        assertTrue(run(v2, "open", storeDir, "h1").startsWith("FAILED"),
                "the whole store must refuse to open, not just the affected value");

        // Putting the declaration back makes the store readable again: nothing was corrupted.
        assertTrue(run(v1, "read", storeDir, "h1").contains("status=CLOSED"),
                "the data is intact; only the new class ordering was refused");
    }

    /**
     * Removing a constant from the middle shifts the ones after it, so it fails the same ordinal
     * check — even though no stored value used the removed constant.
     */
    @Test
    void removingAConstantFromTheMiddleMakesTheStoreUnreadable() throws Exception {
        Path base = base("enum-remove");
        Path storeDir = base.resolve("store");
        Path v1 = EnumVersions.compile(base, "v1", "OTHER, OPEN, PENDING, CLOSED");
        Path v2 = EnumVersions.compile(base, "v2", "OTHER, OPEN, CLOSED");

        assertTrue(run(v1, "write", storeDir, "h1", "CLOSED").startsWith("OK"));

        String upgraded = run(v2, "read", storeDir, "h1");
        assertTrue(upgraded.startsWith("FAILED"),
                "removing a constant must not silently shift stored values: " + upgraded);
    }

    /**
     * The quiet one, and the reason this test class exists rather than a paragraph of folklore.
     * <p>
     * Removing the <em>last</em> constant moves no ordinal, so the store opens and every other
     * value reads back correctly. But a value that was stored as the removed constant comes back
     * as <b>null</b> — no exception, no log line, and a field that was never nullable in the model
     * is suddenly null. A loud refusal would have been the kinder outcome.
     */
    @Test
    void removingTheLastConstantSilentlyTurnsItsStoredValuesIntoNull() throws Exception {
        Path base = base("enum-remove-last");
        Path storeDir = base.resolve("store");
        Path v1 = EnumVersions.compile(base, "v1", "OTHER, OPEN, CLOSED, ARCHIVED");
        Path v2 = EnumVersions.compile(base, "v2", "OTHER, OPEN, CLOSED");

        assertTrue(run(v1, "write", storeDir, "kept", "CLOSED").startsWith("OK"));
        assertTrue(run(v1, "write", storeDir, "lost", "ARCHIVED").startsWith("OK"));

        String survivor = run(v2, "read", storeDir, "kept");
        assertTrue(survivor.contains("status=CLOSED"),
                "constants before the removed one keep their ordinals: " + survivor);

        String orphan = run(v2, "read", storeDir, "lost");
        assertTrue(orphan.startsWith("OK"),
                "the store still opens, which is what makes this dangerous: " + orphan);
        assertTrue(orphan.contains("status=null"),
                "a value of the removed constant silently becomes null: " + orphan);
    }

    /**
     * Renaming a constant is a removal plus an addition, and inherits the same silence: the name
     * is what the persisted type dictionary matches on, so every value stored as the old name
     * comes back null while the constant next to it is untouched.
     */
    @Test
    void renamingAConstantSilentlyTurnsItsStoredValuesIntoNull() throws Exception {
        Path base = base("enum-rename");
        Path storeDir = base.resolve("store");
        Path v1 = EnumVersions.compile(base, "v1", "OTHER, OPEN, CLOSED");
        Path v2 = EnumVersions.compile(base, "v2", "OTHER, ACTIVE, CLOSED");

        assertTrue(run(v1, "write", storeDir, "renamed", "OPEN").startsWith("OK"));
        assertTrue(run(v1, "write", storeDir, "kept", "CLOSED").startsWith("OK"));

        String renamed = run(v2, "read", storeDir, "renamed");
        assertTrue(renamed.contains("status=null"),
                "the renamed constant's stored values are lost, not carried: " + renamed);
        assertTrue(run(v2, "read", storeDir, "kept").contains("status=CLOSED"),
                "while the constant that kept its name and ordinal is fine");
    }

    private static Path base(String name) throws Exception {
        Path base = Path.of("target", name + "-" + System.nanoTime());
        Files.createDirectories(base);
        return base;
    }

    /**
     * Runs the probe in a child JVM whose classpath carries the given enum version first. Unlike
     * {@code SchemaEvolutionTest}, a non-zero exit is expected here — refusing to open the store
     * is the behaviour under test — so the marker line is returned either way.
     */
    private static String run(Path versionClasses, String mode, Path storeDir, String key,
                              String... extra) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", versionClasses + java.io.File.pathSeparator
                        + System.getProperty("java.class.path"),
                EnumProbe.class.getName(), mode, storeDir.toString(), key));
        command.addAll(List.of(extra));

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
        return result == null ? "" : result;
    }
}
