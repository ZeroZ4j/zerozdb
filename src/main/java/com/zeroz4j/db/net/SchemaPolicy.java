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
package com.zeroz4j.db.net;

/**
 * How strictly a client's model must match a store's.
 */
public enum SchemaPolicy {

    /**
     * Schema ids must be equal. Simple and blunt: during a blue/green switchover the new build
     * is refused even when its change is harmless.
     */
    EXACT,

    /**
     * Admit a client whose model differs from the store's only by <em>additions</em> — new
     * fields, new classes — as classified by
     * {@link com.zeroz4j.db.schema.SchemaCompatibility}. Removals, type changes and the
     * remove-plus-add pattern are still refused.
     * <p>
     * This is what lets two application versions run against one shared store during a rolling
     * release: both can read the data, because an added field is invisible to the older build
     * and defaulted for the newer one. Requires both sides to supply a
     * {@link com.zeroz4j.db.schema.SchemaDescriptor}; without one it falls back to {@link #EXACT}.
     * <p>
     * Safe because remote writes are commands executed on the server with the server's classes,
     * so an older client cannot write a record shaped by its older model.
     */
    ADDITIVE_COMPATIBLE
}
