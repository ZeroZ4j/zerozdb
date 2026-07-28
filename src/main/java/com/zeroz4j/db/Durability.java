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

/**
 * What "the write returned" guarantees about disk state.
 * <p>
 * Verified against EclipseStore 4.1.0 sources: its NIO write path ({@code NioIoHandler} →
 * {@code XIO.write}) never calls {@code FileChannel.force}, so plain EclipseStore commits are
 * durable against process death (OS page cache survives) but NOT against power loss or kernel
 * crash. {@link #SYNC} closes that gap by forcing the channel after every storage write.
 */
public enum Durability {

    /**
     * Default. Every storage write is followed by {@code FileChannel.force(false)} — an
     * acknowledged write-block survives power loss. Costs roughly one fsync latency
     * (~0.5–5 ms on SSDs) per storage file touched per commit.
     */
    SYNC,

    /**
     * EclipseStore's native behavior: writes land in the OS page cache and the OS flushes on
     * its own schedule. Survives process kill; a power cut can lose the last seconds of
     * acknowledged commits. Appropriate for bulk imports and rebuildable data.
     */
    OS_BUFFERED
}
