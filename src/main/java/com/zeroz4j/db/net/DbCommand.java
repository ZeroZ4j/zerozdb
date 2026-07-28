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

import com.zeroz4j.db.WriteContext;

/**
 * A unit of work executed <em>on the server</em>, inside one atomic durable write-block.
 * <p>
 * Remote writes are command objects rather than lambdas because the executing JVM must have the
 * code: the command class lives in the domain jar both sides load. Keep commands small and
 * self-contained — they carry only their parameters over the wire, never object references.
 *
 * @param <R> result type, serialized back to the caller
 */
public interface DbCommand<R> {

    /**
     * @param ctx  the server-side write context — enlist changed objects with
     *             {@link WriteContext#store} / {@link WriteContext#edit}
     * @param root the store's persistent root, live on the server
     */
    R execute(WriteContext ctx, Object root);
}
