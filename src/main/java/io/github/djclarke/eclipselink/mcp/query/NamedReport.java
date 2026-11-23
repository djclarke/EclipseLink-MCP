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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.expressions.Expression;
import org.eclipse.persistence.expressions.ExpressionBuilder;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.queries.ReportQuery;
import org.eclipse.persistence.queries.ReportQueryResult;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.ReportDefinition;
import io.github.djclarke.eclipselink.mcp.json.EntityJson;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;

/**
 * A named report checked against the model at startup and run as an EclipseLink {@link ReportQuery}: one SQL
 * statement returning only the selected values, no entities and no cache use. Callers supply parameter values
 * and paging only (requirements 4.5).
 */
public final class NamedReport {

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

    private final ReportDefinition definition;
    private final ClassDescriptor root;
    private final Set<String> outerJoins;
    private final List<OutputColumn> columns;

    /** One result column: name, JSON Schema type, and the attribute path it reads. */
    public record OutputColumn(String name, Map<String, Object> schema, AttributePath path, String aggregate) {
    }

    private NamedReport(ReportDefinition definition, ClassDescriptor root, Set<String> outerJoins,
                        List<OutputColumn> columns) {
        this.definition = definition;
        this.root = root;
        this.outerJoins = outerJoins;
        this.columns = columns;
    }

    /** Validates the report against the model (requirements FR-22). */
    public static NamedReport compile(ReportDefinition def, ModelRuntime model) {
        String where = "Report '" + def.name() + "': ";
        ClassDescriptor root = model.entity(def.root());
        if (root == null) {
            throw new DefinitionException(where + "unknown root entity '" + def.root() + "'");
        }
        try {
            Set<String> outer = new HashSet<>();
            for (Map.Entry<String, String> j : def.joins().entrySet()) {
                AttributePath p = AttributePath.resolve(root, j.getKey());
                if (p.isBasic()) {
                    throw new DefinitionException(where + "join '" + j.getKey() + "' is not a relationship");
                }
                if ("outer".equalsIgnoreCase(j.getValue())) {
                    outer.add(j.getKey());
                } else if (!"inner".equalsIgnoreCase(j.getValue())) {
                    throw new DefinitionException(where + "join type must be inner or outer, not " + j.getValue());
                }
            }

            List<OutputColumn> columns = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (ReportDefinition.Column c : def.select()) {
                if (!names.add(c.as())) {
                    throw new DefinitionException(where + "duplicate column name '" + c.as() + "'");
                }
                String agg = c.aggregate();
                if (agg != null && !AGGREGATES.contains(agg)) {
                    throw new DefinitionException(where + "unknown aggregate '" + agg + "'; use " + AGGREGATES);
                }
                if (c.path() == null) {
                    throw new DefinitionException(where + "column '" + c.as() + "' needs a path");
                }
                AttributePath p = AttributePath.resolve(root, c.path());
                if (!p.isBasic()) {
                    throw new DefinitionException(where + "column '" + c.as() + "' path '" + c.path()
                            + "' is a relationship; select one of its attributes");
                }
                Map<String, Object> schema = switch (agg == null ? "" : agg) {
                    case "count" -> Map.of("type", "integer");
                    case "avg" -> Map.of("type", "number");
                    // A sum keeps its operand's kind: integers, exact decimals (strings) or floating point.
                    case "sum" -> {
                        Map<String, Object> base = Mappings.jsonSchema(p.type());
                        yield base.get("type").equals("integer") || "decimal".equals(base.get("format"))
                                ? base : Map.of("type", "number");
                    }
                    default -> Mappings.jsonSchema(p.last());
                };
                columns.add(new OutputColumn(c.as(), schema, p, agg));
            }

            for (String g : def.groupBy()) {
                if (!AttributePath.resolve(root, g).isBasic()) {
                    throw new DefinitionException(where + "groupBy '" + g + "' must be a basic attribute");
                }
            }
            for (ReportDefinition.Ordering o : def.orderBy()) {
                if (columns.stream().noneMatch(col -> col.name().equals(o.by()))) {
                    throw new DefinitionException(where + "orderBy '" + o.by() + "' must name a select column");
                }
            }
            Parameters.checkTypes(def.parameters(), where);

            NamedReport report = new NamedReport(def, root, outer, List.copyOf(columns));
            // Build the full query once with placeholder values, checking the filter and that every declared
            // parameter is used.
            Parameters.UsageCheck check = Parameters.usageCheck(def.parameters(), where);
            report.buildQuery(check);
            check.assertAllUsed();
            return report;
        } catch (ToolException e) {
            throw new DefinitionException(where + e.getMessage(), e);
        }
    }

    public ReportDefinition definition() {
        return definition;
    }

    public List<OutputColumn> columns() {
        return columns;
    }

    /** JSON Schema for the tool input: the report's parameters plus paging. */
    public Map<String, Object> inputSchema(int maxPageSize) {
        return Parameters.inputSchema(definition.parameters(), maxPageSize);
    }

    /** Runs the report. Returns rows of named columns; one extra row is fetched to detect another page. */
    public List<Map<String, Object>> run(EntityManager em, Map<String, Object> args, int offset, int limit) {
        ReportQuery query = buildQuery(Parameters.fromArgs(definition.parameters(), args, "report"));
        List<?> raw = JpaHelper.createQuery(query, em).setFirstResult(offset).setMaxResults(limit).getResultList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object r : raw) {
            Object[] values = r instanceof ReportQueryResult rqr ? rqr.getResults().toArray()
                    : r instanceof Object[] a ? a : new Object[] {r};
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i).name(), EntityJson.value(values[i]));
            }
            rows.add(row);
        }
        return rows;
    }

    private ReportQuery buildQuery(Filters.ParameterValues params) {
        ExpressionBuilder eb = new ExpressionBuilder();
        ReportQuery q = new ReportQuery(root.getJavaClass(), eb);
        Map<String, Expression> byName = new HashMap<>();
        for (OutputColumn c : columns) {
            Expression e = c.path().toExpression(eb, outerJoins);
            if (c.aggregate() == null) {
                q.addAttribute(c.name(), e);
            } else {
                switch (c.aggregate()) {
                    case "count" -> { e = e.count(); q.addAttribute(c.name(), e, Long.class); }
                    case "sum" -> { e = e.sum(); q.addAttribute(c.name(), e); }
                    case "avg" -> { e = e.average(); q.addAttribute(c.name(), e, Double.class); }
                    case "min" -> { e = e.minimum(); q.addAttribute(c.name(), e); }
                    case "max" -> { e = e.maximum(); q.addAttribute(c.name(), e); }
                    default -> throw new IllegalStateException(c.aggregate());
                }
            }
            byName.put(c.name(), e);
        }
        for (String g : definition.groupBy()) {
            q.addGrouping(AttributePath.resolve(root, g).toExpression(eb, outerJoins));
        }
        for (ReportDefinition.Ordering o : definition.orderBy()) {
            Expression e = byName.get(o.by());
            q.addOrdering(o.descending() ? e.descending() : e.ascending());
        }
        Expression where = Filters.toExpression(definition.filter(), root, eb, outerJoins, params, new ArrayList<>());
        if (where != null) {
            q.setSelectionCriteria(where);
        }
        return q;
    }
}
