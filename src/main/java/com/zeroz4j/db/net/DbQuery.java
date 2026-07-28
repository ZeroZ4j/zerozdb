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
 * A read executed on the server inside a read-block, returning a serializable result.
 * <p>
 * Return <em>values</em> (counts, DTOs, copies), not live graph nodes: whatever you return is
 * serialized to the client, so returning a deeply-connected entity ships its reachable graph.
 *
 * @param <R> result type
 */
public interface DbQuery<R> {

    R execute(Object root);
}
