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

import com.zeroz4j.db.schema.SchemaDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Everything a client presents at connect: identity, credentials and the model it speaks. */
public final class ConnectOptions {

    String schemaId = "default";
    String secret;
    javax.net.ssl.SSLContext sslContext;
    int timeoutMillis = 10_000;
    final Map<String, String> storeSchemaIds = new LinkedHashMap<>();
    SchemaDescriptor descriptor;

    public static ConnectOptions create() {
        return new ConnectOptions();
    }

    /** Default model version this client speaks. */
    public ConnectOptions schemaId(String schemaId) {
        this.schemaId = schemaId;
        return this;
    }

    /** Model version for one specific store, overriding {@link #schemaId}. */
    public ConnectOptions storeSchemaId(String store, String schemaId) {
        storeSchemaIds.put(store, schemaId);
        return this;
    }

    /**
     * The shape of this client's classes. Supplying it lets a server running
     * {@link SchemaPolicy#ADDITIVE_COMPATIBLE} admit this client even when the version labels
     * differ, provided the difference is only additions — the rolling-release case.
     */
    public ConnectOptions descriptor(SchemaDescriptor descriptor) {
        this.descriptor = descriptor;
        return this;
    }

    public ConnectOptions secret(String secret) {
        this.secret = secret;
        return this;
    }

    public ConnectOptions tls(javax.net.ssl.SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    public ConnectOptions timeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }
}
