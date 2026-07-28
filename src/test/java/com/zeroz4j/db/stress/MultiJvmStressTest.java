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
package com.zeroz4j.db.stress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A short real multi-JVM run with the suite: separate server and client processes, real TCP,
 * cross-process invariants. For a bigger run:
 * {@code java -cp <test-cp> com.zeroz4j.db.stress.MultiJvmStress 8 32 60 SYNC}
 */
class MultiJvmStressTest {

    @Test
    void separateJvmsShareOneStoreCorrectly() throws Exception {
        MultiJvmStress.Outcome outcome = new MultiJvmStress(2, 6, 6, "OS_BUFFERED").run();
        System.out.println(outcome);

        assertEquals(0, outcome.totalFailures(), "no client JVM may fail: " + outcome.problems());
        assertEquals(0, outcome.totalViolations(), "no client may observe a broken invariant");
        assertEquals(outcome.expectedTotal(), outcome.serverTotal(),
                "money conserved across all processes");
        assertEquals(outcome.clientIncrements(), outcome.serverCounter(),
                "every increment from every JVM landed exactly once");
        assertEquals(outcome.catalogSize(), outcome.indexed(), "index matches catalog");
        assertEquals(outcome.catalogSize(), outcome.uniqueIndexed(), "unique index matches catalog");
        assertEquals(outcome.expectedTotal(), outcome.postReopenTotal(),
                "state is durable after the server JVM exits");
        assertTrue(outcome.totalWrites() > 100,
                "harness should do real work, did " + outcome.totalWrites());
        assertTrue(outcome.healthy());
    }
}
