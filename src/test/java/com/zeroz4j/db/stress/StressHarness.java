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

import com.zeroz4j.db.CrossStoreWrite;
import com.zeroz4j.db.Index;
import com.zeroz4j.db.StaleObjectException;
import com.zeroz4j.db.UniqueConstraintException;
import com.zeroz4j.db.UniqueIndex;
import com.zeroz4j.db.ZeroZDb;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test application that bangs on a ZeroZ DB engine with many Loom virtual-thread clients doing
 * mixed work, then verifies invariants that only hold if the engine is correct.
 * <p>
 * Runnable standalone for soak runs:
 * {@code java -cp <test-classpath> com.zeroz4j.db.stress.StressHarness [clients] [seconds]}
 */
public final class StressHarness {

    public record Result(
            long transfers, long reads, long crossWrites, long staleRetries,
            long uniqueRejections, long unexpectedFailures,
            long expectedTotalCents, long actualTotalCents,
            long expectedCounter, long actualCounter,
            long catalogSize, long indexedProducts,
            String writeLatency, Duration elapsed, Duration reopenTime,
            long postReopenTotalCents) {

        public boolean healthy() {
            return unexpectedFailures == 0
                    && expectedTotalCents == actualTotalCents
                    && expectedCounter == actualCounter
                    && catalogSize == indexedProducts
                    && postReopenTotalCents == expectedTotalCents;
        }

        @Override
        public String toString() {
            long seconds = Math.max(1, elapsed.toSeconds());
            return """
                    ZeroZ DB stress result
                      elapsed            : %s
                      transfers (writes) : %d  (%d/s)
                      reads              : %d  (%d/s)
                      cross-store writes : %d
                      stale retries      : %d
                      unique rejections  : %d
                      unexpected failures: %d
                      write latency      : %s
                      money invariant    : expected %d == actual %d  %s
                      counter invariant  : expected %d == actual %d  %s
                      index invariant    : catalog %d == indexed %d  %s
                      reopen             : %d ms, total after reopen %d  %s"""
                    .formatted(elapsed, transfers, transfers / seconds, reads, reads / seconds,
                            crossWrites, staleRetries, uniqueRejections, unexpectedFailures,
                            writeLatency,
                            expectedTotalCents, actualTotalCents, mark(expectedTotalCents == actualTotalCents),
                            expectedCounter, actualCounter, mark(expectedCounter == actualCounter),
                            catalogSize, indexedProducts, mark(catalogSize == indexedProducts),
                            reopenTime.toMillis(), postReopenTotalCents,
                            mark(postReopenTotalCents == expectedTotalCents));
        }

        private static String mark(boolean ok) {
            return ok ? "OK" : "*** FAILED ***";
        }
    }

    private static final int ACCOUNTS = 200;
    private static final long OPENING_BALANCE_CENTS = 100_000;

    private final Path bankDir;
    private final Path auditDir;
    private final int clients;
    private final Duration duration;
    private final com.zeroz4j.db.Durability durability;

    public StressHarness(Path bankDir, Path auditDir, int clients, Duration duration) {
        this(bankDir, auditDir, clients, duration, com.zeroz4j.db.Durability.SYNC);
    }

    public StressHarness(Path bankDir, Path auditDir, int clients, Duration duration,
                         com.zeroz4j.db.Durability durability) {
        this.bankDir = bankDir;
        this.auditDir = auditDir;
        this.clients = clients;
        this.duration = duration;
        this.durability = durability;
    }

    public Result run() throws InterruptedException {
        StressStats stats = new StressStats();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicLong committedIncrements = new AtomicLong();
        long expectedTotal = (long) ACCOUNTS * OPENING_BALANCE_CENTS;

        long actualTotal;
        long actualCounter;
        long catalogSize;
        long indexed;
        java.time.Instant started;
        Duration elapsed;

        try (ZeroZDb bank = ZeroZDb.open(new BankRoot(), bankDir, durability);
             ZeroZDb audit = ZeroZDb.open(new AuditRoot(), auditDir, durability)) {

            BankRoot bankRoot = bank.root();
            bank.write(ctx -> {
                ctx.edit(bankRoot.accounts);
                for (long id = 0; id < ACCOUNTS; id++) {
                    bankRoot.accounts.put(id, new Account(id, OPENING_BALANCE_CENTS));
                }
                ctx.store(bankRoot);
            });
            Index<String, StressProduct> byCategory = bank.index("stressByCategory",
                    StressProduct.class, () -> ((BankRoot) bank.root()).catalog, p -> p.category);
            UniqueIndex<String, StressProduct> bySku = bank.uniqueIndex("stressBySku",
                    StressProduct.class, () -> ((BankRoot) bank.root()).catalog, p -> p.sku);

            CountDownLatch ready = new CountDownLatch(clients);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();

            for (int i = 0; i < clients; i++) {
                int clientId = i;
                Thread t = Thread.ofVirtual().name("client-" + clientId).unstarted(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        while (!stop.get()) {
                            switch (clientId % 5) {
                                case 0, 4 -> transfer(bank, stats);
                                case 1 -> readAudit(bank, stats, expectedTotal);
                                case 2 -> bumpCounter(bank, stats, committedIncrements);
                                case 3 -> catalogChurn(bank, stats, clientId);
                                default -> crossStoreTransfer(bank, audit, stats, clientId);
                            }
                            if (clientId % 5 == 4) {
                                crossStoreTransfer(bank, audit, stats, clientId);
                            }
                        }
                    } catch (Throwable t2) {
                        firstFailure.compareAndSet(null, t2);
                        stats.unexpectedFailures.incrementAndGet();
                    }
                });
                threads.add(t);
                t.start();
            }

            ready.await();
            started = java.time.Instant.now();
            go.countDown();
            Thread.sleep(duration.toMillis());
            stop.set(true);
            for (Thread t : threads) {
                t.join(60_000);
            }
            elapsed = Duration.between(started, java.time.Instant.now());

            actualTotal = bank.read(() -> bankRoot.accounts.values().stream()
                    .mapToLong(a -> a.balanceCents).sum());
            actualCounter = bank.read(() -> bankRoot.counter.value);
            catalogSize = bank.read(() -> (long) bankRoot.catalog.size());
            indexed = byCategory.size();
            long uniqueIndexed = bySku.size();
            if (uniqueIndexed != catalogSize) {
                throw new AssertionError("Unique index drift: catalog " + catalogSize
                        + " but bySku holds " + uniqueIndexed);
            }
        }

        if (firstFailure.get() != null) {
            throw new AssertionError("client thread failed", firstFailure.get());
        }

        java.time.Instant reopenStart = java.time.Instant.now();
        long postReopenTotal;
        Duration reopenTime;
        try (ZeroZDb bank = ZeroZDb.open(new BankRoot(), bankDir)) {
            reopenTime = Duration.between(reopenStart, java.time.Instant.now());
            BankRoot root = bank.root();
            postReopenTotal = bank.read(() -> root.accounts.values().stream()
                    .mapToLong(a -> a.balanceCents).sum());
        }

        return new Result(stats.writes.get(), stats.reads.get(), stats.crossWrites.get(),
                stats.staleRetries.get(), stats.uniqueRejections.get(),
                stats.unexpectedFailures.get(),
                expectedTotal, actualTotal,
                committedIncrements.get(), actualCounter,
                catalogSize, indexed,
                stats.latencyReport(), elapsed, reopenTime, postReopenTotal);
    }

    /**
     * Adds and removes catalog entries, deliberately colliding on SKUs from a small pool so the
     * unique index is exercised under contention. Every rejection must leave the catalog, the
     * indexes and the id counter consistent.
     */
    private void catalogChurn(ZeroZDb bank, StressStats stats, int clientId) {
        BankRoot root = bank.root();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String sku = "SKU-" + rnd.nextInt(40);
        String category = "cat-" + rnd.nextInt(8);
        long start = System.nanoTime();
        try {
            bank.write(ctx -> {
                ctx.edit(root);
                ctx.edit(root.catalog);
                long id = root.nextProductId++;
                root.catalog.put(id, new StressProduct(id, sku, category));
            });
            stats.recordWrite(System.nanoTime() - start);
        } catch (UniqueConstraintException e) {
            stats.uniqueRejections.incrementAndGet();
        }

        if (rnd.nextInt(3) == 0) {
            bank.write(ctx -> {
                ctx.edit(root.catalog);
                root.catalog.keySet().stream().findFirst().ifPresent(root.catalog::remove);
            });
        }
    }

    /** Money moves between two accounts in one block: the sum must never change. */
    private void transfer(ZeroZDb bank, StressStats stats) {
        BankRoot root = bank.root();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long fromId = rnd.nextLong(ACCOUNTS);
        long toId = rnd.nextLong(ACCOUNTS);
        if (fromId == toId) {
            return;
        }
        long amount = rnd.nextLong(1, 500);
        long start = System.nanoTime();
        bank.write(ctx -> {
            Account from = root.accounts.get(fromId);
            Account to = root.accounts.get(toId);
            if (from.balanceCents < amount) {
                return;
            }
            ctx.edit(from);
            ctx.edit(to);
            from.balanceCents -= amount;
            to.balanceCents += amount;
        });
        stats.recordWrite(System.nanoTime() - start);
    }

    /** Readers must never observe a torn transfer: the total is invariant. */
    private void readAudit(ZeroZDb bank, StressStats stats, long expectedTotal) {
        BankRoot root = bank.root();
        long total = bank.read(() -> root.accounts.values().stream()
                .mapToLong(a -> a.balanceCents).sum());
        if (total != expectedTotal) {
            throw new AssertionError("Torn read: total " + total + " != " + expectedTotal);
        }
        stats.reads.incrementAndGet();
    }

    /** Contended single object via optimistic checking; retries until it lands. */
    private void bumpCounter(ZeroZDb bank, StressStats stats, AtomicLong committed) {
        BankRoot root = bank.root();
        for (int attempt = 0; attempt < 50; attempt++) {
            long baseline = bank.baseline(root.counter);
            try {
                long start = System.nanoTime();
                bank.write(ctx -> {
                    ctx.edit(root.counter);
                    root.counter.value++;
                    ctx.storeChecked(root.counter, baseline);
                });
                stats.recordWrite(System.nanoTime() - start);
                committed.incrementAndGet();
                return;
            } catch (StaleObjectException e) {
                stats.staleRetries.incrementAndGet();
            } catch (UniqueConstraintException e) {
                stats.uniqueRejections.incrementAndGet();
                return;
            }
        }
    }

    /** A transfer plus an audit-log entry in a second store, atomically intended. */
    private void crossStoreTransfer(ZeroZDb bank, ZeroZDb audit, StressStats stats, int clientId) {
        BankRoot bankRoot = bank.root();
        AuditRoot auditRoot = audit.root();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long fromId = rnd.nextLong(ACCOUNTS);
        long toId = rnd.nextLong(ACCOUNTS);
        if (fromId == toId) {
            return;
        }
        long amount = rnd.nextLong(1, 100);
        long start = System.nanoTime();
        CrossStoreWrite.run(ctx -> {
            Account from = bankRoot.accounts.get(fromId);
            Account to = bankRoot.accounts.get(toId);
            if (from.balanceCents < amount) {
                return;
            }
            ctx.on(bank).edit(from);
            ctx.on(bank).edit(to);
            from.balanceCents -= amount;
            to.balanceCents += amount;

            ctx.on(audit).edit(auditRoot.events);
            auditRoot.events.put("c" + clientId + "-" + System.nanoTime(),
                    fromId + "->" + toId + ":" + amount);
        }, bank, audit);
        stats.recordWrite(System.nanoTime() - start);
        stats.crossWrites.incrementAndGet();
    }

    public static void main(String[] args) throws Exception {
        int clients = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        com.zeroz4j.db.Durability mode = args.length > 2
                ? com.zeroz4j.db.Durability.valueOf(args[2])
                : com.zeroz4j.db.Durability.SYNC;
        Path base = Path.of("target", "stress-" + System.nanoTime());
        System.out.println("clients=" + clients + " seconds=" + seconds + " durability=" + mode);
        Result result = new StressHarness(base.resolve("bank"), base.resolve("audit"),
                clients, Duration.ofSeconds(seconds), mode).run();
        System.out.println(result);
        System.exit(result.healthy() ? 0 : 1);
    }
}
