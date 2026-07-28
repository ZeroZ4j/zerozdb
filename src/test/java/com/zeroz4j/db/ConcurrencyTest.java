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
package com.zeroz4j.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

class ConcurrencyTest {

    /**
     * A write-block sets two fields to the same value; a reader under {@code db.read} must never
     * observe them mid-update ("torn read"). This is the guarantee plain EclipseStore does not
     * give and the write/read locks exist to provide.
     */
    @Test
    void readersNeverObserveTornState() throws Exception {
        Path dir = TestStores.newDir("torn");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<String> torn = new AtomicReference<>();

            Thread reader = new Thread(() -> {
                while (!stop.get() && torn.get() == null) {
                    db.read(() -> {
                        int a = root.a;
                        int b = root.b;
                        if (a != b) {
                            torn.set("saw a=" + a + " b=" + b);
                        }
                    });
                }
            });
            reader.start();

            for (int i = 1; i <= 300 && torn.get() == null; i++) {
                int v = i;
                db.write(ctx -> {
                    root.a = v;
                    Thread.yield();
                    root.b = v;
                    ctx.store(root);
                });
            }
            stop.set(true);
            reader.join(5000);
            assertNull(torn.get(), "reader observed torn state");
        }
    }
}
