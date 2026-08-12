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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.ref.Cleaner;
import java.util.function.Consumer;

/**
 * Warns when a {@link WriteTransaction} is garbage-collected without having been committed,
 * rolled back or closed. This is what connection pools do about a leaked connection, for the same
 * reason: an API shaped {@code begin()} … {@code commit()} can be leaked, and the leak is
 * invisible until something later needs what it holds.
 * <p>
 * Nothing here can repair the leak — a {@code ReentrantReadWriteLock} write hold can only be
 * released by the thread that took it, and that thread has forgotten the transaction exists. The
 * value is entirely in the message, which names the thread and, with origin tracing on, the line
 * that opened the block.
 * <p>
 * Only {@link ZeroZDb#beginWrite()} pays for any of this: the {@link Cleaner} and its thread are
 * created on first use, so a consumer that only uses write-blocks — which cannot be leaked — never
 * creates one.
 */
final class WriteBlockLeakWatch implements Runnable {

    private static final Logger LOG = System.getLogger(WriteBlockLeakWatch.class.getName());

    /** Swapped by tests, which need to observe the report without installing a LoggerFinder. */
    static volatile Consumer<String> reporter = message -> LOG.log(Level.WARNING, message);

    /** Holder idiom: the Cleaner's thread starts on the first transaction, not on class load. */
    private static final class CleanerHolder {
        static final Cleaner INSTANCE = Cleaner.create();

        private CleanerHolder() {
        }
    }

    private final String store;
    private final WriteHold origin;
    private volatile boolean ended;
    private volatile Cleaner.Cleanable registration;

    private WriteBlockLeakWatch(String store, WriteHold origin) {
        this.store = store;
        this.origin = origin;
    }

    /**
     * Watches {@code subject} — the transaction — for collection. The watch must not reference it,
     * or it could never be collected in the first place, so everything the message needs is copied
     * out now.
     */
    static WriteBlockLeakWatch watch(Object subject, String store, WriteHold origin) {
        WriteBlockLeakWatch watch = new WriteBlockLeakWatch(store, origin);
        watch.registration = CleanerHolder.INSTANCE.register(subject, watch);
        return watch;
    }

    /** The transaction finished properly: no warning, and drop the registration with it. */
    void ended() {
        ended = true;
        Cleaner.Cleanable current = registration;
        if (current != null) {
            current.clean();
        }
    }

    @Override
    public void run() {
        if (ended) {
            return;
        }
        reporter.accept("Leaked write transaction on store '" + store + "': " + origin.describe()
                + " was garbage-collected without commit(), rollback() or close(). Its write lock "
                + "can only be released by the thread that took it, so this store can no longer be "
                + "written to and ZeroZDb.close() on it will time out. Wrap beginWrite() in "
                + "try-with-resources. " + origin.origin());
    }
}
