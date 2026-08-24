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
package com.zeroz4j.db.evolution;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compiles alternative versions of an enum and a class that holds one, into separate directories.
 * <p>
 * The enum is a separate top-level type on purpose: a nested enum reports {@code null} from
 * {@code Class#getEnumConstants} on some JVMs, which EclipseStore has to special-case, and that
 * would be testing the special case rather than the ordinary one.
 */
final class EnumVersions {

    static final String ENUM_NAME = "evolving.Status";
    static final String HOLDER_NAME = "evolving.Holder";

    /**
     * Writes and compiles {@code evolving.Status} with the given constants in the given order,
     * plus an {@code evolving.Holder} that references one of them.
     *
     * @param constants the constant names in declaration order, comma-separated as they appear in
     *                  source — the order <em>is</em> the thing under test.
     */
    static Path compile(Path baseDir, String versionName, String constants) throws IOException {
        Path sourceDir = baseDir.resolve(versionName + "-src");
        Path classesDir = baseDir.resolve(versionName + "-classes");
        Path packageDir = sourceDir.resolve("evolving");
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path enumFile = packageDir.resolve("Status.java");
        Path holderFile = packageDir.resolve("Holder.java");

        Files.writeString(enumFile, """
                package evolving;

                public enum Status {
                    %s
                }
                """.formatted(constants));

        Files.writeString(holderFile, """
                package evolving;

                public class Holder {
                    public String id;
                    public Status status;

                    public Holder() {
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler; run tests on a JDK");
        }
        int result = compiler.run(null, null, null,
                "-d", classesDir.toString(), enumFile.toString(), holderFile.toString());
        if (result != 0) {
            throw new IllegalStateException("Failed to compile " + versionName);
        }
        return classesDir;
    }

    private EnumVersions() {
    }
}
