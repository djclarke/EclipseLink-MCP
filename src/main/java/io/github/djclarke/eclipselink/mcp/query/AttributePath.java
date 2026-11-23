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
import java.util.List;
import java.util.Set;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.expressions.Expression;
import org.eclipse.persistence.mappings.DatabaseMapping;

import io.github.djclarke.eclipselink.mcp.tools.ToolException;

/**
 * A dotted attribute path such as {@code customer.region}, checked against the mapped model. Every segment but
 * the last must be a mapped relationship; joins therefore only ever follow mappings.
 */
public record AttributePath(String text, List<DatabaseMapping> mappings) {

    public static AttributePath resolve(ClassDescriptor root, String text) {
        if (text == null || text.isBlank()) {
            throw new ToolException(ToolException.Code.UNKNOWN_ATTRIBUTE, "Empty attribute path");
        }
        List<DatabaseMapping> mappings = new ArrayList<>();
        ClassDescriptor current = root;
        String[] segments = text.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (current == null) {
                throw new ToolException(ToolException.Code.UNKNOWN_ATTRIBUTE,
                        "'" + text + "': '" + segments[i - 1] + "' is not a relationship, so it has no attributes");
            }
            DatabaseMapping mapping = current.getMappingForAttributeName(segments[i]);
            if (mapping == null || !Mappings.isSupported(mapping)) {
                throw new ToolException(ToolException.Code.UNKNOWN_ATTRIBUTE,
                        "'" + text + "': " + current.getAlias() + " has no attribute '" + segments[i] + "'");
            }
            mappings.add(mapping);
            current = mapping.isForeignReferenceMapping() ? mapping.getReferenceDescriptor() : null;
        }
        return new AttributePath(text, List.copyOf(mappings));
    }

    public DatabaseMapping last() {
        return mappings.get(mappings.size() - 1);
    }

    /** True if the path ends in a basic (column) attribute rather than a relationship. */
    public boolean isBasic() {
        return last().isDirectToFieldMapping();
    }

    public boolean traversesCollection() {
        return mappings.stream().anyMatch(DatabaseMapping::isCollectionMapping);
    }

    /** The Java type of the final attribute, for basic attributes. */
    public Class<?> type() {
        return Mappings.attributeType(last());
    }

    /**
     * Builds the EclipseLink expression for this path.
     *
     * @param outerJoins relationship paths (prefixes of this path, e.g. {@code customer}) to join with an outer join
     */
    public Expression toExpression(Expression base, Set<String> outerJoins) {
        Expression e = base;
        StringBuilder prefix = new StringBuilder();
        for (DatabaseMapping m : mappings) {
            if (!prefix.isEmpty()) {
                prefix.append('.');
            }
            prefix.append(m.getAttributeName());
            boolean outer = outerJoins.contains(prefix.toString());
            if (m.isCollectionMapping()) {
                e = outer ? e.anyOfAllowingNone(m.getAttributeName()) : e.anyOf(m.getAttributeName());
            } else if (m.isForeignReferenceMapping()) {
                e = outer ? e.getAllowingNull(m.getAttributeName()) : e.get(m.getAttributeName());
            } else {
                e = e.get(m.getAttributeName());
            }
        }
        return e;
    }
}
