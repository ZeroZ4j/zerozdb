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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Builds {@link SSLContext}s from keystore files, so the server and client can be wired for TLS
 * without pulling in a crypto library.
 * <p>
 * TLS protects the wire; the shared secret authenticates the caller. Over any non-loopback
 * interface you want both — the secret alone crosses the network in the clear.
 */
public final class Tls {

    /** Context that presents {@code keystore} as the server's identity. */
    public static SSLContext server(Path keystore, char[] password) {
        try {
            KeyStore store = load(keystore, password);
            KeyManagerFactory keyManagers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build server TLS context from " + keystore, e);
        }
    }

    /** Context that trusts the certificates in {@code truststore}. */
    public static SSLContext client(Path truststore, char[] password) {
        try {
            KeyStore store = load(truststore, password);
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build client TLS context from " + truststore, e);
        }
    }

    private static KeyStore load(Path file, char[] password) throws GeneralSecurityException {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(in, password);
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read keystore " + file, e);
        }
    }

    private Tls() {
    }
}
