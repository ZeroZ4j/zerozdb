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

/** First frame each way: schema gate plus store inventory. */
public final class Handshake {

    public String schemaId;
    public String[] stores;
    public boolean accepted;
    public String rejectionReason;
    /** Shared secret, when the server requires authentication. Never logged. */
    public String secret;
    /** Per-store schema ids the client expects, overriding {@link #schemaId} for those stores. */
    public java.util.Map<String, String> storeSchemaIds;
    /** The client's model shape, enabling compatibility-based admittance. */
    public String descriptorText;
    /** Server → client: stores refused, with the reason, so the client can log it precisely. */
    public java.util.Map<String, String> refusedStores;

    public Handshake() {
    }

    Handshake(String schemaId) {
        this.schemaId = schemaId;
    }

    Handshake(String schemaId, String secret) {
        this.schemaId = schemaId;
        this.secret = secret;
    }
}
