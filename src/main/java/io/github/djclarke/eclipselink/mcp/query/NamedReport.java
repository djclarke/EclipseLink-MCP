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

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private static final Set<String> PARAM_TYPES = Set.of("string", "integer", "decimal", "date", "boolean");

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
                    case "sum" -> Mappings.jsonSchema(p.type()).get("type").equals("integer")
                            ? Map.of("type", "integer") : Map.of("type", "number");
                    default -> Mappings.jsonSchema(p.type());
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
            for (Map.Entry<String, ReportDefinition.Parameter> p : def.parameters().entrySet()) {
                if (!PARAM_TYPES.contains(p.getValue().type())) {
                    throw new DefinitionException(where + "parameter '" + p.getKey() + "' has unknown type '"
                            + p.getValue().type() + "'; use " + PARAM_TYPES);
                }
            }

            NamedReport report = new NamedReport(def, root, outer, List.copyOf(columns));
            // Build the full query once with placeholder values, checking the filter and that every declared
            // parameter is used.
            Set<String> used = new HashSet<>();
            report.buildQuery(name -> {
                if (!def.parameters().containsKey(name)) {
                    throw new DefinitionException(where + "filter uses undeclared parameter '" + name + "'");
                }
                used.add(name);
                return sample(def.parameters().get(name).type());
            });
            for (String declared : def.parameters().keySet()) {
                if (!used.contains(declared)) {
                    throw new DefinitionException(where + "parameter '" + declared + "' is declared but not used");
                }
            }
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
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, ReportDefinition.Parameter> e : definition.parameters().entrySet()) {
            ReportDefinition.Parameter p = e.getValue();
            Map<String, Object> s = new LinkedHashMap<>();
            switch (p.type()) {
                case "integer" -> s.put("type", "integer");
                case "decimal" -> s.put("type", "number");
                case "boolean" -> s.put("type", "boolean");
                case "date" -> {
                    s.put("type", "string");
                    s.put("format", "date");
                }
                default -> s.put("type", "string");
            }
            if (p.description() != null) {
                s.put("description", p.description());
            }
            props.put(e.getKey(), s);
            if (p.required()) {
                required.add(e.getKey());
            }
        }
        props.put("pageSize", Map.of("type", "integer", "minimum", 1, "maximum", maxPageSize));
        props.put("cursor", Map.of("type", "string", "description", "nextCursor from a previous page"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Runs the report. Returns rows of named columns; one extra row is fetched to detect another page. */
    public List<Map<String, Object>> run(EntityManager em, Map<String, Object> args, int offset, int limit) {
        for (String key : args.keySet()) {
            if (!definition.parameters().containsKey(key) && !key.equals("pageSize") && !key.equals("cursor")) {
                throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                        "Unknown parameter '" + key + "'; this report takes " + definition.parameters().keySet());
            }
        }
        ReportQuery query = buildQuery(name -> {
            ReportDefinition.Parameter p = definition.parameters().get(name);
            Object v = args.get(name);
            if (v == null) {
                if (p.required()) {
                    throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                            "Missing required parameter '" + name + "'");
                }
                return Filters.ABSENT;
            }
            return typed(name, p.type(), v);
        });
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

    private static Object typed(String name, String type, Object v) {
        try {
            return switch (type) {
                case "integer" -> v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
                case "decimal" -> new BigDecimal(v.toString());
                case "date" -> LocalDate.parse(v.toString()).toString();
                case "boolean" -> v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
                default -> v.toString();
            };
        } catch (RuntimeException e) {
            throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                    "Parameter '" + name + "' must be a " + type + ", got " + v);
        }
    }

    private static Object sample(String type) {
        return switch (type) {
            case "integer" -> 0L;
            case "decimal" -> BigDecimal.ZERO;
            case "date" -> "2000-01-01";
            case "boolean" -> Boolean.TRUE;
            default -> "x";
        };
    }
}
