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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kills the owner JVM of a shared store and verifies automatic recovery. Longer runs:
 * {@code java -cp <test-cp> com.zeroz4j.db.stress.FailoverStress 5 60}
 */
class FailoverStressTest {

    @Test
    void survivorsElectANewOwnerAndLoseNoAcknowledgedWrite() throws Exception {
        FailoverStress.Outcome outcome = new FailoverStress(3, 15).run();
        System.out.println(outcome);

        assertTrue(outcome.problems().isEmpty(), "problems: " + outcome.problems());
        assertTrue(outcome.someoneWasPromoted(), "a survivor must take ownership");
        assertTrue(outcome.survivorAcks() > 0, "survivors must keep working after the kill");
        assertTrue(outcome.finalCounter() >= outcome.highestAcknowledged(),
                "acknowledged write lost: store holds " + outcome.finalCounter()
                        + " but a client was told " + outcome.highestAcknowledged());
        assertTrue(outcome.healthy());
    }
}
