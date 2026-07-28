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
 * Fetches the store's whole root graph. The reply is a serialized copy — an independent object
 * graph in the caller's heap, not a live reference to the server's. Used by {@link ReplicaView}
 * to (re)build its local copy.
 */
public final class SnapshotQuery implements DbQuery<Object> {

    @Override
    public Object execute(Object root) {
        return root;
    }
}
