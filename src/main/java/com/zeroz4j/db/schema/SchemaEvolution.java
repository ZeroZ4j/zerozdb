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
package com.zeroz4j.db.schema;

import org.eclipse.serializer.persistence.types.PersistenceMemberMatchingProvider;
import org.eclipse.serializer.persistence.types.PersistenceRefactoringMappingProvider;
import org.eclipse.serializer.persistence.types.PersistenceTypeDefinitionMember;
import org.eclipse.serializer.util.similarity.MatchValidator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How stored data is mapped onto classes that have changed shape since it was written.
 * <p>
 * EclipseStore matches a legacy field to a current field by name first, then falls back to a
 * <em>similarity</em> heuristic for whatever is left over. That fallback is convenient — a
 * renamed field keeps its data with no configuration — and it is also a live hazard: remove one
 * field and add an unrelated one of the same type in the same release, and the old value is
 * silently carried into the new field. No error, no log, wrong data. (Demonstrated in
 * {@code SchemaEvolutionTest}.)
 * <p>
 * So zeroz4j-db defaults to {@link #strict()}: heuristic matches are refused, and a field whose
 * name disappeared simply arrives unset. Renames are then something you <em>declare</em>:
 *
 * <pre>{@code
 * SchemaEvolution.strict()
 *     .rename("com.example.Product#sku", "com.example.Product#productCode")
 * }</pre>
 *
 * This is a deliberate divergence from EclipseStore's default, in the direction of "wrong data
 * is worse than missing data". {@link #lenient()} restores the original behaviour.
 */
public final class SchemaEvolution {

    private final boolean strictFieldMatching;
    private final Map<String, String> renames = new LinkedHashMap<>();

    private SchemaEvolution(boolean strictFieldMatching) {
        this.strictFieldMatching = strictFieldMatching;
    }

    /** Refuse heuristic field matching; unmatched legacy fields are dropped. The default. */
    public static SchemaEvolution strict() {
        return new SchemaEvolution(true);
    }

    /** EclipseStore's own behaviour: unmatched fields may be paired by type similarity. */
    public static SchemaEvolution lenient() {
        return new SchemaEvolution(false);
    }

    /**
     * Declares that a field (or type) moved. Identifiers are
     * {@code fully.qualified.Type#fieldName} for fields and {@code fully.qualified.Type} for
     * types, naming the <em>old</em> shape on the left.
     */
    public SchemaEvolution rename(String oldIdentifier, String newIdentifier) {
        renames.put(oldIdentifier, newIdentifier);
        return this;
    }

    public boolean isStrict() {
        return strictFieldMatching;
    }

    public Map<String, String> renames() {
        return Map.copyOf(renames);
    }

    /** Applies this policy to a storage foundation. */
    public void applyTo(org.eclipse.store.storage.embedded.types.EmbeddedStorageFoundation<?> foundation) {
        foundation.onConnectionFoundation(connectionFoundation -> {
            if (!renames.isEmpty()) {
                java.util.List<org.eclipse.serializer.typing.KeyValue<String, String>> entries =
                        renames.entrySet().stream()
                                .map(e -> org.eclipse.serializer.typing.KeyValue.New(
                                        e.getKey(), e.getValue()))
                                .map(kv -> (org.eclipse.serializer.typing.KeyValue<String, String>) kv)
                                .toList();
                connectionFoundation.setRefactoringMappingProvider(
                        PersistenceRefactoringMappingProvider.New(entries));
            }
            if (strictFieldMatching) {
                connectionFoundation.setLegacyMemberMatchingProvider(new StrictMatching());
            }
        });
    }

    /**
     * Accepts a legacy-to-current field match only when the names are identical. The validator
     * is consulted for <em>every</em> candidate pairing, not just heuristic leftovers, so it
     * must let same-name pairs through — rejecting everything would discard even unchanged
     * fields (learned the hard way; see {@code SchemaEvolutionTest}).
     */
    private static final class StrictMatching implements PersistenceMemberMatchingProvider {

        @Override
        public MatchValidator<PersistenceTypeDefinitionMember> provideMemberMatchValidator() {
            return (source, target, similarity, sourceCandidates, targetCandidates) ->
                    source != null && target != null
                            && source.name() != null
                            && source.name().equals(target.name());
        }
    }
}
