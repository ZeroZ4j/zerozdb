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

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Short version of the stress application, run with the normal suite. For a real soak run:
 * {@code java -cp <test-cp> com.zeroz4j.db.stress.StressHarness 128 120}
 */
class StressTest {

    @Test
    void manyVirtualThreadClientsPreserveEveryInvariant() throws Exception {
        Path base = Path.of("target", "stress-" + System.nanoTime());
        StressHarness.Result result = new StressHarness(
                base.resolve("bank"), base.resolve("audit"), 48, Duration.ofSeconds(5)).run();

        System.out.println(result);

        assertEquals(0, result.unexpectedFailures(), "client threads must not fail");
        assertEquals(result.expectedTotalCents(), result.actualTotalCents(),
                "money invariant: transfers must never create or destroy value");
        assertEquals(result.expectedCounter(), result.actualCounter(),
                "counter invariant: every committed increment counted exactly once");
        assertEquals(result.catalogSize(), result.indexedProducts(),
                "index invariant: index membership matches the catalog");
        assertEquals(result.expectedTotalCents(), result.postReopenTotalCents(),
                "durability: reopened store holds the same total");
        assertTrue(result.transfers() > 100, "harness should have done real work, did "
                + result.transfers() + " writes");
        assertTrue(result.healthy());
    }
}
