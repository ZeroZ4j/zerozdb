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

/** Wire envelope: a result, or a faithfully-typed failure. */
public final class Response {

    public long id;
    public Object result;
    public String failureType;
    public String failureMessage;

    public Response() {
    }

    static Response ok(long id, Object result) {
        Response r = new Response();
        r.id = id;
        r.result = result;
        return r;
    }

    static Response failure(long id, Throwable t) {
        Response r = new Response();
        r.id = id;
        r.failureType = t.getClass().getName();
        r.failureMessage = t.getMessage();
        return r;
    }

    boolean failed() {
        return failureType != null;
    }
}
