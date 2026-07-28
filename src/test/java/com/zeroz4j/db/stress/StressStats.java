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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe op counters plus latency samples for percentile reporting. */
final class StressStats {

    final AtomicLong writes = new AtomicLong();
    final AtomicLong reads = new AtomicLong();
    final AtomicLong crossWrites = new AtomicLong();
    final AtomicLong staleRetries = new AtomicLong();
    final AtomicLong uniqueRejections = new AtomicLong();
    final AtomicLong unexpectedFailures = new AtomicLong();

    private final List<Long> writeNanos = Collections.synchronizedList(new ArrayList<>());

    void recordWrite(long nanos) {
        writes.incrementAndGet();
        if (writes.get() % 5 == 0) {
            writeNanos.add(nanos);
        }
    }

    String latencyReport() {
        List<Long> samples;
        synchronized (writeNanos) {
            samples = new ArrayList<>(writeNanos);
        }
        if (samples.isEmpty()) {
            return "no samples";
        }
        Collections.sort(samples);
        return String.format("p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms (n=%d)",
                ms(samples, 0.50), ms(samples, 0.95), ms(samples, 0.99), ms(samples, 1.0), samples.size());
    }

    private static double ms(List<Long> sorted, double q) {
        int index = (int) Math.min(sorted.size() - 1L, Math.round(q * (sorted.size() - 1)));
        return sorted.get(index) / 1_000_000.0;
    }
}
