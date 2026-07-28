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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive per-directory ownership of a store, backed by an OS file lock. The lock is released
 * by the OS even on unceremonious process death, so a crashed owner never wedges its store.
 * This is the local-disk rung of the ownership ladder; lease-based ownership across hosts is a
 * later SPI implementation.
 */
public final class StoreOwnership implements AutoCloseable {

    static final String LOCK_FILE_NAME = "zerozdb.lock";

    private final FileChannel channel;
    private final FileLock fileLock;
    private final Path directory;

    private StoreOwnership(FileChannel channel, FileLock fileLock, Path directory) {
        this.channel = channel;
        this.fileLock = fileLock;
        this.directory = directory;
    }

    public static StoreOwnership acquire(Path directory) {
        try {
            Files.createDirectories(directory);
            FileChannel channel = FileChannel.open(
                    directory.resolve(LOCK_FILE_NAME),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw new StoreOwnedException(directory, "another ZeroZDb in this JVM owns it");
            }
            if (lock == null) {
                channel.close();
                throw new StoreOwnedException(directory, "another process owns it");
            }
            return new StoreOwnership(channel, lock, directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to acquire ownership of store at '" + directory + "'", e);
        }
    }

    public Path directory() {
        return directory;
    }

    public void release() {
        try {
            fileLock.release();
        } catch (IOException ignored) {
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        release();
    }
}
