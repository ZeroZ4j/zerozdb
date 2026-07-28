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
 * A checked store found the object was committed by someone else after the caller's baseline
 * was taken (typically: another user saved while this user had the edit form open). The whole
 * write-block aborts and rolls back; the app decides what to show the user.
 */
public class StaleObjectException extends IllegalStateException {

    /** Reconstruction from a remote failure; see {@code com.zeroz4j.db.net}. */
    public StaleObjectException(String message) {
        super(message);
    }

    public StaleObjectException(Object object, long expectedVersion, long actualVersion) {
        super("Stale edit of " + object.getClass().getName()
                + ": baseline version " + expectedVersion
                + " but committed version is " + actualVersion
                + " — the object changed after this edit began");
    }
}
