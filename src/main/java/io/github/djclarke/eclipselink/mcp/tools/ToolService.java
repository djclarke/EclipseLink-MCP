/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.QueryTimeoutException;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.exceptions.DatabaseException;
import org.eclipse.persistence.expressions.Expression;
import org.eclipse.persistence.expressions.ExpressionBuilder;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.queries.ReadAllQuery;
import org.eclipse.persistence.queries.ReportQuery;
import org.eclipse.persistence.queries.ReportQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.djclarke.eclipselink.mcp.definition.Descriptor;
import io.github.djclarke.eclipselink.mcp.definition.ReportDefinition;
import io.github.djclarke.eclipselink.mcp.json.EntityJson;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.query.AttributePath;
import io.github.djclarke.eclipselink.mcp.query.Cursors;
import io.github.djclarke.eclipselink.mcp.query.Filters;
import io.github.djclarke.eclipselink.mcp.query.Mappings;
import io.github.djclarke.eclipselink.mcp.query.NamedReport;

/**
 * The read-only MCP tools generated for one server definition (docs/server-definition.md 4.3). Each tool is a
 * {@link Tool}: metadata plus a handler from arguments to structured content. The MCP wiring lives in
 * {@code McpServerFactory}; this class has no MCP SDK dependency so it can be tested directly.
 */
public final class ToolService {

    private static final Logger AUDIT = LoggerFactory.getLogger("eclipselink.mcp.audit");

    /** A tool's metadata and handler. The handler throws {@link ToolException} for agent-facing errors. */
    public record Tool(String name, String title, String description, Map<String, Object> inputSchema,
                       Function<Map<String, Object>, Map<String, Object>> handler) {
    }

    private final ModelRuntime model;
    private final Descriptor.Limits limits;
    private final boolean audit;
    private final EntityJson json;
    private final Map<String, NamedReport> reports = new LinkedHashMap<>();
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolService(ModelRuntime model) {
        this.model = model;
        Descriptor d = model.definition().descriptor();
        this.limits = d.limits();
        this.audit = d.logging().audit();
        this.json = new EntityJson(limits.maxPageSize());

        for (ReportDefinition r : model.definition().reports()) {
            reports.put(r.toolName(), NamedReport.compile(r, model));
        }

        Descriptor.Tools enabled = d.tools();
        if (enabled.list_entities()) {
            add(new Tool("list_entities", "List entities",
                    "List the entities (tables) this server exposes, with descriptions, keys and relationships. "
                            + "Start here.",
                    schema(Map.of(), List.of()), this::listEntities));
        }
        if (enabled.describe_entity()) {
            add(new Tool("describe_entity", "Describe an entity",
                    "Attributes, types, key, relationships and the JSON Schema of one entity.",
                    schema(Map.of("entity", entityParam()), List.of("entity")), this::describeEntity));
        }
        if (enabled.find_entity()) {
            add(new Tool("find_entity", "Find an entity by key",
                    "Fetch one entity by primary key. Optionally choose attributes with 'select' (paths such as "
                            + "'customer.name' or 'lines.quantity') and how deep to nest related entities.",
                    schema(ordered(
                            "entity", entityParam(),
                            "id", Map.of("description", "Primary key value, or an object of key attributes"),
                            "select", selectParam(),
                            "depth", Map.of("type", "integer", "minimum", 0, "maximum", limits.maxDepth(),
                                    "description", "Levels of to-one relationships to nest (default 1)")),
                            List.of("entity", "id")),
                    this::findEntity));
        }
        if (enabled.query_entities()) {
            add(new Tool("query_entities", "Query entities",
                    "Query one entity with a structured filter, sorting and paging. Filter nodes: "
                            + "{\"and\":[...]}, {\"or\":[...]}, {\"not\":{...}} or "
                            + "{\"path\":\"customer.region\",\"op\":\"eq\",\"value\":\"EAST\"}. Operators: eq ne lt le "
                            + "gt ge in notIn like isNull isNotNull between. Prefer report tools for totals.",
                    schema(ordered(
                            "entity", entityParam(),
                            "filter", Map.of("type", "object", "description", "Structured filter (see description)"),
                            "select", selectParam(),
                            "orderBy", Map.of("type", "array", "items", Map.of("type", "object", "properties",
                                    ordered("by", Map.of("type", "string"),
                                            "dir", Map.of("type", "string", "enum", List.of("asc", "desc"))),
                                    "required", List.of("by"))),
                            "pageSize", Map.of("type", "integer", "minimum", 1, "maximum", limits.maxPageSize()),
                            "cursor", Map.of("type", "string", "description", "nextCursor from a previous page"),
                            "count", Map.of("type", "boolean", "description", "Also return the total match count")),
                            List.of("entity")),
                    this::queryEntities));
        }
        if (enabled.reports()) {
            for (NamedReport r : reports.values()) {
                ReportDefinition def = r.definition();
                add(new Tool(def.toolName(), def.title(),
                        (def.description() == null ? "" : def.description() + " ")
                                + "Columns: " + String.join(", ", r.columns().stream().map(NamedReport.OutputColumn::name).toList()) + ".",
                        r.inputSchema(limits.maxPageSize()), args -> runReport(r, args)));
            }
        }
    }

    public Collection<Tool> tools() {
        return tools.values();
    }

    public Collection<NamedReport> reports() {
        return reports.values();
    }

    /** Calls a tool by name, with audit logging. Throws {@link ToolException} for agent-facing errors. */
    public Map<String, Object> call(String name, Map<String, Object> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolException(ToolException.Code.INVALID_PARAMETER, "Unknown tool '" + name + "'");
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return tool.handler().apply(safeArgs);
        } catch (ToolException e) {
            outcome = e.code().name();
            throw e;
        } catch (QueryTimeoutException e) {
            outcome = "TIMEOUT";
            throw new ToolException(ToolException.Code.TIMEOUT,
                    "The query took longer than " + limits.queryTimeoutSeconds() + "s; narrow the filter");
        } catch (PersistenceException | DatabaseException e) {
            outcome = "DATABASE_ERROR";
            AUDIT.warn("Database error in {}: {}", name, e.toString());
            // Never pass SQL, connection details or driver messages back to the agent.
            throw new ToolException(ToolException.Code.DATABASE_ERROR, "The database reported an error");
        } finally {
            if (audit) {
                AUDIT.info("tool={} outcome={} ms={} args={}", name, outcome,
                        (System.nanoTime() - start) / 1_000_000, safeArgs);
            }
        }
    }

    // ---- tools ---------------------------------------------------------------------------------------------

    private Map<String, Object> listEntities(Map<String, Object> args) {
        List<Object> entities = new ArrayList<>();
        for (ClassDescriptor d : model.entities().values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", d.getAlias());
            putIfNotNull(e, "description", ModelRuntime.description(d));
            e.put("key", Mappings.keyAttributes(d));
            e.put("relationships", Mappings.of(d).stream().filter(DatabaseMapping::isForeignReferenceMapping)
                    .map(DatabaseMapping::getAttributeName).toList());
            entities.add(e);
        }
        return Map.of("entities", entities);
    }

    private Map<String, Object> describeEntity(Map<String, Object> args) {
        return describe(entityArg(args));
    }

    /** Also used for the entity resources. */
    public Map<String, Object> describe(ClassDescriptor d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.getAlias());
        putIfNotNull(out, "description", ModelRuntime.description(d));
        out.put("key", Mappings.keyAttributes(d));
        List<Object> attributes = new ArrayList<>();
        List<Object> relationships = new ArrayList<>();
        Map<String, Object> props = new LinkedHashMap<>();
        for (DatabaseMapping m : Mappings.of(d)) {
            if (m.isDirectToFieldMapping()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", m.getAttributeName());
                a.put("type", Mappings.typeName(Mappings.attributeType(m)));
                a.put("key", Mappings.isKey(d, m));
                a.put("nullable", m.getField() != null && m.getField().isNullable() && !Mappings.isKey(d, m));
                putIfNotNull(a, "description", Mappings.description(m));
                attributes.add(a);
                Map<String, Object> s = new LinkedHashMap<>(Mappings.jsonSchema(Mappings.attributeType(m)));
                putIfNotNull(s, "description", Mappings.description(m));
                props.put(m.getAttributeName(), s);
            } else {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", m.getAttributeName());
                r.put("target", m.getReferenceDescriptor().getAlias());
                r.put("cardinality", m.isCollectionMapping() ? "many" : "one");
                r.put("defaultOutput", m.isCollectionMapping() ? "omitted unless selected"
                        : "nested while depth remains, otherwise a reference holding the target key");
                putIfNotNull(r, "description", Mappings.description(m));
                relationships.add(r);
                Map<String, Object> ref = Map.of("type", "object",
                        "description", "Related " + m.getReferenceDescriptor().getAlias());
                props.put(m.getAttributeName(), m.isCollectionMapping()
                        ? Map.of("type", "array", "items", ref) : ref);
            }
        }
        out.put("attributes", attributes);
        out.put("relationships", relationships);
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("type", "object");
        jsonSchema.put("properties", props);
        jsonSchema.put("required", Mappings.keyAttributes(d));
        out.put("jsonSchema", jsonSchema);
        return out;
    }

    private Map<String, Object> findEntity(Map<String, Object> args) {
        ClassDescriptor d = entityArg(args);
        Object id = args.get("id");
        if (id == null) {
            throw new ToolException(ToolException.Code.INVALID_PARAMETER, "'id' is required");
        }
        int depth = intArg(args, "depth", Math.min(1, limits.maxDepth()), 0, limits.maxDepth());
        EntityJson.Selection selection = EntityJson.Selection.parse(d, stringList(args.get("select"), "select"));

        ExpressionBuilder eb = new ExpressionBuilder();
        Expression where = keyExpression(d, eb, id);
        ReadAllQuery q = new ReadAllQuery(d.getJavaClass(), eb);
        q.setSelectionCriteria(where);
        EntityManager em = model.createEntityManager();
        try {
            List<?> found = JpaHelper.createQuery(q, em).setMaxResults(1).getResultList();
            if (found.isEmpty()) {
                throw new ToolException(ToolException.Code.NOT_FOUND, d.getAlias() + " " + id + " not found");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("entity", d.getAlias());
            out.put("item", json.render((DynamicEntity) found.get(0), d, selection, depth));
            return checkSize(out);
        } finally {
            em.close();
        }
    }

    private Map<String, Object> queryEntities(Map<String, Object> args) {
        ClassDescriptor d = entityArg(args);
        int pageSize = intArg(args, "pageSize", limits.defaultPageSize(), 1, limits.maxPageSize());
        int offset = Cursors.decode(args.get("cursor"));
        EntityJson.Selection selection = EntityJson.Selection.parse(d, stringList(args.get("select"), "select"));

        ExpressionBuilder eb = new ExpressionBuilder();
        List<AttributePath> used = new ArrayList<>();
        Expression where = Filters.toExpression(args.get("filter"), d, eb, Set.of(), null, used);

        ReadAllQuery q = new ReadAllQuery(d.getJavaClass(), eb);
        if (where != null) {
            q.setSelectionCriteria(where);
        }
        if (used.stream().anyMatch(AttributePath::traversesCollection)) {
            q.useDistinct();
        }
        Object orderBy = args.get("orderBy");
        if (orderBy != null) {
            if (!(orderBy instanceof List<?> list)) {
                throw new ToolException(ToolException.Code.INVALID_PARAMETER, "'orderBy' must be a list");
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || !(m.get("by") instanceof String by)) {
                    throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                            "Each orderBy entry needs 'by' (an attribute path)");
                }
                AttributePath p = AttributePath.resolve(d, by);
                if (!p.isBasic() || p.traversesCollection()) {
                    throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                            "orderBy '" + by + "' must be a basic attribute, not through a to-many relationship");
                }
                Expression e = p.toExpression(eb, Set.of());
                q.addOrdering("desc".equalsIgnoreCase(String.valueOf(m.get("dir"))) ? e.descending() : e.ascending());
            }
        }
        for (String key : Mappings.keyAttributes(d)) {
            q.addOrdering(eb.get(key).ascending()); // stable paging
        }

        EntityManager em = model.createEntityManager();
        try {
            List<?> found = JpaHelper.createQuery(q, em).setFirstResult(offset).setMaxResults(pageSize + 1)
                    .getResultList();
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < Math.min(pageSize, found.size()); i++) {
                items.add(json.render((DynamicEntity) found.get(i), d, selection, 0));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("entity", d.getAlias());
            out.put("items", items);
            if (found.size() > pageSize) {
                out.put("nextCursor", Cursors.encode(offset + pageSize));
            }
            if (Boolean.TRUE.equals(args.get("count"))) {
                out.put("count", count(d, args.get("filter"), em));
            }
            return checkSize(out);
        } finally {
            em.close();
        }
    }

    private Object count(ClassDescriptor d, Object filter, EntityManager em) {
        ExpressionBuilder cb = new ExpressionBuilder();
        List<AttributePath> used = new ArrayList<>();
        Expression where = Filters.toExpression(filter, d, cb, Set.of(), null, used);
        ReportQuery count = new ReportQuery(d.getJavaClass(), cb);
        Expression key = cb.get(Mappings.keyAttributes(d).get(0));
        boolean distinct = used.stream().anyMatch(AttributePath::traversesCollection);
        count.addAttribute("count", (distinct ? key.distinct() : key).count(), Long.class);
        if (where != null) {
            count.setSelectionCriteria(where);
        }
        Object total = JpaHelper.createQuery(count, em).getSingleResult();
        return total instanceof ReportQueryResult r ? r.getResults().get(0)
                : total instanceof Object[] a ? a[0] : total;
    }

    private Map<String, Object> runReport(NamedReport report, Map<String, Object> args) {
        int pageSize = intArg(args, "pageSize", limits.defaultPageSize(), 1, limits.maxPageSize());
        int offset = Cursors.decode(args.get("cursor"));
        EntityManager em = model.createEntityManager();
        try {
            List<Map<String, Object>> rows = report.run(em, args, offset, pageSize + 1);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("report", report.definition().name());
            List<Object> columns = new ArrayList<>();
            for (NamedReport.OutputColumn c : report.columns()) {
                columns.add(Map.of("name", c.name(), "type", c.schema().get("type")));
            }
            out.put("columns", columns);
            out.put("rows", rows.subList(0, Math.min(pageSize, rows.size())));
            if (rows.size() > pageSize) {
                out.put("nextCursor", Cursors.encode(offset + pageSize));
            }
            return checkSize(out);
        } finally {
            em.close();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private void add(Tool tool) {
        tools.put(tool.name(), tool);
    }

    private Map<String, Object> entityParam() {
        return Map.of("type", "string", "enum", List.copyOf(model.entities().keySet()),
                "description", "Entity name, from list_entities");
    }

    private static Map<String, Object> selectParam() {
        return Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Attribute paths to return, e.g. [\"id\", \"total\", \"customer.name\"]. "
                        + "Default: all basic attributes plus to-one references.");
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        s.put("additionalProperties", false);
        return s;
    }

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private ClassDescriptor entityArg(Map<String, Object> args) {
        Object name = args.get("entity");
        ClassDescriptor d = name == null ? null : model.entity(name.toString());
        if (d == null) {
            throw new ToolException(ToolException.Code.UNKNOWN_ENTITY,
                    "Unknown entity '" + name + "'; use one of " + model.entities().keySet());
        }
        return d;
    }

    private Expression keyExpression(ClassDescriptor d, ExpressionBuilder eb, Object id) {
        List<String> keys = Mappings.keyAttributes(d);
        Expression where = null;
        if (id instanceof Map<?, ?> composite) {
            for (String k : keys) {
                if (!composite.containsKey(k)) {
                    throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                            "'id' must contain key attribute '" + k + "'");
                }
                Expression e = eb.get(k).equal(Mappings.convert(composite.get(k),
                        Mappings.attributeType(d.getMappingForAttributeName(k)), k));
                where = where == null ? e : where.and(e);
            }
        } else {
            if (keys.size() != 1) {
                throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                        d.getAlias() + " has a composite key " + keys + "; pass 'id' as an object");
            }
            String k = keys.get(0);
            where = eb.get(k).equal(Mappings.convert(id,
                    Mappings.attributeType(d.getMappingForAttributeName(k)), k));
        }
        return where;
    }

    private static int intArg(Map<String, Object> args, String name, int dflt, int min, int max) {
        Object v = args.get(name);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof Number n) || n.intValue() < min || n.intValue() > max) {
            throw new ToolException(ToolException.Code.INVALID_PARAMETER,
                    "'" + name + "' must be an integer from " + min + " to " + max);
        }
        return n.intValue();
    }

    private static List<String> stringList(Object v, String name) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof List<?> list) || !list.stream().allMatch(x -> x instanceof String)) {
            throw new ToolException(ToolException.Code.INVALID_PARAMETER, "'" + name + "' must be a list of strings");
        }
        return list.stream().map(Object::toString).toList();
    }

    private Map<String, Object> checkSize(Map<String, Object> out) {
        long size = Json.size(out);
        if (size > limits.maxResultBytes()) {
            throw new ToolException(ToolException.Code.LIMIT_EXCEEDED, "Result is " + size
                    + " bytes, over the " + limits.maxResultBytes() + " byte limit; use a smaller pageSize or 'select'");
        }
        return out;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
