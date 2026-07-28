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
 * A write-block held open across method calls, for host frameworks whose API is
 * {@code begin()} … {@code commit()} rather than a lambda — JDO/JPA-style persistence managers,
 * for instance, where thousands of call sites already exist and rewriting them would be a far
 * larger and riskier change than adapting underneath them.
 * <p>
 * Semantics are identical to {@link ZeroZDb#write}: the store's write lock is held from
 * {@link ZeroZDb#beginWrite()} until {@link #commit()} or {@link #rollback()}, so no reader ever
 * observes a half-applied change, and everything enlisted flushes in one atomic durable commit.
 * <p>
 * <strong>Prefer {@link ZeroZDb#write} where you can.</strong> A block cannot be leaked; a
 * transaction can. Always use try-with-resources — {@link #close()} rolls back an unfinished
 * transaction rather than leaving the store's write lock held forever.
 * <p>
 * <strong>Nesting.</strong> Beginning a transaction while one is already active on this thread
 * joins the outer one: the inner {@code commit()} does nothing and the outer decides. An inner
 * {@code rollback()} marks the whole transaction rollback-only, so the outer's commit fails
 * loudly instead of persisting work the inner already disowned.
 */
public final class WriteTransaction implements AutoCloseable {

    private final ZeroZDb db;
    private final WriteContextImpl context;
    private final boolean outermost;
    private boolean finished;

    WriteTransaction(ZeroZDb db, WriteContextImpl context, boolean outermost) {
        this.db = db;
        this.context = context;
        this.outermost = outermost;
    }

    /** Enlist changed objects here — same contract as inside a write-block. */
    public WriteContext context() {
        return context;
    }

    public boolean isActive() {
        return !finished;
    }

    /** True when this transaction owns the commit; false when it joined an outer one. */
    public boolean isOutermost() {
        return outermost;
    }

    /**
     * Flushes every enlisted object in one atomic durable commit and releases the write lock.
     * A nested transaction commits nothing — the outermost one does.
     *
     * @throws IllegalStateException if this transaction was marked rollback-only by a nested
     *                               rollback
     */
    public void commit() {
        if (finished) {
            throw new IllegalStateException("Transaction already finished");
        }
        try {
            if (outermost) {
                if (context.rollbackOnly) {
                    context.rollback();
                    throw new IllegalStateException(
                            "Transaction is rollback-only: a nested transaction rolled back, so "
                                    + "committing here would persist work that was disowned");
                }
                db.finishCommit(context);
            }
        } finally {
            finished = true;
            db.endTransaction(context, outermost);
        }
    }

    /** Restores every enlisted object from its before-image and releases the write lock. */
    public void rollback() {
        if (finished) {
            throw new IllegalStateException("Transaction already finished");
        }
        try {
            if (outermost) {
                context.rollback();
            } else {
                context.rollbackOnly = true;
            }
        } finally {
            finished = true;
            db.endTransaction(context, outermost);
        }
    }

    /** Rolls back if still open, so a leaked transaction cannot hold the write lock forever. */
    @Override
    public void close() {
        if (!finished) {
            rollback();
        }
    }
}
