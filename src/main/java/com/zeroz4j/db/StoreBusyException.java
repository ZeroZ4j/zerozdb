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
 * Thrown by {@link ZeroZDb#close(java.time.Duration)} when the store's write lock is still held
 * when the timeout expires — either a genuinely long write is in flight, or a write-block was
 * leaked and never ended.
 * <p>
 * The message names the thread holding it, and {@link #owner()} hands it over as an object so a
 * host can dump that thread's stack. This exists because the alternative — waiting for ever — is
 * an unclosable and therefore unreleasable store, with nothing in the log to say why.
 */
public class StoreBusyException extends IllegalStateException {

    private final transient Thread owner;

    public StoreBusyException(Thread owner, String message) {
        super(message);
        this.owner = owner;
    }

    /** The thread holding the store's write lock when the wait expired; null if it had none. */
    public Thread owner() {
        return owner;
    }
}
