/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.definition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named entity query from {@code queries/*.yaml} (docs/server-definition.md 2.5): a fixed filter over one root
 * entity plus the object graph to load and return. The graph is both the load plan (batch fetched, no lazy
 * loading per row) and the JSON shape of each item.
 *
 * @param graph attribute paths to load and return, e.g. {@code customer.name}, {@code lines.product.name}
 */
public record QueryDefinition(
        String name,
        String title,
        String description,
        String root,
        Map<String, Object> filter,
        List<ReportDefinition.Ordering> orderBy,
        LinkedHashMap<String, ReportDefinition.Parameter> parameters,
        List<String> graph) {

    public QueryDefinition {
        orderBy = orderBy == null ? List.of() : orderBy;
        parameters = parameters == null ? new LinkedHashMap<>() : parameters;
        graph = graph == null ? List.of() : graph;
    }

    /** Tool name for this query. */
    public String toolName() {
        return "query_" + name;
    }
}
