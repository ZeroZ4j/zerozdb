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

/**
 * Who took a store's write lock, when, and how — the answer to the only question worth asking
 * when a write will not finish or a store will not close. A store keeps at most one of these at a
 * time, because at most one thread can hold the write lock.
 * <p>
 * The thread alone is usually enough to identify the culprit. The stack that opened the block is
 * the difference between "somebody leaked a write-block" and "this line leaked it", so it can be
 * captured too — off by default, because it costs roughly a microsecond per block and most
 * consumers never need it.
 */
final class WriteHold {

    static final String BLOCK = "write-block";
    static final String TRANSACTION = "write transaction";
    static final String CROSS_STORE = "cross-store write";
    static final String INDEX = "index registration";

    static final String TRACE_PROPERTY = "zerozdb.traceWriteBlockOrigins";

    private static volatile boolean traceOrigins = Boolean.getBoolean(TRACE_PROPERTY);

    private final Thread thread;
    private final String kind;
    private final long startedNanos;
    /** Null unless origin tracing is on. */
    private final StackTraceElement[] openedAt;

    WriteHold(String kind) {
        this.thread = Thread.currentThread();
        this.kind = kind;
        this.startedNanos = System.nanoTime();
        this.openedAt = traceOrigins ? thread.getStackTrace() : null;
    }

    static void traceOrigins(boolean enabled) {
        traceOrigins = enabled;
    }

    static boolean isTracingOrigins() {
        return traceOrigins;
    }

    Thread thread() {
        return thread;
    }

    /** Reads as {@code a write transaction opened by thread "main" (id 1) 1.4 s ago}. */
    String describe() {
        return "a " + kind + " opened by thread \"" + thread.getName() + "\" (id "
                + thread.threadId() + ") " + age() + " ago";
    }

    /**
     * The stack that opened the block, formatted for a log line — or, when tracing is off, how to
     * turn it on. Never null, because the caller is already writing a failure message and an
     * absent origin is itself worth saying.
     */
    String origin() {
        if (openedAt == null) {
            return "The stack that opened it was not captured; run with -D" + TRACE_PROPERTY
                    + "=true to capture it.";
        }
        StringBuilder text = new StringBuilder("It was opened at:");
        int shown = 0;
        for (StackTraceElement frame : openedAt) {
            if (shown == 0 && isPlumbing(frame)) {
                continue;
            }
            text.append(System.lineSeparator()).append("\tat ").append(frame);
            if (++shown == 20) {
                text.append(System.lineSeparator()).append("\t...");
                break;
            }
        }
        return text.toString();
    }

    /** Leading frames of the capture itself say nothing about the caller that took the lock. */
    private static boolean isPlumbing(StackTraceElement frame) {
        String className = frame.getClassName();
        return className.equals(Thread.class.getName())
                || className.equals(WriteHold.class.getName())
                || className.equals(ZeroZDb.class.getName());
    }

    private String age() {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
        return millis >= 1000L
                ? String.format("%.1f s", millis / 1000.0)
                : millis + " ms";
    }
}
