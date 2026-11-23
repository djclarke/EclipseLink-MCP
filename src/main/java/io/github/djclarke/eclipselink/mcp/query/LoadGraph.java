/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.persistence.annotations.BatchFetchType;
import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.expressions.Expression;
import org.eclipse.persistence.expressions.ExpressionBuilder;
import org.eclipse.persistence.queries.ObjectLevelReadQuery;

/**
 * The relationships a set of attribute paths needs loaded, applied to a query as EclipseLink batch reading
 * (docs/server-definition.md 4.8). With {@link BatchFetchType#IN} each relationship level costs one extra SQL
 * statement for the whole page, whatever the page size, and it stays correct with paging (unlike JOIN batch
 * fetching, which repeats the root query without its row limits).
 *
 * <p>Example: paths {@code customer.name}, {@code lines.quantity}, {@code lines.product.name} give the
 * relationships {@code customer}, {@code lines}, {@code lines.product}: four statements per page in all.
 */
public final class LoadGraph {

    private static final LoadGraph EMPTY = new LoadGraph(List.of());

    private final List<String> relationships;

    private LoadGraph(List<String> relationships) {
        this.relationships = relationships;
    }

    /** Every relationship prefix of the given paths, in order. Paths are validated against {@code root}. */
    public static LoadGraph of(ClassDescriptor root, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return EMPTY;
        }
        Set<String> prefixes = new LinkedHashSet<>();
        for (String text : paths) {
            AttributePath path = AttributePath.resolve(root, text);
            String[] segments = text.split("\\.");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < path.mappings().size(); i++) {
                if (i > 0) {
                    prefix.append('.');
                }
                prefix.append(segments[i]);
                if (path.mappings().get(i).isForeignReferenceMapping()) {
                    prefixes.add(prefix.toString());
                }
            }
        }
        return new LoadGraph(List.copyOf(prefixes));
    }

    /** Relationship paths that will be batch read, e.g. {@code [customer, lines, lines.product]}. */
    public List<String> relationships() {
        return relationships;
    }

    public boolean isEmpty() {
        return relationships.isEmpty();
    }

    /**
     * Adds batch reading for every relationship to a query whose base is {@code builder}.
     *
     * @param batchSize ids per IN list; use at least the page size so each level is one statement
     */
    public void applyTo(ObjectLevelReadQuery query, ExpressionBuilder builder, int batchSize) {
        if (relationships.isEmpty()) {
            return;
        }
        query.setBatchFetchType(BatchFetchType.IN);
        query.setBatchFetchSize(batchSize);
        for (String r : relationships) {
            Expression e = builder;
            for (String segment : r.split("\\.")) {
                e = e.get(segment);
            }
            query.addBatchReadAttribute(e);
        }
    }

    /** All paths from a list plus nested to-one relationships, used for depth-based default output. */
    public static List<String> toOnePaths(ClassDescriptor root, int depth) {
        List<String> out = new ArrayList<>();
        collectToOne(root, "", depth, out);
        return out;
    }

    private static void collectToOne(ClassDescriptor d, String prefix, int depth, List<String> out) {
        if (depth <= 0) {
            return;
        }
        for (var m : Mappings.of(d)) {
            if (m.isForeignReferenceMapping() && !m.isCollectionMapping()) {
                String p = prefix + m.getAttributeName();
                out.add(p);
                collectToOne(m.getReferenceDescriptor(), p + ".", depth - 1, out);
            }
        }
    }
}
