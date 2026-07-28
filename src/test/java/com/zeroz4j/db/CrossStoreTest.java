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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-store coordination, modeled on a tenant-provisioning shape: a registry store plus a
 * tenant store that must change together.
 */
class CrossStoreTest {

    @Test
    void atomicIntentAcrossTwoStores() {
        Path dirA = TestStores.newDir("xs-registry");
        Path dirB = TestStores.newDir("xs-tenant");
        try (ZeroZDb registry = ZeroZDb.open(new TestRoot(), dirA);
             ZeroZDb tenant = ZeroZDb.open(new TestRoot(), dirB)) {
            TestRoot regRoot = registry.root();
            TestRoot tenRoot = tenant.root();

            CrossStoreWrite.run(ctx -> {
                ctx.on(registry).edit(regRoot.entries);
                regRoot.entries.put("tenant-1", "provisioned");
                ctx.on(tenant).edit(tenRoot.entries);
                tenRoot.entries.put("admin", "franz");
            }, registry, tenant);
        }
        try (ZeroZDb registry = ZeroZDb.open(new TestRoot(), dirA);
             ZeroZDb tenant = ZeroZDb.open(new TestRoot(), dirB)) {
            assertEquals("provisioned", registry.<TestRoot>root().entries.get("tenant-1"));
            assertEquals("franz", tenant.<TestRoot>root().entries.get("admin"));
        }
    }

    @Test
    void blockExceptionAbortsEveryParticipant() {
        Path dirA = TestStores.newDir("xs-abort-a");
        Path dirB = TestStores.newDir("xs-abort-b");
        try (ZeroZDb a = ZeroZDb.open(new TestRoot(), dirA);
             ZeroZDb b = ZeroZDb.open(new TestRoot(), dirB)) {
            TestRoot rootA = a.root();
            TestRoot rootB = b.root();
            assertThrows(RuntimeException.class, () -> CrossStoreWrite.run(ctx -> {
                ctx.on(a).edit(rootA.entries);
                rootA.entries.put("x", "1");
                ctx.on(b).edit(rootB.entries);
                rootB.entries.put("y", "2");
                throw new RuntimeException("boom");
            }, a, b));
            assertTrue(rootA.entries.isEmpty(), "store A rolled back in memory");
            assertTrue(rootB.entries.isEmpty(), "store B rolled back in memory");
        }
        try (ZeroZDb a = ZeroZDb.open(new TestRoot(), dirA);
             ZeroZDb b = ZeroZDb.open(new TestRoot(), dirB)) {
            assertTrue(a.<TestRoot>root().entries.isEmpty(), "nothing on disk A");
            assertTrue(b.<TestRoot>root().entries.isEmpty(), "nothing on disk B");
        }
    }

    @Test
    void phase1FailureInOneStoreAbortsAll() {
        Path dirA = TestStores.newDir("xs-p1-a");
        Path dirB = TestStores.newDir("xs-p1-b");
        try (ZeroZDb a = ZeroZDb.open(new PeopleRoot(), dirA);
             ZeroZDb b = ZeroZDb.open(new TestRoot(), dirB)) {
            PeopleRoot people = a.root();
            TestRoot other = b.root();
            UniqueIndex<String, Person> byEmail = a.uniqueIndex("byEmail", Person.class,
                    () -> a.<PeopleRoot>root().people, p -> p.email);
            a.write(ctx -> {
                ctx.edit(people.people);
                people.people.put("p1", new Person("Alice", "Berlin", "same@x.de"));
            });

            assertThrows(UniqueConstraintException.class, () -> CrossStoreWrite.run(ctx -> {
                ctx.on(b).edit(other.entries);
                other.entries.put("side-effect", "should not survive");
                ctx.on(a).edit(people.people);
                people.people.put("p2", new Person("Bob", "Hamburg", "same@x.de"));
            }, a, b));

            assertNull(other.entries.get("side-effect"), "store B aborted because store A failed validation");
            assertEquals(1, people.people.size());
            assertEquals("Alice", byEmail.get("same@x.de").name);
        }
    }

    @Test
    void nestedSingleStoreWriteJoinsTheCrossWrite() {
        Path dirA = TestStores.newDir("xs-nested-a");
        Path dirB = TestStores.newDir("xs-nested-b");
        try (ZeroZDb a = ZeroZDb.open(new TestRoot(), dirA);
             ZeroZDb b = ZeroZDb.open(new TestRoot(), dirB)) {
            TestRoot rootA = a.root();
            java.util.concurrent.atomic.AtomicLong commitsA = new java.util.concurrent.atomic.AtomicLong();
            a.addCommitListener((seq, count) -> commitsA.incrementAndGet());

            CrossStoreWrite.run(ctx -> {
                ctx.on(a).edit(rootA.entries);
                rootA.entries.put("direct", "1");
                a.write(inner -> {                       // service code calling db.write inside
                    inner.edit(rootA.entries);
                    rootA.entries.put("nested", "2");
                });
            }, a, b);

            assertEquals(1, commitsA.get(), "nested write joined the cross-write's single commit");
            assertEquals("2", rootA.entries.get("nested"));
        }
    }

    @Test
    void oppositeLockOrdersCannotDeadlock() throws Exception {
        Path dirA = TestStores.newDir("xs-dl-a");
        Path dirB = TestStores.newDir("xs-dl-b");
        try (ZeroZDb a = ZeroZDb.open(new TestRoot(), dirA);
             ZeroZDb b = ZeroZDb.open(new TestRoot(), dirB)) {
            TestRoot rootA = a.root();
            TestRoot rootB = b.root();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Runnable worker1 = () -> {
                try {
                    start.await();
                    for (int i = 0; i < 25; i++) {
                        CrossStoreWrite.run(ctx -> {
                            ctx.on(a).edit(rootA.entries);
                            rootA.entries.put("w1", "x");
                            ctx.on(b).edit(rootB.entries);
                            rootB.entries.put("w1", "x");
                        }, a, b);                        // declared order A,B
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            };
            Runnable worker2 = () -> {
                try {
                    start.await();
                    for (int i = 0; i < 25; i++) {
                        CrossStoreWrite.run(ctx -> {
                            ctx.on(b).edit(rootB.entries);
                            rootB.entries.put("w2", "y");
                            ctx.on(a).edit(rootA.entries);
                            rootA.entries.put("w2", "y");
                        }, b, a);                        // declared order B,A — reversed
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            };

            Thread t1 = new Thread(worker1);
            Thread t2 = new Thread(worker2);
            t1.start();
            t2.start();
            start.countDown();
            t1.join(30_000);
            t2.join(30_000);
            assertTrue(!t1.isAlive() && !t2.isAlive(), "deadlock: workers did not finish");
            assertNull(failure.get());
        }
    }
}
