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

/**
 * A store's model version, declared per store rather than per server — so one daemon can serve
 * {@code root} at one version and {@code templates} at another, and an app release that changes
 * only one of them does not lock clients out of the others.
 *
 * @param schemaId   coarse version label, compared for equality when no descriptor is available
 * @param descriptor the store's persistent shape; when both sides supply one, admittance can be
 *                   decided by actual compatibility instead of string equality
 */
public record StoreSchema(String schemaId, SchemaDescriptor descriptor) {

    public static StoreSchema of(String schemaId) {
        return new StoreSchema(schemaId, null);
    }

    public static StoreSchema of(String schemaId, SchemaDescriptor descriptor) {
        return new StoreSchema(schemaId, descriptor);
    }
}
