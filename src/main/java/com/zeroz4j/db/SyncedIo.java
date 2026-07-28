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

import org.eclipse.store.afs.nio.types.NioFileWrapper;
import org.eclipse.store.afs.nio.types.NioIoHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;

/**
 * Wraps a {@link NioIoHandler} so every {@code writeBytes} is followed by
 * {@code FileChannel.force(false)} (fdatasync semantics — same call EclipseStore's own unused
 * {@code XIO.appendAllGuaranteed} makes). This is what upgrades commit durability from
 * "survives process death" to "survives power loss". Reflection proxy overhead is noise next to
 * the fsync itself, which only happens on the write path.
 */
final class SyncedIo {

    static NioIoHandler wrap(NioIoHandler delegate) {
        return (NioIoHandler) Proxy.newProxyInstance(
                NioIoHandler.class.getClassLoader(),
                new Class<?>[]{NioIoHandler.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if ("writeBytes".equals(method.getName())
                            && args != null && args.length > 0
                            && args[0] instanceof NioFileWrapper file) {
                        FileChannel channel = file.fileChannel();
                        if (channel != null && channel.isOpen()) {
                            try {
                                channel.force(false);
                            } catch (IOException e) {
                                throw new UncheckedIOException("fsync after storage write failed", e);
                            }
                        }
                    }
                    return result;
                });
    }

    private SyncedIo() {
    }
}
