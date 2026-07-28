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

/** Compiles alternative versions of the same domain class into separate directories. */
final class ClassVersions {

    static final String CLASS_NAME = "evolving.Product";

    /** Writes and compiles a {@code evolving.Product} with the given field declarations. */
    static Path compile(Path baseDir, String versionName, String fields) throws IOException {
        Path sourceDir = baseDir.resolve(versionName + "-src");
        Path classesDir = baseDir.resolve(versionName + "-classes");
        Path sourceFile = sourceDir.resolve("evolving").resolve("Product.java");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classesDir);

        Files.writeString(sourceFile, """
                package evolving;

                public class Product {
                %s
                    public Product() {
                    }
                }
                """.formatted(fields));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler; run tests on a JDK");
        }
        int result = compiler.run(null, null, null,
                "-d", classesDir.toString(), sourceFile.toString());
        if (result != 0) {
            throw new IllegalStateException("Failed to compile " + versionName);
        }
        return classesDir;
    }

    private ClassVersions() {
    }
}
