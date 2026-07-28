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
package com.zeroz4j.db.example;

import java.util.HashMap;
import java.util.Map;

/**
 * Example persistent root: a product catalog with an id counter. This is deliberately the shape
 * of zeroz4j's original {@code ProductServiceImpl} bug — where {@code store(products)} and
 * {@code store(root)} were two independent commits and a crash between them persisted the
 * product but lost the {@code nextId} bump. Under ZeroZ DB both changes ride one write-block
 * and that bug is unwritable.
 */
public class ShopRoot {
    public long nextId = 1;
    public final Map<Long, Product> products = new HashMap<>();
}
