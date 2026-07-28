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

import java.nio.file.Path;

/**
 * Thrown when opening a store directory that another process (or this JVM, via another
 * {@link ZeroZDb} instance) already owns. Exactly one owner per store, always.
 */
public class StoreOwnedException extends IllegalStateException {

    public StoreOwnedException(Path directory, String detail) {
        super("Store at '" + directory + "' cannot be opened: " + detail);
    }
}
