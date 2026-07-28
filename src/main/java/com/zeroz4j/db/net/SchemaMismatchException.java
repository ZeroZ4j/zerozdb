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
 * The client's schema id does not match the server's, so the connection was refused.
 * <p>
 * This strictness is deliberate: a client running older domain classes can silently drop fields
 * it does not know about. Failing loudly at connect turns a data-loss bug into a deploy-time
 * error. Upgrade the server first, then the clients.
 */
public class SchemaMismatchException extends RuntimeException {

    public SchemaMismatchException(String clientSchema, String serverSchema) {
        super("Schema mismatch: client '" + clientSchema + "' vs server '" + serverSchema
                + "'. Upgrade the server first, then clients.");
    }
}
